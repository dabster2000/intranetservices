package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentConsent;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Talent-pool consent commands (ATS P19, spec §5.5): token minting for
 * the renewal emails and the public {@code /consent/[token]} page's
 * grant / withdraw actions.
 *
 * <h3>Token model</h3>
 * A token is 32 bytes from {@link SecureRandom}, base64url-encoded
 * (43 characters). Only its SHA-256 hex digest is stored
 * ({@code recruitment_consents.token_hash}, unique) — a database leak
 * exposes no usable links. Tokens are single-candidate by construction
 * (the hash IS the row lookup), expire at {@code token_expires_at}
 * (stamped at mint = the candidate's retention deadline, so a link never
 * outlives the data it controls) and are cleared by the anonymizer.
 * The page stays usable for a change of heart: granting and later
 * withdrawing (or the reverse) with the same link both work until the
 * token expires.
 *
 * <h3>Uniform failure</h3>
 * {@link #findByToken}, {@link #grant} and {@link #withdraw} answer
 * {@code null} for EVERY invalid case — malformed, unknown, expired
 * token, or a gone/anonymized candidate. Callers translate {@code null}
 * to one uniform NOT_FOUND so the public endpoint never reveals whether
 * a token ever existed (plan §P19 DoD).
 *
 * <h3>Clock coupling (spec §5.5)</h3>
 * Granting sets {@code retention_deadline = granted_at + 12 months}
 * (= the consent's {@code expires_at} — one clock, two views).
 * Withdrawing resumes the process countdown:
 * {@code retention_deadline = process_ended_at + 6 months} when a
 * process ever ended, {@code NULL} (no clock — the P4 "NULL means no
 * clock running" convention) otherwise. A resumed deadline already in
 * the past means the next nightly sweep anonymizes — "without undue
 * delay" is exactly the withdrawn candidate's expectation.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentConsentService {

    /**
     * Consent validity — the DPO-signed-off 12-month policy constant
     * (spec §5.5); deliberately NOT an app setting (see
     * {@link RecruitmentGdprParameters}).
     */
    public static final int CONSENT_MONTHS = 12;

    /** 32 random bytes, base64url without padding = 43 chars. */
    private static final Pattern TOKEN_SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");

    /** Repeat-grant debounce: a second grant within this window is a no-op. */
    private static final int REGRANT_DEBOUNCE_MINUTES = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    RecruitmentEmailService emailService;

    /** What the public page needs to render — nothing more. */
    public record ConsentView(String candidateFirstName, RecruitmentConsentKind kind,
                              RecruitmentConsentStatus status, LocalDateTime grantedAt,
                              LocalDateTime expiresAt, LocalDateTime retentionDeadline) {
    }

    /** A freshly minted raw token (goes into ONE email, never stored). */
    public record MintedToken(String token, String consentUuid) {
    }

    // ------------------------------------------------------------------
    // Minting (sweep-driven)
    // ------------------------------------------------------------------

    /**
     * Mint (or re-mint) the consent-page token for a candidate's
     * talent-pool consent. Reuses the candidate's newest consent row of
     * the kind; creates a REQUESTED row (+ {@code CONSENT_REQUESTED}
     * event, scheduler actor) when none exists — sourced pool candidates
     * never went through the P4/P5 consent capture. Re-minting replaces
     * the previous token (the second renewal email carries a fresh link;
     * the first link dies — one live link per candidate).
     *
     * @param tokenExpiresAt when the link stops working (the sweep passes
     *                       the candidate's retention deadline)
     * @return the raw token for the email link
     */
    @Transactional
    public MintedToken mintToken(String candidateUuid, LocalDateTime tokenExpiresAt) {
        RecruitmentConsent consent = newestOfKind(candidateUuid);
        if (consent == null) {
            consent = new RecruitmentConsent();
            consent.setCandidateUuid(candidateUuid);
            consent.setKind(RecruitmentConsentKind.TALENT_POOL_RETENTION);
            consent.setStatus(RecruitmentConsentStatus.REQUESTED);
            consent.persist();
            eventRecorder.record(RecruitmentEventBuilder
                    .event(RecruitmentEventType.CONSENT_REQUESTED)
                    .candidate(candidateUuid)
                    .actorScheduler()
                    .visibility(emailService.visibilityFor(candidateUuid))
                    .payload("kind", RecruitmentConsentKind.TALENT_POOL_RETENTION.name())
                    .payload("consent_uuid", consent.getUuid())
                    .payload("origin", "gdpr_sweep"));
        }
        String token = generateToken();
        consent.setTokenHash(sha256Hex(token));
        consent.setTokenExpiresAt(tokenExpiresAt);
        return new MintedToken(token, consent.getUuid());
    }

    // ------------------------------------------------------------------
    // Public page reads / actions (token-addressed)
    // ------------------------------------------------------------------

    /** Resolve a presented token; {@code null} on every invalid case. */
    public ConsentView findByToken(String rawToken) {
        Resolved resolved = resolve(rawToken);
        return resolved == null ? null : view(resolved);
    }

    /**
     * Grant (or renew) talent-pool consent via the public page.
     * Sets the consent GRANTED for {@value #CONSENT_MONTHS} months and
     * pushes the candidate's retention deadline to match (spec §5.5).
     * A repeat grant within {@value #REGRANT_DEBOUNCE_MINUTES} minutes is
     * a silent no-op (double-click safety); a later repeat is a renewal.
     *
     * @return the updated view, or {@code null} for every invalid token case
     */
    @Transactional
    public ConsentView grant(String rawToken) {
        Resolved resolved = resolve(rawToken);
        if (resolved == null) {
            return null;
        }
        RecruitmentConsent consent = resolved.consent();
        RecruitmentCandidate candidate = resolved.candidate();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (consent.getStatus() == RecruitmentConsentStatus.GRANTED
                && consent.getGrantedAt() != null
                && consent.getGrantedAt().isAfter(now.minusMinutes(REGRANT_DEBOUNCE_MINUTES))) {
            return view(resolved);
        }
        consent.setStatus(RecruitmentConsentStatus.GRANTED);
        consent.setGrantedAt(now);
        consent.setExpiresAt(now.plusMonths(CONSENT_MONTHS));
        candidate.setRetentionDeadline(now.plusMonths(CONSENT_MONTHS));
        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.CONSENT_GRANTED)
                .candidate(candidate.getUuid())
                .actorCandidate()
                .visibility(emailService.visibilityFor(candidate.getUuid()))
                .payload("kind", consent.getKind().name())
                .payload("consent_uuid", consent.getUuid())
                .payload("origin", "consent_page")
                .payload("expires_at", consent.getExpiresAt().toString()));
        log.infof("Consent %s granted via public page (candidate=%s, expires=%s)",
                consent.getUuid(), candidate.getUuid(), consent.getExpiresAt());
        return view(resolved);
    }

    /**
     * Withdraw talent-pool consent via the public page and resume the
     * process countdown (class javadoc). Withdrawing an already-WITHDRAWN
     * consent is a silent no-op.
     *
     * @return the updated view, or {@code null} for every invalid token case
     */
    @Transactional
    public ConsentView withdraw(String rawToken) {
        Resolved resolved = resolve(rawToken);
        if (resolved == null) {
            return null;
        }
        RecruitmentConsent consent = resolved.consent();
        RecruitmentCandidate candidate = resolved.candidate();
        if (consent.getStatus() == RecruitmentConsentStatus.WITHDRAWN) {
            return view(resolved);
        }
        consent.setStatus(RecruitmentConsentStatus.WITHDRAWN);
        LocalDateTime resumed = candidate.getProcessEndedAt() == null
                ? null
                : candidate.getProcessEndedAt()
                        .plusMonths(RecruitmentApplicationService.RETENTION_MONTHS);
        candidate.setRetentionDeadline(resumed);
        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.CONSENT_WITHDRAWN)
                .candidate(candidate.getUuid())
                .actorCandidate()
                .visibility(emailService.visibilityFor(candidate.getUuid()))
                .payload("kind", consent.getKind().name())
                .payload("consent_uuid", consent.getUuid())
                .payload("origin", "consent_page")
                .payload("resumed_retention_deadline",
                        resumed == null ? null : resumed.toString()));
        log.infof("Consent %s withdrawn via public page (candidate=%s, resumed deadline=%s)",
                consent.getUuid(), candidate.getUuid(), resumed);
        return view(resolved);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private record Resolved(RecruitmentConsent consent, RecruitmentCandidate candidate) {
    }

    private Resolved resolve(String rawToken) {
        if (rawToken == null || !TOKEN_SHAPE.matcher(rawToken).matches()) {
            return null;
        }
        RecruitmentConsent consent = RecruitmentConsent
                .<RecruitmentConsent>find("tokenHash", sha256Hex(rawToken))
                .firstResult();
        if (consent == null) {
            return null;
        }
        if (consent.getTokenExpiresAt() == null
                || consent.getTokenExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            return null;
        }
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(consent.getCandidateUuid());
        if (candidate == null || candidate.getStatus() == CandidateStatus.ANONYMIZED) {
            return null;
        }
        return new Resolved(consent, candidate);
    }

    private static ConsentView view(Resolved resolved) {
        return new ConsentView(
                resolved.candidate().getFirstName(),
                resolved.consent().getKind(),
                resolved.consent().getStatus(),
                resolved.consent().getGrantedAt(),
                resolved.consent().getExpiresAt(),
                resolved.candidate().getRetentionDeadline());
    }

    private static RecruitmentConsent newestOfKind(String candidateUuid) {
        return RecruitmentConsent
                .<RecruitmentConsent>find(
                        "candidateUuid = ?1 and kind = ?2 ORDER BY createdAt DESC",
                        candidateUuid, RecruitmentConsentKind.TALENT_POOL_RETENTION)
                .firstResult();
    }

    static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
