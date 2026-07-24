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
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentAiFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentInterviewService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlaService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.slack.SlackRecruitmentViews;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType.AI_BRIEF_GENERATED;

/**
 * P12 outbound Slack baseline (plan §P12): flat channel messages for the
 * five notification moments — new application, new referral, debrief
 * ready, signing completed, position opened. These flat messages remain
 * the permanent degradation path when the P22 living cards are off
 * (Slack companion spec §3.3). P18 adds two interviewer/owner-directed
 * DM moments on top: the interview-kit DM on the interview lifecycle
 * events (scheduled/moved/cancelled, with the scorecard button when
 * {@code recruitment.slack.scorecard.enabled} is on) and the
 * debrief-ready DM to the decision owner (deep link, never decision
 * buttons — the locked intake-yes/judgment-no boundary).
 * <p>
 * Rules enforced here:
 * <ul>
 *   <li><b>Flag:</b> {@code recruitment.pipeline.enabled} is checked per
 *       event — off ⇒ silent PROCESSED advance, no backfill on later
 *       enable (event consumption is always on; side effects are gated —
 *       Slack spec §3.1).</li>
 *   <li><b>PII boundary:</b> builders consume {@link SlackCandidateFacts}
 *       plus structural payload facts only. The AI brief is the single
 *       free-text exception, appended under its own toggle and truncated
 *       to the Block Kit clamp — never the structural fields.</li>
 *   <li><b>Partner-track suppression:</b> {@code visibility=CIRCLE}
 *       events never post to shared channels; they degrade to DMs to the
 *       current circle members (Slack spec §5.2, applied from day one;
 *       P22 upgrades this to private channels).</li>
 *   <li><b>Routing:</b> {@link RecruitmentSlackChannelRouter} — settings
 *       change takes effect without redeploy; nothing configured ⇒ no
 *       post.</li>
 * </ul>
 * Offset seeding to the stream head at deploy comes free from the P1
 * startup guard — no historical replay.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentSlackReactor extends RecruitmentReactor {

    public static final String NAME = "slack-notifications";

    /** Slack rejects text objects above 3 000 chars ({@code invalid_blocks}) — the module clamp. */
    static final int MESSAGE_CLAMP = 3000;

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentAiFeatureFlag aiFlags;

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    RecruitmentSlackChannelRouter router;

    @Inject
    SlackService slackService;

    @Inject
    RecruitmentSlaService slaService;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    @Override
    public String name() {
        return NAME;
    }

    /**
     * One live try + two catch-up retries, then durable SKIPPED.
     * ponytail: notifications are best-effort — never block the watermark
     * (and every other pending notification) on persistent Slack trouble;
     * upgrade to a circuit breaker if outages ever become routine.
     */
    @Override
    protected int maxDeliveryAttempts() {
        return 3;
    }

    @Override
    protected void handle(RecruitmentEvent event) throws Exception {
        switch (event.getEventType()) {
            case APPLICATION_CREATED, REFERRAL_SUBMITTED, SCORECARD_SUBMITTED,
                 SIGNING_COMPLETED, POSITION_OPENED,
                 INTERVIEW_SCHEDULED, INTERVIEW_RESCHEDULED, INTERVIEW_CANCELLED -> {
            }
            default -> {
                return; // not ours — silent advance
            }
        }
        if (!featureFlag.isPipelineEnabled()) {
            return; // side effects gated; offset advances, no backfill on later enable
        }
        switch (event.getEventType()) {
            // P18: the interview-kit DM — interviewer-directed, never a
            // channel post, so it bypasses the channel/circle delivery below.
            case INTERVIEW_SCHEDULED, INTERVIEW_RESCHEDULED, INTERVIEW_CANCELLED -> {
                interviewKitDms(event);
                return;
            }
            default -> {
            }
        }
        Notification notification = switch (event.getEventType()) {
            case APPLICATION_CREATED -> newApplication(event);
            case REFERRAL_SUBMITTED -> newReferral(event);
            case SCORECARD_SUBMITTED -> debriefReady(event);
            case SIGNING_COMPLETED -> signingCompleted(event);
            case POSITION_OPENED -> positionOpened(event);
            default -> null;
        };
        if (notification != null) {
            // P22: with living cards on, the card reactor owns the channel
            // surface for the two application-thread moments this reactor
            // used to post flat (new application → root card; debrief ready
            // → thread reply) — including the CIRCLE DM fallback, which the
            // card reactor performs itself when no private channel exists.
            // Everything else (referral/position/signing pings, all DMs)
            // stays here; with cards off these flat posts remain the
            // permanent degradation path (Slack spec §3.3).
            boolean cardOwnsChannelSurface = slackFlags.isCardsEnabled()
                    && (event.getEventType() == RecruitmentEventType.APPLICATION_CREATED
                            || event.getEventType() == RecruitmentEventType.SCORECARD_SUBMITTED);
            if (!cardOwnsChannelSurface) {
                deliver(event, notification);
            }
            // P18: when the last scorecard lands, the debrief-ready
            // notification also goes to the decision owner personally
            // (spec §5.6 — deep link, no decision buttons). CIRCLE events
            // skip this: their circle-member DMs above already reach the
            // partner-track owners.
            if (event.getEventType() == RecruitmentEventType.SCORECARD_SUBMITTED
                    && event.getVisibility() != RecruitmentEventVisibility.CIRCLE) {
                dmDecisionOwners(event, clamp(notification.message()));
            }
        }
    }

    /**
     * A built message plus the practice that routes it (nullable).
     * {@code blocks} (nullable) upgrades the channel post to Block Kit —
     * P14 uses it for the triage buttons on the new-referral ping;
     * {@code message} stays the fallback/notification text either way.
     */
    private record Notification(String practiceUuid, String message,
                                List<com.slack.api.model.block.LayoutBlock> blocks) {
        Notification(String practiceUuid, String message) {
            this(practiceUuid, message, null);
        }
    }

    // ------------------------------------------------------------------
    // Builders — SlackCandidateFacts + structural payload facts only
    // ------------------------------------------------------------------

    private Notification newApplication(RecruitmentEvent event) {
        RecruitmentCandidate candidate = findCandidate(event.getCandidateUuid());
        RecruitmentPosition position = findPosition(event.getPositionUuid());
        RecruitmentApplication application = event.getApplicationUuid() == null ? null
                : RecruitmentApplication.findById(event.getApplicationUuid());
        if (candidate == null || position == null) {
            log.warnf("Slack reactor: APPLICATION_CREATED seq %d without loadable subjects — skipping",
                    event.getSeq());
            return null;
        }
        SlackCandidateFacts facts = SlackCandidateFacts.of(candidate, position, application);
        StringBuilder sb = new StringBuilder(256)
                .append(":inbox_tray: *New application* — ").append(facts.displayName())
                .append(" applied for *").append(facts.positionTitle()).append('*');
        if (facts.sourceCode() != null) {
            sb.append(" (source: ").append(humanize(facts.sourceCode())).append(')');
        }
        sb.append('\n').append(profileUrl(facts.candidateUuid()));
        return new Notification(facts.practiceUuid(),
                withBriefWhenEnabled(sb.toString(), facts.candidateUuid()));
    }

    private Notification newReferral(RecruitmentEvent event) {
        Object referralUuid = payload(event).get("referral_uuid");
        RecruitmentReferral referral = referralUuid == null ? null
                : RecruitmentReferral.findById(referralUuid.toString());
        if (referral == null) {
            log.warnf("Slack reactor: REFERRAL_SUBMITTED seq %d without loadable referral — skipping",
                    event.getSeq());
            return null;
        }
        // Name comes from the referral's name COLUMN — the why-text and
        // other free text stay behind (facts contract).
        SlackCandidateFacts facts = SlackCandidateFacts.ofReferralName(referral.getCandidateName());
        String referrer = resolveUserName(referral.getReferrerUuid());
        StringBuilder sb = new StringBuilder(256)
                .append(":raised_hands: *New referral* — ").append(facts.displayName());
        if (referral.getReferrerRelation() != null) {
            sb.append(" (relation: ").append(humanize(referral.getReferrerRelation().name())).append(')');
        }
        sb.append(" referred by ").append(referrer)
                .append(". Ready for triage.\n").append(baseUrl).append("/recruitment/refer");
        // P14: with triage actions enabled the ping carries its action
        // buttons (handled by the inbound pipeline behind the master gate);
        // off ⇒ the P12 flat text — the permanent degradation path.
        // Referrals have no practice yet → default channel either way.
        if (!slackFlags.isTriageActionsEnabled()) {
            return new Notification(null, sb.toString());
        }
        return new Notification(null, sb.toString(), triagePingBlocks(sb.toString(), referral));
    }

    /** The triage ping as Block Kit: the text section + the three actions. */
    private List<com.slack.api.model.block.LayoutBlock> triagePingBlocks(
            String message, RecruitmentReferral referral) {
        return com.slack.api.model.block.Blocks.asBlocks(
                com.slack.api.model.block.Blocks.section(s -> s.text(
                        com.slack.api.model.block.composition.BlockCompositions.markdownText(message))),
                com.slack.api.model.block.Blocks.actions(a -> a.elements(
                        com.slack.api.model.block.element.BlockElements.asElements(
                                com.slack.api.model.block.element.BlockElements.button(b -> b
                                        .actionId("recruitment_triage_create")
                                        .value(referral.getUuid())
                                        .style("primary")
                                        .text(com.slack.api.model.block.composition.BlockCompositions
                                                .plainText("Create candidate"))),
                                com.slack.api.model.block.element.BlockElements.button(b -> b
                                        .actionId("recruitment_triage_view")
                                        .url(baseUrl + "/recruitment/refer")
                                        .text(com.slack.api.model.block.composition.BlockCompositions
                                                .plainText("View in intranet"))),
                                com.slack.api.model.block.element.BlockElements.button(b -> b
                                        .actionId("recruitment_triage_dismiss")
                                        .value(referral.getUuid())
                                        .style("danger")
                                        .text(com.slack.api.model.block.composition.BlockCompositions
                                                .plainText("Dismiss")))))));
    }

    private Notification debriefReady(RecruitmentEvent event) {
        Object interviewUuid = payload(event).get("interview_uuid");
        RecruitmentInterview interview = interviewUuid == null ? null
                : RecruitmentInterview.findById(interviewUuid.toString());
        if (interview == null) {
            return null;
        }
        List<RecruitmentScorecard> cards = RecruitmentScorecard.list(
                "interviewUuid = ?1", interview.getUuid());
        // The P11 rule, shared code: debrief-ready = every assigned
        // interviewer of the round has submitted. Fires on the completing
        // scorecard only — earlier submissions fall through silently.
        if (!RecruitmentInterviewService.allAssignedSubmitted(interview, cards)) {
            return null;
        }
        RecruitmentCandidate candidate = findCandidate(event.getCandidateUuid());
        RecruitmentPosition position = findPosition(event.getPositionUuid());
        SlackCandidateFacts facts = SlackCandidateFacts.of(candidate, position, null);
        StringBuilder sb = new StringBuilder(256)
                .append(":clipboard: *Debrief ready* — all ").append(cards.size())
                .append(" scorecards are in for ").append(facts.displayName());
        if (facts.positionTitle() != null) {
            sb.append(" (*").append(facts.positionTitle()).append('*');
            if (interview.getRound() != null) {
                sb.append(", round ").append(interview.getRound());
            }
            sb.append(')');
        }
        // Deliberately no scores or recommendations (P11 carry-over) —
        // the debrief happens in the intranet.
        sb.append(".\n").append(profileUrl(facts.candidateUuid()));
        return new Notification(facts.practiceUuid(), sb.toString());
    }

    private Notification signingCompleted(RecruitmentEvent event) {
        RecruitmentCandidate candidate = findCandidate(event.getCandidateUuid());
        if (candidate == null) {
            return null;
        }
        RecruitmentPosition position = findPosition(event.getPositionUuid());
        SlackCandidateFacts facts = SlackCandidateFacts.of(candidate, position, null);
        StringBuilder sb = new StringBuilder(256)
                .append(":lower_left_fountain_pen: *Contract signed* — ").append(facts.displayName())
                .append(" has completed signing");
        if (facts.positionTitle() != null) {
            sb.append(" for *").append(facts.positionTitle()).append('*');
        }
        sb.append(".\n").append(profileUrl(facts.candidateUuid()));
        return new Notification(facts.practiceUuid(), sb.toString());
    }

    private Notification positionOpened(RecruitmentEvent event) {
        RecruitmentPosition position = findPosition(event.getPositionUuid());
        if (position == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(256)
                .append(":briefcase: *Position opened* — *")
                .append(SlackCandidateFacts.mrkdwnSafe(position.getTitle())).append('*');
        List<String> qualifiers = new ArrayList<>(2);
        if (position.getHiringTrack() != null) {
            qualifiers.add(humanize(position.getHiringTrack().name()) + " track");
        }
        if (position.getPracticeName() != null) {
            qualifiers.add(SlackCandidateFacts.mrkdwnSafe(position.getPracticeName()));
        }
        if (!qualifiers.isEmpty()) {
            sb.append(" (").append(String.join(", ", qualifiers)).append(')');
        }
        sb.append(" is now open.\n").append(baseUrl).append("/recruitment/positions");
        return new Notification(position.getPracticeUuid(), sb.toString());
    }

    // ------------------------------------------------------------------
    // Delivery — channel for NORMAL, circle-member DMs for CIRCLE
    // ------------------------------------------------------------------

    private void deliver(RecruitmentEvent event, Notification notification) throws Exception {
        String message = clamp(notification.message());
        if (event.getVisibility() == RecruitmentEventVisibility.CIRCLE) {
            // Partner-track suppression: NEVER a shared channel. Until
            // P22's private channels exist, degrade to circle-member DMs.
            dmCircleMembers(event, message);
            return;
        }
        router.channelFor(notification.practiceUuid()).ifPresentOrElse(
                channel -> {
                    if (notification.blocks() != null) {
                        slackService.sendMessage(channel, message, notification.blocks());
                    } else {
                        slackService.sendMessage(channel, message);
                    }
                },
                () -> log.debugf("Slack reactor: no channel configured for event seq %d — skipping",
                        event.getSeq()));
    }

    private void dmCircleMembers(RecruitmentEvent event, String message) throws Exception {
        Set<String> positionUuids = new LinkedHashSet<>();
        if (event.getPositionUuid() != null) {
            positionUuids.add(event.getPositionUuid());
        } else if (event.getCandidateUuid() != null) {
            // Position-less CIRCLE events (e.g. the fail-closed
            // SIGNING_COMPLETED): every partner-track position the
            // candidate has an application on.
            RecruitmentApplication.<RecruitmentApplication>list("candidateUuid = ?1",
                            event.getCandidateUuid()).stream()
                    .map(RecruitmentApplication::getPositionUuid)
                    .filter(uuid -> {
                        RecruitmentPosition p = RecruitmentPosition.findById(uuid);
                        return p != null && p.getHiringTrack() == RecruitmentHiringTrack.PARTNER;
                    })
                    .forEach(positionUuids::add);
        }
        if (positionUuids.isEmpty()) {
            log.warnf("Slack reactor: CIRCLE event seq %d has no resolvable circle — dropping",
                    event.getSeq());
            return;
        }
        Set<String> memberUuids = new LinkedHashSet<>();
        RecruitmentCircleMember.<RecruitmentCircleMember>list("positionUuid in ?1",
                        List.copyOf(positionUuids))
                .forEach(m -> memberUuids.add(m.getUserUuid()));
        for (String memberUuid : memberUuids) {
            User member = User.findById(memberUuid);
            if (member == null || member.getSlackusername() == null
                    || member.getSlackusername().isBlank()) {
                log.infof("Slack reactor: circle member %s has no Slack link — skipping DM", memberUuid);
                continue;
            }
            // Throws on transport failure → delivery retries via catch-up
            // (a rare duplicate DM beats a silently lost confidential ping).
            slackService.sendMessage(member, message);
        }
    }

    // ------------------------------------------------------------------
    // P18 — interview-kit DMs + the debrief-ready owner DM
    // ------------------------------------------------------------------

    /** Wall-clock Europe/Copenhagen, as the scheduler entered it (P11 model). */
    private static final java.time.format.DateTimeFormatter KIT_TIME =
            java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy 'at' HH:mm",
                    java.util.Locale.ENGLISH);

    /**
     * The interview-kit DM (plan §P18 entry point): every assigned
     * interviewer gets a DM when their interview is scheduled, moved or
     * cancelled — candidate, position, round, time, place, the template's
     * focus areas and the kit deep link. With
     * {@code recruitment.slack.scorecard.enabled} on, scheduled/moved ROUND
     * DMs carry the "Fill in scorecard" button; off ⇒ deep-link-only (the
     * explicit degradation chain — nothing interactive is a prerequisite).
     * <p>
     * Interviewer-directed by construction: this never posts to a channel,
     * so partner-track (CIRCLE) interviews DM exactly the people the P11
     * interviewer grant already admits. Transport failures throw — the
     * chassis retries the delivery (≤ {@link #maxDeliveryAttempts()});
     * a rare duplicate DM beats a silently lost kit.
     */
    private void interviewKitDms(RecruitmentEvent event) throws Exception {
        Object interviewUuid = payload(event).get("interview_uuid");
        RecruitmentInterview interview = interviewUuid == null ? null
                : RecruitmentInterview.findById(interviewUuid.toString());
        if (interview == null) {
            log.warnf("Slack reactor: %s seq %d without loadable interview — skipping",
                    event.getEventType(), event.getSeq());
            return;
        }
        RecruitmentCandidate candidate = findCandidate(event.getCandidateUuid());
        RecruitmentPosition position = findPosition(event.getPositionUuid());
        SlackCandidateFacts facts = SlackCandidateFacts.of(candidate, position, null);
        String message = kitDmText(event.getEventType(), interview, facts, position);

        boolean actionable = event.getEventType() != RecruitmentEventType.INTERVIEW_CANCELLED
                && interview.getKind() == RecruitmentInterviewKind.ROUND
                && slackFlags.isScorecardEnabled();
        List<com.slack.api.model.block.LayoutBlock> blocks = actionable
                ? List.of(
                        com.slack.api.model.block.Blocks.section(s -> s.text(
                                com.slack.api.model.block.composition.BlockCompositions
                                        .markdownText(message))),
                        SlackRecruitmentViews.scorecardActions(interview.getUuid()))
                : null;

        for (String interviewerUuid : interview.getInterviewerUuids()) {
            User interviewer = User.findById(interviewerUuid);
            if (interviewer == null || interviewer.getSlackusername() == null
                    || interviewer.getSlackusername().isBlank()) {
                log.infof("Slack reactor: interviewer %s has no Slack link — skipping kit DM",
                        interviewerUuid);
                continue;
            }
            if (blocks != null) {
                slackService.sendMessage(interviewer, message, blocks);
            } else {
                slackService.sendMessage(interviewer, message);
            }
        }
    }

    /** Structural facts only: names/titles mrkdwn-escaped by the facts record. */
    private String kitDmText(RecruitmentEventType type, RecruitmentInterview interview,
                             SlackCandidateFacts facts, RecruitmentPosition position) {
        boolean informal = interview.getRound() == null;
        StringBuilder sb = new StringBuilder(256);
        switch (type) {
            case INTERVIEW_SCHEDULED -> sb.append(":calendar: *Interview scheduled* — you're ")
                    .append(informal ? "having an informal chat with " : "interviewing ")
                    .append(facts.displayName());
            case INTERVIEW_RESCHEDULED -> sb.append(":calendar: *Interview moved* — your ")
                    .append(informal ? "informal chat" : "interview").append(" with ")
                    .append(facts.displayName());
            default -> sb.append(":x: *Interview cancelled* — your ")
                    .append(informal ? "informal chat" : "interview").append(" with ")
                    .append(facts.displayName());
        }
        if (facts.positionTitle() != null) {
            sb.append(" for *").append(facts.positionTitle()).append('*');
        }
        if (interview.getRound() != null) {
            sb.append(" (round ").append(interview.getRound()).append(')');
        }
        if (type == RecruitmentEventType.INTERVIEW_CANCELLED) {
            sb.append(" is cancelled — nothing more to do.");
            return sb.toString();
        }
        if (interview.getScheduledAt() != null) {
            sb.append(type == RecruitmentEventType.INTERVIEW_RESCHEDULED
                            ? " has moved to " : " on ")
                    .append(interview.getScheduledAt().format(KIT_TIME));
        }
        if (interview.getLocation() != null && !interview.getLocation().isBlank()) {
            sb.append(" (").append(SlackCandidateFacts.mrkdwnSafe(interview.getLocation()))
                    .append(')');
        }
        sb.append('.');
        if (!informal && position != null && position.getScorecardTemplate() != null
                && !position.getScorecardTemplate().isEmpty()) {
            sb.append("\nFocus areas: ").append(position.getScorecardTemplate().stream()
                    .map(a -> SlackCandidateFacts.mrkdwnSafe(a.label()))
                    .reduce((a, b) -> a + ", " + b).orElse(""))
                    .append('.');
        }
        sb.append("\nYour kit (CV, focus areas, scorecard): ")
                .append(baseUrl).append("/recruitment/interviews");
        return sb.toString();
    }

    /**
     * The debrief-ready owner DM (P18, spec §5.6): the same structural
     * message as the channel ping, sent personally to the decision owner —
     * resolved through the P17 owner ladder (one rule, never
     * re-implemented). Deliberately best-effort: a missed DM is backstopped
     * by the P17 debrief-stalled nudge, and throwing here would re-post the
     * already-delivered channel ping on retry.
     */
    private void dmDecisionOwners(RecruitmentEvent event, String message) {
        RecruitmentPosition position = findPosition(event.getPositionUuid());
        for (String ownerUuid : slaService.resolveOwners(position)) {
            User owner = User.findById(ownerUuid);
            if (owner == null || owner.getSlackusername() == null
                    || owner.getSlackusername().isBlank()) {
                log.infof("Slack reactor: decision owner %s has no Slack link — skipping "
                        + "debrief-ready DM", ownerUuid);
                continue;
            }
            try {
                slackService.sendMessage(owner, message);
            } catch (Exception e) {
                log.warnf("Slack reactor: debrief-ready DM to owner %s failed — continuing "
                        + "(the SLA debrief-stalled nudge backstops it): %s",
                        ownerUuid, e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // AI brief enrichment (the single free-text exception, own toggle)
    // ------------------------------------------------------------------

    private String withBriefWhenEnabled(String structural, String candidateUuid) {
        if (candidateUuid == null || !aiFlags.isBriefEnabled()) {
            return structural;
        }
        RecruitmentEvent briefEvent = RecruitmentEvent.find(
                        "candidateUuid = ?1 and eventType = ?2 order by seq desc",
                        candidateUuid, AI_BRIEF_GENERATED)
                .firstResult();
        if (briefEvent == null || briefEvent.getPii() == null) {
            return structural;
        }
        Object bullets = parse(briefEvent.getPii()).get("bullets");
        if (!(bullets instanceof List<?> list) || list.isEmpty()) {
            return structural;
        }
        // Truncate the brief to fit the clamp — never the structural part.
        StringBuilder sb = new StringBuilder(structural).append("\n\n*AI-genereret resumé:*");
        for (Object bullet : list) {
            // Brief text is model output over applicant-controlled CV
            // content — escape it like any other untrusted string.
            String line = "\n• " + SlackCandidateFacts.mrkdwnSafe(String.valueOf(bullet));
            if (sb.length() + line.length() > MESSAGE_CLAMP - 2) {
                sb.append("\n…");
                break;
            }
            sb.append(line);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String profileUrl(String candidateUuid) {
        return baseUrl + "/recruitment/candidates/" + candidateUuid;
    }

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

    /** {@code LINKEDIN_AD} → {@code Linkedin ad} — shared code, P22 hoisted it to the facts record. */
    private static String humanize(String enumName) {
        return SlackCandidateFacts.humanizeCode(enumName);
    }

    /** Hard safety clamp — builders keep structural parts far below this. */
    private static String clamp(String message) {
        return message.length() <= MESSAGE_CLAMP ? message
                : message.substring(0, MESSAGE_CLAMP - 1) + "…";
    }

    private static RecruitmentCandidate findCandidate(String uuid) {
        return uuid == null ? null : RecruitmentCandidate.findById(uuid);
    }

    private static RecruitmentPosition findPosition(String uuid) {
        return uuid == null ? null : RecruitmentPosition.findById(uuid);
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

    private Map<String, Object> payload(RecruitmentEvent event) {
        return parse(event.getPayload());
    }
}
