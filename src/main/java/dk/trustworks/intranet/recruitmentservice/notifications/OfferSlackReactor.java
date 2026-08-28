package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.model.Practice;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactor;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * The HR-facing offer-phase channel feed (2026-08-25): when
 * {@code recruitment.slack.channel.offer} is configured, the moments HR must
 * react to — a candidate entering the OFFER stage and the contract documents
 * completing signature — post there as short flat messages. Together with
 * {@link RecruitmentHrSlackNotifier} (contract sent, conversion, onboarding
 * uploads — direct calls, no stream events) this gives HR one channel that
 * tells the whole offer-to-employee story.
 * <p>
 * Rules enforced here:
 * <ul>
 *   <li><b>Master switch:</b> the offer channel being CONFIGURED is the
 *       switch for the whole offer split ({@link
 *       RecruitmentSlackChannelRouter#offerChannel()}). Blank ⇒ this reactor
 *       is inert and every pre-existing routing (practice/default channels,
 *       card-thread replies) behaves as before — the "move" is all-or-nothing,
 *       so no moment is ever lost in between.</li>
 *   <li><b>Move, not copy:</b> with the channel configured,
 *       {@link SlackCardReactor} stops replying the same moments into the
 *       living card's practice-channel thread (the card itself keeps
 *       updating — it is a state mirror, not a notification).</li>
 *   <li><b>Flag:</b> {@code recruitment.pipeline.enabled} is the module's
 *       side-effect master, checked per event — off ⇒ silent advance, no
 *       backfill on later enable (the P12 posture).</li>
 *   <li><b>Partner-track suppression:</b> {@code visibility=CIRCLE} events
 *       never reach the shared offer channel — confidential partner-track
 *       recruitment stays with its circle surfaces (spec §5.2). No DM
 *       degradation here: the circle already gets these moments through its
 *       own channel/DMs.</li>
 *   <li><b>PII boundary:</b> messages carry {@link SlackCandidateFacts}
 *       fields plus structural payload facts only — names, position titles,
 *       stage codes, the profile link. Never salary, CPR, case keys or
 *       signer emails.</li>
 *   <li><b>Failure posture:</b> the post is STRICT — a transport failure or
 *       not-ok Slack response throws, so the delivery retries (≤3) and then
 *       dead-letters durably (V490), replayable once the channel is fixed.
 *       These are "HR must react" moments; losing one silently is the
 *       failure mode this reactor had on 2026-08-27.</li>
 * </ul>
 * Offset seeding to the stream head at deploy comes free from the P1
 * startup guard — no historical replay.
 */
@JBossLog
@ApplicationScoped
public class OfferSlackReactor extends RecruitmentReactor {

    public static final String NAME = "slack-offer";

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentSlackChannelRouter router;

    @Inject
    SlackService slackService;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    @Override
    public String name() {
        return NAME;
    }

    /** The P12 posture: one live try + two catch-up retries, then durable SKIPPED. */
    @Override
    protected int maxDeliveryAttempts() {
        return 3;
    }

    @Override
    protected void handle(RecruitmentEvent event) throws Exception {
        switch (event.getEventType()) {
            case OFFER_OPENED, SIGNING_COMPLETED -> {
            }
            default -> {
                return; // not ours — silent advance
            }
        }
        if (!featureFlag.isPipelineEnabled()) {
            return; // side effects gated; offset advances, no backfill on later enable
        }
        Optional<String> channel = router.offerChannel();
        if (channel.isEmpty()) {
            return; // offer split off — the moment stays wherever it flowed before
        }
        if (event.getVisibility() == RecruitmentEventVisibility.CIRCLE) {
            log.debugf("Offer reactor: CIRCLE event seq %d suppressed from the shared offer channel",
                    event.getSeq());
            return;
        }
        String message = switch (event.getEventType()) {
            case OFFER_OPENED -> offerEntered(event);
            case SIGNING_COMPLETED -> signingCompleted(event);
            default -> null;
        };
        if (message != null) {
            // Strict: a failed post must THROW so this delivery retries and
            // then dead-letters (V490). The swallow variant committed a
            // channel_not_found as success — 2026-08-27, the offer channel
            // was private without the mother bot as member, and two
            // "documents signed" messages vanished with one ERROR line that
            // named neither reactor, seq, nor candidate.
            slackService.sendMessageStrict(channel.get(), message);
        }
    }

    // ------------------------------------------------------------------
    // Builders — SlackCandidateFacts + structural payload facts only
    // ------------------------------------------------------------------

    /**
     * ":dart: {name} entered Offer — {position} ({practice})". Fires on
     * every OFFER entry ({@code OFFER_OPENED} is appended per entry,
     * re-entries included) — HR wants to see each one, exactly as the
     * timeline records each one.
     */
    private String offerEntered(RecruitmentEvent event) {
        RecruitmentCandidate candidate = event.getCandidateUuid() == null ? null
                : RecruitmentCandidate.findById(event.getCandidateUuid());
        RecruitmentPosition position = event.getPositionUuid() == null ? null
                : RecruitmentPosition.findById(event.getPositionUuid());
        RecruitmentApplication application = event.getApplicationUuid() == null ? null
                : RecruitmentApplication.findById(event.getApplicationUuid());
        if (candidate == null) {
            log.warnf("Offer reactor: OFFER_OPENED seq %d without loadable candidate — skipping",
                    event.getSeq());
            return null;
        }
        SlackCandidateFacts facts = SlackCandidateFacts.of(candidate, position, application);
        StringBuilder sb = new StringBuilder(256)
                .append(":dart: *Offer stage* — ").append(facts.displayName());
        if (facts.positionTitle() != null) {
            sb.append(" on *").append(facts.positionTitle()).append('*');
        }
        String practice = practiceName(facts.practiceUuid());
        if (practice != null) {
            sb.append(" (").append(SlackCandidateFacts.mrkdwnSafe(practice)).append(')');
        }
        sb.append('\n')
                .append("Next: assign the team if missing, prepare and send the contract — "
                        + "Offer & Contract tab on the profile.\n")
                .append(profileUrl(facts.candidateUuid()));
        return sb.toString();
    }

    /**
     * ":writing_hand: {name} signed — ready to hire". The payload's
     * {@code case_key}/{@code dossier_uuid} stay out of the message (PII
     * boundary keeps NextSign identifiers off Slack); the profile link is
     * where HR acts (Convert lives on the Offer & Contract tab).
     */
    private String signingCompleted(RecruitmentEvent event) {
        RecruitmentCandidate candidate = event.getCandidateUuid() == null ? null
                : RecruitmentCandidate.findById(event.getCandidateUuid());
        if (candidate == null) {
            log.warnf("Offer reactor: SIGNING_COMPLETED seq %d without loadable candidate — skipping",
                    event.getSeq());
            return null;
        }
        RecruitmentPosition position = event.getPositionUuid() == null ? null
                : RecruitmentPosition.findById(event.getPositionUuid());
        SlackCandidateFacts facts = SlackCandidateFacts.of(candidate, position, null);
        StringBuilder sb = new StringBuilder(256)
                .append(":writing_hand: *Documents signed* — ").append(facts.displayName())
                .append(" has signed all contract documents");
        if (facts.positionTitle() != null) {
            sb.append(" (*").append(facts.positionTitle()).append("*)");
        }
        sb.append(" — ready to hire.\n")
                .append("Next: convert to employee from the Offer & Contract tab.\n")
                .append(profileUrl(facts.candidateUuid()));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String profileUrl(String candidateUuid) {
        return baseUrl + "/recruitment/candidates/" + candidateUuid;
    }

    private static String practiceName(String practiceUuid) {
        if (practiceUuid == null) {
            return null;
        }
        Practice practice = Practice.findById(practiceUuid);
        return practice == null ? null : practice.getName();
    }
}
