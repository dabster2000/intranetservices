package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactor;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Tells an employee that a job applicant named them (change request (e),
 * 2026-09-01). Sibling of {@link ReferrerNotificationReactor}, and
 * deliberately its opposite in tone: that one congratulates a colleague on
 * a referral they actually made, this one discloses a claim they had no
 * part in.
 *
 * <h3>The wording is the point</h3>
 * The public form asks "Kender du nogen hos Trustworks?" and nobody
 * verifies the answer. The named employee is a data subject of an
 * assertion made about them by a stranger, so the DM must say three
 * things and does: the applicant wrote this, we have not confirmed it,
 * and it is not a recommendation from you. Reusing the referrer cadence's
 * "Your referral X…" copy here would tell a lie in the employee's own
 * Slack — which is why {@link ReferrerNotificationReactor} is explicitly
 * suppressed for these links.
 *
 * <h3>Cadence</h3>
 * Exactly ONE DM per candidate, at the moment of the claim — no milestone
 * follow-ups: the employee has no stake in a pipeline they never opened.
 * Bookkept durably as {@code APPLICANT_REFERRER_NOTIFIED} (event-derived
 * state, the P9 idiom; no new table), so a redelivery cannot DM twice.
 * <p>
 * Like the referrer cadence the DM carries the candidate's name only — no
 * position title, no stage, no CV, nothing about other candidates (the §P6
 * "no candidate handle" rule).
 * <p>
 * Gated by {@code recruitment.apply.referrer-claim.enabled}. That flag is
 * the legal launch gate for the whole change request, so it gates the
 * notice as well as the capture: flipping it off silences this reactor
 * even for claims already stored.
 */
@JBossLog
@ApplicationScoped
public class ApplicantReferrerNotificationReactor extends RecruitmentReactor {

    public static final String NAME = "applicant-referrer-notifications";

    /**
     * Rolling per-RECIPIENT DM cap (security review 2026-09-01, F3). The
     * per-candidate dedupe is not a per-person limit: a candidate row is
     * reused only by email address, so submissions from a1@…, a2@… name the
     * same colleague through a fresh candidate every time. Three notices in a
     * day is already generous for a genuine coincidence and cheap to explain;
     * beyond it the claim is still recorded, only the ping stops.
     */
    static final int MAX_DMS_PER_RECIPIENT = 3;

    /** The window {@link #MAX_DMS_PER_RECIPIENT} is counted over. */
    static final Duration RECIPIENT_WINDOW = Duration.ofHours(24);

    /** Path of the notice the DM links to; §6 is the named colleague's part. */
    static final String PRIVACY_NOTICE_PATH = "/apply/privacy";

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    SlackService slackService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    /** Same property the offer reactor and the digest renderer already use. */
    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    @Override
    public String name() {
        return NAME;
    }

    /**
     * One live try + two catch-up retries, then durable SKIPPED — the same
     * best-effort posture as the referrer cadence: a disclosure DM must
     * never block the reactor watermark on persistent Slack trouble.
     */
    @Override
    protected int maxDeliveryAttempts() {
        return 3;
    }

    @Override
    protected void handle(RecruitmentEvent event) throws Exception {
        if (event.getEventType() != RecruitmentEventType.APPLICANT_REFERRER_CLAIMED) {
            return; // not ours (also ignores our own APPLICANT_REFERRER_NOTIFIED)
        }
        if (!featureFlag.isApplyReferrerClaimEnabled()) {
            return; // launch gate; offset advances, no backfill on later enable
        }
        String candidateUuid = event.getCandidateUuid();
        if (candidateUuid == null) {
            return;
        }
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid);
        if (candidate == null) {
            return;
        }
        if (candidate.getReferredByUserUuid() == null) {
            // The claim did not resolve to an employee — the text is kept as
            // an external referrer name and there is nobody to tell. Never
            // guess a recipient from a name that did not match.
            return;
        }
        if (candidate.getStatus() == CandidateStatus.ANONYMIZED) {
            return; // P19 erased the name; erased PII must not resurface in Slack
        }
        if (alreadyNotified(candidateUuid)) {
            return;
        }
        if (recentlyNotified(candidate.getReferredByUserUuid())) {
            // Security review 2026-09-01 (F3). The per-candidate dedupe above
            // is not a per-PERSON limit: a candidate is reused only by email,
            // so an attacker submitting from a1@…, a2@… mints a fresh
            // candidate every time and every one of them is a fresh DM at the
            // same colleague. That is a harassment primitive, so the recipient
            // gets a rolling cap of their own. The claim is still STORED and
            // still on the profile — only the DM is suppressed, because the
            // Art. 14 duty is discharged by the notice, not by a Slack ping,
            // and silently dropping the data would be the worse trade.
            log.warnf("Applicant referrer notice: user %s is at the %d-per-%dh DM cap — "
                            + "claim on candidate %s recorded, DM suppressed",
                    candidate.getReferredByUserUuid(), MAX_DMS_PER_RECIPIENT,
                    RECIPIENT_WINDOW.toHours(), candidateUuid);
            return;
        }
        User named = User.findById(candidate.getReferredByUserUuid());
        if (named == null || named.getSlackusername() == null
                || named.getSlackusername().isBlank()) {
            log.infof("Applicant referrer notice: user %s has no Slack link — skipping DM "
                            + "for candidate %s",
                    candidate.getReferredByUserUuid(), candidateUuid);
            return; // no bookkeeping event — a later Slack link picks it up on redelivery
        }

