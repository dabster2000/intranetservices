package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentConsent;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * The P19 GDPR clock (plan §P19, spec §5.5 "GdprClock reactor + nightly
 * job"): one nightly pass over the retention machinery. Like the P17 SLA
 * sweep — and for the same reason — this is a clock-driven sweep service,
 * not a {@code RecruitmentReactor} subclass: no event can announce that
 * time has passed (findings §P17, anticipated for P19).
 *
 * <h3>The three sub-sweeps</h3>
 * <ol>
 *   <li><b>Consent expiry</b> — GRANTED consents past {@code expires_at}
 *       flip to EXPIRED (+ {@code CONSENT_EXPIRED} event). No deadline
 *       recompute: granting set {@code retention_deadline = expires_at},
 *       so the anonymization sub-sweep takes over naturally.</li>
 *   <li><b>Consent-renewal emails</b> — POOLED candidates approaching
 *       their retention deadline get the {@code CONSENT_RENEWAL} template
 *       with a tokenized {@code /consent/[token]} link: the first email at
 *       deadline − {@code recruitment.gdpr.renewal-first-days} (default 30),
 *       exactly one repeat at − {@code renewal-second-days} (default 7).
 *       Idempotency is event-derived (the P12/P17 idiom): each send's
 *       {@code EMAIL_SENT} event carries {@code renewal_number} and the
 *       deadline it was sent for — re-runs, concurrent instances and a
 *       later NEW deadline (after a re-grant) all count correctly.
 *       Candidates without an email address are a visible INFO skip; they
 *       surface in the DPO queue instead. Non-pooled candidates get no
 *       renewal (the extra-purpose consent is talent-pool retention,
 *       spec §5.5) — their data simply expires.</li>
 *   <li><b>Auto-anonymization</b> — candidates past
 *       {@code retention_deadline} with no live GRANTED consent are handed
 *       to {@link RecruitmentAnonymizerService} (mode AUTO). HIRED and
 *       already-ANONYMIZED candidates are excluded by query; each
 *       candidate runs in its own transaction so one failure never stops
 *       the sweep.</li>
 * </ol>
 *
 * <h3>Gating</h3>
 * Everything sits behind {@code recruitment.gdpr.enabled} — read in a
 * fresh transaction per sweep (the no-cache flag contract). <b>Enabling
 * that flag is the moment automatic deletion starts</b> (plan §P19).
 * Art. 14 has no sub-sweep: its queue (T − {@code art14-warning-days})
 * is computed at read time by {@link RecruitmentGdprQueueService} and the
 * notice send is an explicit DPO action, never automatic.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentGdprService {

    /** Template key of the renewal email (V448 seed; editable in settings). */
    public static final String KEY_CONSENT_RENEWAL = "CONSENT_RENEWAL";

    /**
     * Spec §5.5: exactly two renewal emails per retention deadline —
     * the −30 d ask and the −7 d final warning.
     */
    static final int MAX_RENEWALS_PER_DEADLINE = 2;

    /**
     * Minimum spacing between the two renewal emails. Matters only when
     * the flag is enabled late (a candidate already inside the −7 d window
     * would otherwise get both mails on consecutive nights).
     */
    static final int MIN_HOURS_BETWEEN_RENEWALS = 48;

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentGdprParameters parameters;

    @Inject
    RecruitmentConsentService consentService;

    @Inject
    RecruitmentEmailService emailService;

    @Inject
    RecruitmentAnonymizerService anonymizerService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    /**
     * Public base URL the consent links are built on. The consent page is
     * served by the intranet frontend but excluded from its auth gate
     * (public page, P5 {@code /apply} pattern).
     */
    @ConfigProperty(name = "dk.trustworks.recruitment.consent.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String consentBaseUrl;

    /** Result of one sweep, for logs and the batchlet exit status. */
    public record SweepSummary(boolean enabled, int consentsExpired, int renewalsSent,
                               int anonymized, int failures) {

        @Override
        public String toString() {
            if (!enabled) {
                return "gdpr-sweep[disabled]";
            }
            return "gdpr-sweep[expired=%d, renewals=%d, anonymized=%d%s]"
                    .formatted(consentsExpired, renewalsSent, anonymized,
                            failures > 0 ? ", failures=" + failures : "");
        }
    }

    /**
     * Run one full sweep. Safe to call at any time and from several
     * instances concurrently — idempotency is event-derived / state-derived
     * per sub-sweep (class javadoc). Each side effect commits independently.
     */
    public SweepSummary sweep() {
        if (!inTx(featureFlag::isGdprEnabled)) {
            log.debug("recruitment-gdpr-sweep skipped: recruitment.gdpr.enabled=false");
            return new SweepSummary(false, 0, 0, 0, 0);
        }
        Counters counters = new Counters();
        sweepExpiredConsents(counters);
        sweepRenewalEmails(counters);
        sweepRetentionDeadlines(counters);
        return new SweepSummary(true, counters.expired, counters.renewals,
                counters.anonymized, counters.failures);
    }

    private static final class Counters {
        int expired;
        int renewals;
        int anonymized;
        int failures;
    }

    // ------------------------------------------------------------------
    // Sub-sweep 1 — consent expiry
    // ------------------------------------------------------------------

    private void sweepExpiredConsents(Counters counters) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<String> dueUuids = inTx(() -> RecruitmentConsent
                .<RecruitmentConsent>list("status = ?1 and expiresAt is not null and expiresAt <= ?2",
                        RecruitmentConsentStatus.GRANTED, now).stream()
                .map(RecruitmentConsent::getUuid)
                .toList());
        for (String consentUuid : dueUuids) {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    RecruitmentConsent consent = RecruitmentConsent.findById(consentUuid);
                    if (consent == null
                            || consent.getStatus() != RecruitmentConsentStatus.GRANTED) {
                        return; // raced by a re-grant/withdraw — nothing to expire
                    }
                    consent.setStatus(RecruitmentConsentStatus.EXPIRED);
                    eventRecorder.record(RecruitmentEventBuilder
                            .event(RecruitmentEventType.CONSENT_EXPIRED)
                            .candidate(consent.getCandidateUuid())
                            .actorScheduler()
                            .visibility(emailService.visibilityFor(consent.getCandidateUuid()))
                            .payload("kind", consent.getKind().name())
                            .payload("consent_uuid", consent.getUuid())
                            .payload("expired_at", String.valueOf(consent.getExpiresAt())));
                });
                counters.expired++;
            } catch (Exception e) {
                counters.failures++;
                log.warnf(e, "GDPR sweep: consent expiry for %s failed — continuing "
                        + "(next sweep retries)", consentUuid);
            }
        }
    }

    // ------------------------------------------------------------------
    // Sub-sweep 2 — consent-renewal emails
    // ------------------------------------------------------------------

    private void sweepRenewalEmails(Counters counters) {
        int firstDays = inTx(parameters::renewalFirstDays);
        int secondDays = inTx(parameters::renewalSecondDays);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        RecruitmentEmailTemplate template = inTx(() ->
                emailService.findActiveByKey(KEY_CONSENT_RENEWAL));
        if (template == null) {
            log.warn("GDPR sweep: no active CONSENT_RENEWAL template — renewal emails skipped "
                    + "(re-activate it on /recruitment/settings)");
            return;
        }

        // In the window: POOLED, clock running, deadline within the first-
        // email horizon but not yet passed (past-deadline candidates belong
        // to the anonymization sub-sweep).
        List<String> candidateUuids = inTx(() -> RecruitmentCandidate
                .<RecruitmentCandidate>list(
                        "status = ?1 and retentionDeadline is not null "
                                + "and retentionDeadline > ?2 and retentionDeadline <= ?3",
                        CandidateStatus.POOLED, now, now.plusDays(firstDays)).stream()
                .map(RecruitmentCandidate::getUuid)
                .toList());

        for (String candidateUuid : candidateUuids) {
            try {
                boolean sent = QuarkusTransaction.requiringNew().call(() ->
                        sendRenewalIfDue(candidateUuid, template.getUuid(), now, secondDays));
                if (sent) {
                    counters.renewals++;
                }
            } catch (Exception e) {
                counters.failures++;
                log.warnf(e, "GDPR sweep: renewal email for candidate %s failed — continuing "
                        + "(next sweep retries)", candidateUuid);
            }
        }
    }

    /**
     * Decide-and-send for one candidate, inside one fresh transaction:
     * count the prior renewals FOR THE CURRENT DEADLINE from the
     * {@code EMAIL_SENT} events, then mint the token and send. The mail
     * row, the token and the event commit together — a failure retries the
     * whole unit next sweep.
     */
    private boolean sendRenewalIfDue(String candidateUuid, String templateUuid,
                                     LocalDateTime now, int secondDays) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid);
        if (candidate == null || candidate.getStatus() != CandidateStatus.POOLED
                || candidate.getRetentionDeadline() == null
                || !candidate.getRetentionDeadline().isAfter(now)) {
            return false; // raced by a grant/withdraw/anonymize since the scan
        }
        String deadlineKey = candidate.getRetentionDeadline().toString();
        List<LocalDateTime> priorSends = priorRenewalSends(candidateUuid, deadlineKey);
        int renewalNumber = priorSends.size() + 1;
        if (renewalNumber > MAX_RENEWALS_PER_DEADLINE) {
            return false;
        }
        if (renewalNumber == 2) {
            if (now.isBefore(candidate.getRetentionDeadline().minusDays(secondDays))) {
                return false; // second email waits for the −7 d window
            }
            LocalDateTime firstSend = priorSends.getFirst();
            if (firstSend.isAfter(now.minusHours(MIN_HOURS_BETWEEN_RENEWALS))) {
                return false; // late flag-on: never both mails on consecutive nights
            }
        }
        if (candidate.getEmail() == null || candidate.getEmail().isBlank()) {
            log.infof("GDPR sweep: candidate %s has no email address — renewal skipped, "
                    + "the DPO queue shows the unanswered consent", candidateUuid);
            return false;
        }

        RecruitmentEmailTemplate template = RecruitmentEmailTemplate.findById(templateUuid);
        if (template == null || !template.isActive()) {
            return false;
        }
        RecruitmentConsentService.MintedToken minted = consentService.mintToken(
                candidateUuid, candidate.getRetentionDeadline());
        RecruitmentEmailRenderer.Rendered rendered = RecruitmentEmailRenderer.render(
                template.getSubject(), template.getBody(), candidate, null,
                Map.of("consent_link", consentBaseUrl + "/consent/" + minted.token()));

        emailService.send(candidate, null, null,
                template.getTemplateKey(), template.getUuid(),
                rendered.subject(), rendered.body(), "GDPR_SWEEP", null,
                RecruitmentEventBuilder.event(RecruitmentEventType.EMAIL_SENT)
                        .actorScheduler()
                        .payload("renewal_number", renewalNumber)
                        .payload("retention_deadline", deadlineKey)
                        .payload("consent_uuid", minted.consentUuid()),
                emailService.visibilityFor(candidateUuid));
        log.infof("GDPR sweep: renewal email %d/%d queued for candidate %s (deadline %s)",
                renewalNumber, MAX_RENEWALS_PER_DEADLINE, candidateUuid, deadlineKey);
        return true;
    }

    /**
     * Timestamps of prior renewal sends for THIS deadline, oldest first —
     * derived from the {@code EMAIL_SENT} events (template key + the
     * deadline they were sent for). A re-granted candidate gets a NEW
     * deadline, which resets the pair by construction.
     */
    private List<LocalDateTime> priorRenewalSends(String candidateUuid, String deadlineKey) {
        return RecruitmentEvent
                .<RecruitmentEvent>list("candidateUuid = ?1 and eventType = ?2",
                        candidateUuid, RecruitmentEventType.EMAIL_SENT).stream()
                .filter(e -> {
                    Map<String, Object> payload = parse(e.getPayload());
                    return KEY_CONSENT_RENEWAL.equals(payload.get("template_key"))
                            && deadlineKey.equals(payload.get("retention_deadline"));
                })
                .map(RecruitmentEvent::getOccurredAt)
                .sorted()
                .toList();
    }

    // ------------------------------------------------------------------
    // Sub-sweep 3 — auto-anonymization at the retention deadline
    // ------------------------------------------------------------------

    private void sweepRetentionDeadlines(Counters counters) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<String> dueUuids = inTx(() -> RecruitmentCandidate
                .<RecruitmentCandidate>list(
                        "retentionDeadline is not null and retentionDeadline <= ?1 "
                                + "and status not in ?2",
                        now, List.of(CandidateStatus.HIRED, CandidateStatus.ANONYMIZED)).stream()
                .map(RecruitmentCandidate::getUuid)
                .toList());

        for (String candidateUuid : dueUuids) {
            try {
                // Defensive re-check inside its own transaction: a consent
                // grant moves the deadline, so a candidate with a live
                // GRANTED consent must never be auto-erased even if the
                // deadline column lagged (belt-and-braces; logged loudly).
                boolean hasLiveConsent = QuarkusTransaction.requiringNew().call(() ->
                        RecruitmentConsent.count(
                                "candidateUuid = ?1 and status = ?2 and expiresAt > ?3",
                                candidateUuid, RecruitmentConsentStatus.GRANTED, now) > 0);
                if (hasLiveConsent) {
                    log.warnf("GDPR sweep: candidate %s is past the retention deadline but "
                            + "holds a live GRANTED consent — deadline/consent mismatch, "
                            + "NOT anonymizing (fix the deadline or wait for expiry)",
                            candidateUuid);
                    continue;
                }
                RecruitmentAnonymizerService.AnonymizationSummary summary =
                        anonymizerService.anonymize(candidateUuid,
                                RecruitmentAnonymizerService.Mode.AUTO, null);
                if (!summary.alreadyAnonymized()) {
                    counters.anonymized++;
                }
            } catch (Exception e) {
                counters.failures++;
                log.warnf(e, "GDPR sweep: auto-anonymization of candidate %s failed — "
                        + "continuing (next sweep retries)", candidateUuid);
            }
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Reads on batch threads need a transaction (lazily-bound EntityManager). */
    private <T> T inTx(java.util.function.Supplier<T> work) {
        return QuarkusTransaction.requiringNew().call(work::get);
    }
}
