package dk.trustworks.intranet.recruitmentservice.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactor;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCircleMember;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentInterviewService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * P22 living candidate cards (plan §P22, Slack companion spec §5.1): one
 * continuously updated Block Kit root card per application, with each
 * event posted as a short reply in the card's thread. The channel becomes
 * a skimmable mirror of the candidate timeline; the intranet remains the
 * system of record.
 * <p>
 * Rules enforced here:
 * <ul>
 *   <li><b>Flags:</b> {@code recruitment.pipeline.enabled} (the module's
 *       side-effect master, the P12/P18 precedent) AND
 *       {@code recruitment.slack.cards.enabled} — both checked per event;
 *       off ⇒ silent PROCESSED advance, no backfill on later enable. With
 *       cards off, {@link RecruitmentSlackReactor}'s flat messages remain
 *       the permanent degradation path (spec §3.3).</li>
 *   <li><b>No retroactive cards:</b> an application that predates the
 *       toggle gets its root card on its NEXT event (the card then already
 *       shows current state, so that first delivery posts no reply).</li>
 *   <li><b>One root card per application, ever:</b> the
 *       {@link RecruitmentSlackThread} projection row is the durable
 *       guard — persisted in its own committed transaction immediately
 *       after the Slack post, so a redelivered or replayed event finds the
 *       row and {@code chat.update}s instead of reposting.</li>
 *   <li><b>PII boundary:</b> builders consume {@link SlackCandidateFacts}
 *       plus structural columns/payload facts only — stage codes, enum
 *       reasons, counts, dates. Free text can never appear; deliberately
 *       NOT even the AI brief (its documented home is the P12 flat
 *       new-application ping, not the living card — spec §2.2 "not
 *       extended here").</li>
 *   <li><b>Blind rule:</b> scorecard replies carry submitter + count only —
 *       never scores or recommendations before the decision.</li>
 *   <li><b>Partner-track suppression:</b> cards for partner-track
 *       positions post ONLY to the position's private channel
 *       ({@link RecruitmentSlackChannel}, maintained by
 *       {@link SlackChannelReactor}); with no live channel they degrade to
 *       circle-member DMs — never a shared channel (spec §5.2).</li>
 *   <li><b>Anonymization redaction (P19 carry-over):</b>
 *       {@code CANDIDATE_ANONYMIZED} rewrites every root card of the
 *       candidate to "Anonymized candidate" — deliberately independent of
 *       BOTH flags (erasure duties outlive convenience features). Thread
 *       replies and DMs are not rewritten — the documented residual
 *       (spec §2.1).</li>
 * </ul>
 * Offset seeding to the stream head at deploy comes free from the P1
 * startup guard — no historical replay.
 */
@JBossLog
@ApplicationScoped
public class SlackCardReactor extends RecruitmentReactor {

    public static final String NAME = "slack-cards";

    /** The redacted display name (spec §2.1). */
    static final String ANONYMIZED_NAME = "Anonymized candidate";

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    /** Wall-clock Europe/Copenhagen as the scheduler entered it (the P11/P12 model). */
    private static final java.time.format.DateTimeFormatter REPLY_TIME =
            java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy 'at' HH:mm",
                    java.util.Locale.ENGLISH);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EntityManager entityManager;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

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
        if (event.getEventType() == RecruitmentEventType.CANDIDATE_ANONYMIZED) {
            redactRootCards(event); // flag-independent — see class javadoc
            return;
        }
        switch (event.getEventType()) {
            case APPLICATION_CREATED, APPLICATION_STAGE_CHANGED,
                 APPLICATION_REJECTED, APPLICATION_WITHDRAWN, CANDIDATE_HIRED,
                 INTERVIEW_SCHEDULED, INTERVIEW_RESCHEDULED, INTERVIEW_CANCELLED,
                 SCORECARD_SUBMITTED -> {
            }
            default -> {
                return; // not ours — silent advance
            }
        }
        if (!featureFlag.isPipelineEnabled() || !slackFlags.isCardsEnabled()) {
            return; // side effects gated; offset advances, no backfill on later enable
        }
        String applicationUuid = event.getApplicationUuid();
        if (applicationUuid == null) {
            return; // card scope is the application (e.g. a legacy CANDIDATE_HIRED without one)
        }
        RecruitmentApplication application = RecruitmentApplication.findById(applicationUuid);
        if (application == null) {
            log.warnf("Card reactor: %s seq %d without loadable application — skipping",
                    event.getEventType(), event.getSeq());
            return;
        }
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(application.getCandidateUuid());
        RecruitmentPosition position = RecruitmentPosition.findById(application.getPositionUuid());
        CardState state = CardState.of(candidate, position, application);

        RecruitmentSlackThread thread = RecruitmentSlackThread.findById(applicationUuid);
        if (thread == null) {
            String channel = resolveChannel(event, position);
            if (channel == null) {
                // Partner track with no live private channel: never a shared
                // channel — degrade to circle-member DMs (spec §5.2).
                if (isPartnerTrack(event, position)) {
                    dmCircleMembers(position, dmFallbackText(event, state));
                } else {
                    log.debugf("Card reactor: no channel configured for event seq %d — skipping",
                            event.getSeq());
                }
                return;
            }
            postRootCard(channel, applicationUuid, state, event.getSeq());
            // The fresh card already shows current state — no reply needed
            // for the event that just birthed it (mid-stream toggle rule).
            return;
        }

        // chat.update the living card first (idempotent — a retry of this
        // delivery re-runs it harmlessly), then the in-thread reply LAST
        // and best-effort, so a retry can never duplicate it.
        slackService.updateMessageStrict(thread.getChannelId(), thread.getRootTs(),
                state.fallbackText(), state.blocks(baseUrl));
        touch(thread);
        String reply = replyText(event, state);
        if (reply != null) {
            slackService.sendThreadReply(thread.getChannelId(), thread.getRootTs(), reply);
        }
    }

    // ------------------------------------------------------------------
    // Root card lifecycle
    // ------------------------------------------------------------------

    private void postRootCard(String channel, String applicationUuid, CardState state, long seq)
            throws Exception {
        // Throws on failure — nothing happened yet, the delivery retries.
        String ts = slackService.sendMessageReturningTs(channel, state.fallbackText(),
                state.blocks(baseUrl));
        // Claim the thread row in its OWN committed transaction so the card
        // can never be reposted, even if this delivery fails afterwards.
        int claimed = QuarkusTransaction.requiringNew().call(() ->
                entityManager.createNativeQuery(
                                "INSERT IGNORE INTO recruitment_slack_threads "
                                + "(application_uuid, channel_id, root_ts, updated_at) "
                                + "VALUES (:app, :channel, :ts, UTC_TIMESTAMP(3))")
                        .setParameter("app", applicationUuid)
                        .setParameter("channel", channel)
                        .setParameter("ts", ts)
                        .executeUpdate());
        if (claimed == 0) {
            // Two fresh events of the same application raced — the other
            // delivery's card won the claim; ours is an orphan message.
            // Rare (live dispatch of near-simultaneous events); visible,
            // not silent.
            log.warnf("Card reactor: lost root-card claim race for application %s "
                    + "(event seq %d) — an orphan card was posted to %s", applicationUuid, seq, channel);
        }
    }

    private void touch(RecruitmentSlackThread thread) {
        QuarkusTransaction.requiringNew().run(() ->
                entityManager.createNativeQuery(
                                "UPDATE recruitment_slack_threads SET updated_at = UTC_TIMESTAMP(3) "
                                + "WHERE application_uuid = :app")
                        .setParameter("app", thread.getApplicationUuid())
                        .executeUpdate());
    }

    /**
     * The P19 carry-over: rewrite every living root card of the anonymized
     * candidate. Strict updates — the catch-up retry (≤3 attempts) is the
     * resilience; a permanently deleted Slack message poison-skips after
     * that, which the ops runbook documents.
     */
    private void redactRootCards(RecruitmentEvent event) throws Exception {
        if (event.getCandidateUuid() == null) {
            return;
        }
        List<RecruitmentApplication> applications = RecruitmentApplication.list(
                "candidateUuid = ?1", event.getCandidateUuid());
        for (RecruitmentApplication application : applications) {
            RecruitmentSlackThread thread = RecruitmentSlackThread.findById(application.getUuid());
            if (thread == null) {
                continue;
            }
            RecruitmentCandidate candidate = RecruitmentCandidate.findById(application.getCandidateUuid());
            RecruitmentPosition position = RecruitmentPosition.findById(application.getPositionUuid());
            CardState state = CardState.of(candidate, position, application);
            slackService.updateMessageStrict(thread.getChannelId(), thread.getRootTs(),
                    state.fallbackText(), state.blocks(baseUrl));
            touch(thread);
        }
    }

    // ------------------------------------------------------------------
    // Channel resolution — partner track NEVER falls back to a shared channel
    // ------------------------------------------------------------------

    private boolean isPartnerTrack(RecruitmentEvent event, RecruitmentPosition position) {
        return event.getVisibility() == RecruitmentEventVisibility.CIRCLE
                || (position != null && position.getHiringTrack() == RecruitmentHiringTrack.PARTNER);
    }

    private String resolveChannel(RecruitmentEvent event, RecruitmentPosition position) {
        if (isPartnerTrack(event, position)) {
            if (position == null) {
                return null;
            }
            RecruitmentSlackChannel channel = RecruitmentSlackChannel.findById(position.getUuid());
            return channel == null || channel.getArchivedAt() != null ? null : channel.getChannelId();
        }
        return router.channelFor(position == null ? null : position.getPracticeUuid()).orElse(null);
    }

    private void dmCircleMembers(RecruitmentPosition position, String message) throws Exception {
        if (position == null) {
            return;
        }
        List<RecruitmentCircleMember> members = RecruitmentCircleMember.list(
                "positionUuid = ?1", position.getUuid());
        for (RecruitmentCircleMember member : members) {
            User user = User.findById(member.getUserUuid());
            if (user == null || user.getSlackusername() == null || user.getSlackusername().isBlank()) {
                log.infof("Card reactor: circle member %s has no Slack link — skipping DM",
                        member.getUserUuid());
                continue;
            }
            // Throws on transport failure → delivery retries (the P12
            // circle-DM posture: a rare duplicate beats a lost confidential ping).
            slackService.sendMessage(user, message);
        }
    }

    // ------------------------------------------------------------------
    // Card + reply builders — SlackCandidateFacts/structural columns only
    // ------------------------------------------------------------------

    /**
     * Everything the card renders, read from live rows at render time —
     * so every {@code chat.update} (including redaction and post-terminal
     * touches) reflects current DB state, never the triggering event's
     * possibly-stale payload.
     */
    record CardState(SlackCandidateFacts facts, boolean anonymized,
                     String stageCode, String terminalCode, String rejectionReason,
                     long daysInStage, long scorecardsIn) {

        static CardState of(RecruitmentCandidate candidate, RecruitmentPosition position,
                            RecruitmentApplication application) {
            SlackCandidateFacts facts = SlackCandidateFacts.of(candidate, position, application);
            boolean anonymized = candidate != null && candidate.getStatus() == CandidateStatus.ANONYMIZED;
            String terminal = application.getTerminal() == null ? null : application.getTerminal().name();
            String reason = application.getRejectionReasonCode() == null ? null
                    : application.getRejectionReasonCode().name();
            long days = application.getStageEnteredAt() == null ? 0
                    : Math.max(0, ChronoUnit.DAYS.between(application.getStageEnteredAt(),
                            LocalDateTime.now(ZoneOffset.UTC)));
            return new CardState(facts, anonymized,
                    application.getStage() == null ? null : application.getStage().name(),
                    terminal, reason, days, countScorecards(application.getUuid()));
        }

        private static long countScorecards(String applicationUuid) {
            List<RecruitmentInterview> interviews = RecruitmentInterview.list(
                    "applicationUuid = ?1", applicationUuid);
            if (interviews.isEmpty()) {
                return 0;
            }
            return RecruitmentScorecard.count("interviewUuid in ?1",
                    interviews.stream().map(RecruitmentInterview::getUuid).toList());
        }

        String displayName() {
            return anonymized ? ANONYMIZED_NAME : facts.displayName();
        }

        boolean isHired() {
            return "HIRED".equals(stageCode);
        }

        /** The single mrkdwn body of the living card. */
        String cardText() {
            StringBuilder sb = new StringBuilder(256)
                    .append(":bust_in_silhouette: *").append(displayName()).append('*');
            if (facts.positionTitle() != null) {
                sb.append(" — *").append(facts.positionTitle()).append('*');
            }
            sb.append('\n');
            if (isHired()) {
                sb.append("Outcome: *Hired* :tada:");
            } else if (terminalCode != null) {
                sb.append("Outcome: *").append(SlackCandidateFacts.humanizeCode(terminalCode)).append('*');
                if (rejectionReason != null) {
                    sb.append(" — ").append(SlackCandidateFacts.humanizeCode(rejectionReason));
                }
            } else {
                sb.append("Stage: *")
                        .append(stageCode == null ? "Unknown" : SlackCandidateFacts.humanizeCode(stageCode))
                        .append("* · ").append(daysInStage)
                        .append(daysInStage == 1 ? " day" : " days").append(" in stage");
            }
            if (facts.sourceCode() != null) {
                sb.append(" · Source: ").append(SlackCandidateFacts.humanizeCode(facts.sourceCode()));
            }
            sb.append(" · Scorecards in: ").append(scorecardsIn);
            return sb.toString();
        }

        /** Plain notification/preview text (blocks carry the real card). */
        String fallbackText() {
            String position = facts.positionTitle() == null ? "" : " — " + facts.positionTitle();
            String status = isHired() ? "Hired"
                    : terminalCode != null ? SlackCandidateFacts.humanizeCode(terminalCode)
                    : stageCode == null ? "Unknown" : SlackCandidateFacts.humanizeCode(stageCode);
            return displayName() + position + " (" + status + ")";
        }

        List<com.slack.api.model.block.LayoutBlock> blocks(String baseUrl) {
            var section = com.slack.api.model.block.Blocks.section(s -> s.text(
                    com.slack.api.model.block.composition.BlockCompositions.markdownText(cardText())));
            if (anonymized || facts.candidateUuid() == null) {
                // A redacted (or unloadable) candidate keeps no deep link.
                return com.slack.api.model.block.Blocks.asBlocks(section);
            }
            var actions = com.slack.api.model.block.Blocks.actions(a -> a.elements(
                    com.slack.api.model.block.element.BlockElements.asElements(
                            com.slack.api.model.block.element.BlockElements.button(b -> b
                                    .actionId("recruitment_card_view")
                                    .url(baseUrl + "/recruitment/candidates/" + facts.candidateUuid())
                                    .text(com.slack.api.model.block.composition.BlockCompositions
                                            .plainText("View profile"))))));
            return com.slack.api.model.block.Blocks.asBlocks(section, actions);
        }
    }

    /**
     * The short in-thread reply for one event — structural facts only.
     * Null when the event needs no reply (its card update says it all).
     */
    private String replyText(RecruitmentEvent event, CardState state) {
        Map<String, Object> payload = parse(event.getPayload());
        return switch (event.getEventType()) {
            case APPLICATION_CREATED -> null; // the root card is the announcement
            case APPLICATION_STAGE_CHANGED -> stageChangeReply(payload);
            case APPLICATION_REJECTED -> {
                String reason = str(payload, "reason_code");
                yield ":no_entry: *Rejected*" + (reason == null ? ""
                        : " — reason: " + SlackCandidateFacts.humanizeCode(reason));
            }
            case APPLICATION_WITHDRAWN -> ":wave: *Withdrawn* — the candidate backed out.";
            case CANDIDATE_HIRED -> ":tada: *Hired!*";
            case INTERVIEW_SCHEDULED, INTERVIEW_RESCHEDULED, INTERVIEW_CANCELLED ->
                    interviewReply(event, payload);
            case SCORECARD_SUBMITTED -> scorecardReply(event, payload, state);
            default -> null;
        };
    }

    private String stageChangeReply(Map<String, Object> payload) {
        String from = str(payload, "from");
        String to = str(payload, "to");
        boolean back = "BACK".equals(str(payload, "direction"));
        StringBuilder sb = new StringBuilder(back
                ? ":leftwards_arrow_with_hook: Moved back: "
                : ":arrow_right_hook: Stage: ");
        sb.append(from == null ? "?" : SlackCandidateFacts.humanizeCode(from))
                .append(" → ")
                .append(to == null ? "?" : SlackCandidateFacts.humanizeCode(to));
        return sb.toString();
    }

    private String interviewReply(RecruitmentEvent event, Map<String, Object> payload) {
        String interviewUuid = str(payload, "interview_uuid");
        RecruitmentInterview interview = interviewUuid == null ? null
                : RecruitmentInterview.findById(interviewUuid);
        String what = interview != null && interview.getRound() != null
                ? "Interview (round " + interview.getRound() + ")" : "Informal chat";
        StringBuilder sb = new StringBuilder(128);
        switch (event.getEventType()) {
            case INTERVIEW_SCHEDULED -> sb.append(":calendar: ").append(what).append(" scheduled");
            case INTERVIEW_RESCHEDULED -> sb.append(":calendar: ").append(what).append(" moved");
            default -> {
                sb.append(":x: ").append(what).append(" cancelled");
                return sb.toString();
            }
        }
        if (interview != null && interview.getScheduledAt() != null) {
            sb.append(" — ").append(interview.getScheduledAt().format(REPLY_TIME));
        }
        if (interview != null && interview.getLocation() != null && !interview.getLocation().isBlank()) {
            sb.append(" (").append(SlackCandidateFacts.mrkdwnSafe(interview.getLocation())).append(')');
        }
        return sb.toString();
    }

    /** Submitter + count only — NEVER scores before the decision (the blind rule). */
    private String scorecardReply(RecruitmentEvent event, Map<String, Object> payload,
                                  CardState state) {
        StringBuilder sb = new StringBuilder(128)
                .append(":memo: Scorecard submitted by ").append(resolveUserName(event.getActorUuid()))
                .append(" (").append(state.scorecardsIn())
                .append(" in)");
        String interviewUuid = str(payload, "interview_uuid");
        RecruitmentInterview interview = interviewUuid == null ? null
                : RecruitmentInterview.findById(interviewUuid);
        if (interview != null) {
            List<RecruitmentScorecard> cards = RecruitmentScorecard.list(
                    "interviewUuid = ?1", interview.getUuid());
            if (RecruitmentInterviewService.allAssignedSubmitted(interview, cards)) {
                sb.append("\n:clipboard: *Debrief ready* — all scorecards are in for this round. ")
                        .append("Decide in the intranet: ")
                        .append(baseUrl).append("/recruitment/candidates/")
                        .append(state.facts().candidateUuid() == null ? ""
                                : state.facts().candidateUuid());
            }
        }
        return sb.toString();
    }

    /** Fallback DM body for partner-track events with no live private channel. */
    private String dmFallbackText(RecruitmentEvent event, CardState state) {
        String reply = replyText(event, state);
        String header = ":lock: Confidential partner-track update — " + state.cardText();
        return reply == null ? header : header + "\n" + reply;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String resolveUserName(String userUuid) {
        if (userUuid == null) {
            return "unknown";
        }
        User user = User.findById(userUuid);
        if (user == null) {
            return "unknown";
        }
        String name = ((user.getFirstname() == null ? "" : user.getFirstname()) + " "
                + (user.getLastname() == null ? "" : user.getLastname())).trim();
        return name.isEmpty() ? "unknown" : SlackCandidateFacts.mrkdwnSafe(name);
    }

    private static String str(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }

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
}