        // DM first, bookkeeping second, both inside the delivery transaction:
        // APPLICANT_REFERRER_NOTIFIED only commits when the DM went out. A
        // crash between send and commit re-delivers (the chassis' documented
        // at-least-once residual).
        slackService.sendMessage(named, dmText(candidate));

        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.APPLICANT_REFERRER_NOTIFIED)
                .candidate(candidateUuid)
                .actorSystem()
                .visibility(event.getVisibility()) // strictly-safer copy (P9 precedent)
                .payload("notified_user_uuid", candidate.getReferredByUserUuid())
                .payload("trigger_seq", event.getSeq()));
    }

    /** One notice per candidate, from the durable bookkeeping events. */
    private boolean alreadyNotified(String candidateUuid) {
        return RecruitmentEvent.count("candidateUuid = ?1 and eventType = ?2",
                candidateUuid, RecruitmentEventType.APPLICANT_REFERRER_NOTIFIED) > 0;
    }

    /**
     * True when this colleague has already had {@link #MAX_DMS_PER_RECIPIENT}
     * of these notices inside {@link #RECIPIENT_WINDOW}.
     * <p>
     * Counted through the candidate rows rather than the event payload:
     * {@code notified_user_uuid} lives in a JSON column, and a {@code LIKE}
     * over JSON is the kind of query that works until someone reformats the
     * payload. The subquery is exact and uses the columns the schema actually
     * indexes.
     */
    private boolean recentlyNotified(String userUuid) {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minus(RECIPIENT_WINDOW);
        return RecruitmentEvent.count(
                "eventType = ?1 and occurredAt >= ?2 and candidateUuid in "
                        + "(select c.uuid from RecruitmentCandidate c "
                        + "where c.referredByUserUuid = ?3)",
                RecruitmentEventType.APPLICANT_REFERRER_NOTIFIED, since, userUuid)
                >= MAX_DMS_PER_RECIPIENT;
    }

    /**
     * The DM body. Danish, because the recipient is a Trustworks employee
     * and the applicant answered a Danish question — and because the three
     * disclaimers have to be unmissable in the reader's own language.
     * <p>
     * Package-private so the exact wording is pinned by a fast-tier test:
     * this string is the whole GDPR posture of the feature.
     */
    String dmText(RecruitmentCandidate candidate) {
        String name = inlineSafe(SlackCandidateFacts.mrkdwnSafe(
                ((candidate.getFirstName() == null ? "" : candidate.getFirstName()) + " "
                        + (candidate.getLastName() == null ? "" : candidate.getLastName())).trim()));
        if (name.isEmpty()) {
            name = "En ansøger";
        }
        return ":wave: En jobansøger har nævnt dit navn.\n\n"
                + "*" + name + "* har søgt job hos Trustworks og har selv skrevet, "
                + "at vedkommende kender dig.\n\n"
                + "Det er ansøgerens egen oplysning. Vi har ikke bekræftet den, "
                + "og den er ikke en anbefaling fra dig — du har ikke anbefalet nogen. "
                + "Du behøver ikke gøre noget.\n\n"
                + "Har du input til rekrutteringsteamet, er du velkommen til at give dem besked. "
                + "Vil du ikke nævnes i den slags, så sig til, så fjerner vi oplysningen.\n\n"
                + "<" + baseUrl + PRIVACY_NOTICE_PATH + "|Sådan behandler vi oplysningen> "
                + "(afsnit 6 handler om dig).";
    }

    /**
     * Neutralise the mrkdwn a name can still carry after
     * {@link SlackCandidateFacts#mrkdwnSafe} (security review 2026-09-01, F2).
     * <p>
     * That helper escapes {@code & < >} — the Slack API escaping rules — which
     * is enough to stop {@code <https://evil.example|Klik her>} becoming a
     * link. It is NOT enough here. A newline survives it, and Slack auto-links
     * a bare {@code https://…} with no angle brackets at all, so an applicant
     * called "Ida\n\n:rotating_light: *Advarsel* https://evil.example" would
     * put attacker-written text and a live phishing link into a Trustworks-
     * branded DM. What makes that worth fixing HERE rather than only in the
     * shared helper is the recipient: everywhere else applicant text lands in
     * the recruitment channel, but on this path the applicant CHOOSES who
     * receives it. So the name is flattened to one line and the emphasis
     * characters are stripped before it is wrapped in {@code *…*}.
     * <p>
     * Deliberately not fixed inside {@code mrkdwnSafe} itself: it has 66 call
     * sites across 18 Slack surfaces, some of which pass deliberately
     * multi-line text, and collapsing newlines for all of them is a change
     * that belongs in its own review rather than riding along here. The
     * underlying weakness is pre-existing and still worth that pass.
     */
    static String inlineSafe(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\r\\n]+", " ")            // one line: no injected blocks
                .replaceAll("(?i)\\b(?:https?|ftp)://\\S*", "")  // Slack linkifies bare URLs
                .replaceAll("(?i)\\bwww\\.\\S*", "")            // …and bare www. hosts
                .replaceAll("[*_~`]", "")                       // no emphasis, no code spans
                .replaceAll("\\s{2,}", " ")
                .trim();
    }
}
