package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.notifications.SlackCandidateFacts;
import dk.trustworks.intranet.recruitmentservice.slack.SlackRecruitmentViews;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The P23 morning interviewer brief (Slack spec §5.8): on days with
 * scheduled interviews, each interviewer gets ONE morning DM listing all
 * their interviews that day — candidate, position, round (or informal
 * chat), time and place, the template's focus areas, the kit deep link
 * and (when the P18 toggle is on) a "Fill in scorecard" button per round.
 * No interviews that day ⇒ no message.
 *
 * <h3>Idempotency — per (interviewer, interview, date)</h3>
 * The P17 event-derived idiom, no new tables: every briefed
 * (interviewer, interview) pair appends a {@code MORNING_BRIEF_SENT}
 * event carrying {@code brief_date}; a re-run (manual trigger, second
 * instance during ECS cutover) briefs only pairs with no event for
 * today — so a partial failure retries exactly the missed interviewers,
 * never re-DMing the delivered ones.
 *
 * <h3>Delivery discipline</h3>
 * One transaction per interviewer, DM sent <em>before</em> the
 * bookkeeping events (the P17 order): a Slack failure rolls the
 * bookkeeping back so the next run retries; a crash between send and
 * commit yields at worst one duplicate DM. A missing Slack link is a
 * visible INFO skip with no event. One failing interviewer never stops
 * the sweep.
 *
 * <h3>Gating &amp; timing</h3>
 * {@code recruitment.pipeline.enabled} (the P12/P18 reactor convention —
 * a DM moment must not arm while the module is dark) AND
 * {@code recruitment.slack.morning-brief.enabled}, both read fresh per
 * run. The batchlet fires at 06:00 UTC (07:00/08:00 Copenhagen — before
 * the workday and before the 07:00 UTC SLA sweep); "today" and all
 * interview times are wall-clock Europe/Copenhagen (the P11 model), so a
 * rescheduled interview is picked up by its state at run time — only
 * {@code SCHEDULED} interviews of the Copenhagen calendar day brief.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentMorningBriefService {

    /** Interview timestamps are wall-clock Europe/Copenhagen (the P11 model). */
    static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");

    private static final DateTimeFormatter DAY_HEADER =
            DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    SlackService slackService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    /** Result of one run, for logs and the batchlet exit status. */
    public record BriefSummary(boolean enabled, int briefsSent, int interviewsCovered,
                               int failures) {

        @Override
        public String toString() {
            if (!enabled) {
                return "morning-brief[disabled]";
            }
            return "morning-brief[briefs=%d, interviews=%d%s]"
                    .formatted(briefsSent, interviewsCovered,
                            failures > 0 ? ", failures=" + failures : "");
        }
    }

    /**
     * Run one brief pass for today (Europe/Copenhagen). Safe to call at
     * any time and from several instances concurrently — idempotency is
     * event-derived (class javadoc).
     */
    public BriefSummary run() {
        // Fresh transactions for the settings reads — the sweep must see
        // CURRENT flag values, never a session-cached row (the P17 lesson).
        if (!inTx(featureFlag::isPipelineEnabled)
                || !inTx(slackFlags::isMorningBriefEnabled)) {
            log.debug("recruitment-morning-brief skipped: flag off");
            return new BriefSummary(false, 0, 0, 0);
        }
        LocalDate today = LocalDate.now(COPENHAGEN);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);

        List<RecruitmentInterview> todays = inTx(() -> RecruitmentInterview.list(
                "status = ?1 and scheduledAt >= ?2 and scheduledAt < ?3",
                RecruitmentInterviewStatus.SCHEDULED, dayStart, dayEnd));
        if (todays.isEmpty()) {
            return new BriefSummary(true, 0, 0, 0);
        }
        Context ctx = inTx(() -> Context.load(todays));

        // Interviews on decided applications would brief someone about a
        // candidate who is already out — skip them.
        List<RecruitmentInterview> inPlay = todays.stream()
                .filter(i -> {
                    RecruitmentApplication application = ctx.applications.get(i.getApplicationUuid());
                    return application != null && application.getTerminal() == null
                            && application.getStage() != RecruitmentStage.HIRED;
                })
                .sorted(Comparator.comparing(RecruitmentInterview::getScheduledAt))
                .toList();
        if (inPlay.isEmpty()) {
            return new BriefSummary(true, 0, 0, 0);
        }

        // Already-briefed (interviewer, interview) pairs for today — the
        // idempotency lookup (event-derived, class javadoc).
        Map<String, Set<String>> briefed = inTx(() -> briefedPairs(inPlay, today));

        // Group the un-briefed pairs per interviewer, keeping time order.
        Map<String, List<RecruitmentInterview>> perInterviewer = new LinkedHashMap<>();
        for (RecruitmentInterview interview : inPlay) {
            for (String interviewerUuid : interview.getInterviewerUuids()) {
                if (briefed.getOrDefault(interview.getUuid(), Set.of()).contains(interviewerUuid)) {
                    continue;
                }
                perInterviewer.computeIfAbsent(interviewerUuid, k -> new ArrayList<>())
                        .add(interview);
            }
        }

        boolean scorecardButtons = inTx(slackFlags::isScorecardEnabled);
        int briefs = 0;
        int covered = 0;
        int failures = 0;
        for (Map.Entry<String, List<RecruitmentInterview>> entry : perInterviewer.entrySet()) {
            String interviewerUuid = entry.getKey();
            List<RecruitmentInterview> interviews = entry.getValue();
            User interviewer = inTx(() -> User.findById(interviewerUuid));
            if (interviewer == null || interviewer.getSlackusername() == null
                    || interviewer.getSlackusername().isBlank()) {
                log.infof("Morning brief: interviewer %s has no Slack link — skipping "
                        + "(no event, a later Slack link picks up naturally)", interviewerUuid);
                continue;
            }
            try {
                // DM before bookkeeping, one transaction per interviewer
                // (the P17 order — a Slack failure rolls the events back).
                QuarkusTransaction.requiringNew().run(() -> {
                    String fallback = briefText(interviews, ctx, today);
                    List<com.slack.api.model.block.LayoutBlock> blocks =
                            briefBlocks(interviews, ctx, today, scorecardButtons);
                    try {
                        slackService.sendMessage(interviewer, fallback, blocks);
                    } catch (Exception e) {
                        throw new IllegalStateException("Slack DM failed", e);
                    }
                    for (RecruitmentInterview interview : interviews) {
                        recordBriefed(interview, ctx, interviewerUuid, today);
                    }
                });
                briefs++;
                covered += interviews.size();
            } catch (Exception e) {
                failures++;
                log.warnf(e, "Morning brief: DM to interviewer %s failed — continuing "
                        + "(the next run retries)", interviewerUuid);
            }
        }
        return new BriefSummary(true, briefs, covered, failures);
    }

    // ------------------------------------------------------------------
    // Idempotency lookup (event-derived)
    // ------------------------------------------------------------------

    /** interview uuid → interviewer uuids already briefed today. */
    private Map<String, Set<String>> briefedPairs(List<RecruitmentInterview> interviews,
                                                  LocalDate today) {
        List<String> applicationUuids = interviews.stream()
                .map(RecruitmentInterview::getApplicationUuid)
                .distinct()
                .toList();
        List<RecruitmentEvent> events = RecruitmentEvent.list(
                "applicationUuid in ?1 and eventType = ?2",
                applicationUuids, RecruitmentEventType.MORNING_BRIEF_SENT);
        Map<String, Set<String>> briefed = new HashMap<>();
        for (RecruitmentEvent event : events) {
            Map<String, Object> payload = parse(event.getPayload());
            if (!today.toString().equals(payload.get("brief_date"))) {
                continue;
            }
            Object interviewUuid = payload.get("interview_uuid");
            if (interviewUuid == null) {
                continue;
            }
            Set<String> users = briefed.computeIfAbsent(
                    interviewUuid.toString(), k -> new HashSet<>());
            if (payload.get("nudged_user_uuids") instanceof List<?> list) {
                list.forEach(u -> users.add(String.valueOf(u)));
            }
        }
        return briefed;
    }

    private void recordBriefed(RecruitmentInterview interview, Context ctx,
                               String interviewerUuid, LocalDate today) {
        RecruitmentApplication application = ctx.applications.get(interview.getApplicationUuid());
        RecruitmentPosition position = application == null ? null
                : ctx.positions.get(application.getPositionUuid());
        RecruitmentEventBuilder builder = RecruitmentEventBuilder
                .event(RecruitmentEventType.MORNING_BRIEF_SENT)
                .actorScheduler()
                .visibility(visibilityFor(position))
                .payload("interview_uuid", interview.getUuid())
                .payload("brief_date", today.toString())
                .payload("scheduled_at", String.valueOf(interview.getScheduledAt()))
                .payload("nudged_user_uuids", List.of(interviewerUuid));
        if (interview.getRound() != null) {
            builder.payload("round", interview.getRound());
        }
        if (application != null) {
            builder.candidate(application.getCandidateUuid())
                    .application(application.getUuid())
                    .position(application.getPositionUuid());
        }
        eventRecorder.record(builder);
    }

    // ------------------------------------------------------------------
    // Message builders — structural facts + mrkdwn-escaped names only
    // ------------------------------------------------------------------

    /** The whole brief as plain text (Block Kit fallback / notification preview). */
    String briefText(List<RecruitmentInterview> interviews, Context ctx, LocalDate today) {
        StringBuilder sb = new StringBuilder(256)
                .append(":sunrise: *Your interviews today* — ")
                .append(today.format(DAY_HEADER))
                .append(interviews.size() == 1 ? " (1 interview)"
                        : " (" + interviews.size() + " interviews)");
        for (RecruitmentInterview interview : interviews) {
            sb.append('\n').append(interviewLine(interview, ctx));
        }
        sb.append("\nYour kit (CV, focus areas, scorecard): ")
                .append(baseUrl).append("/recruitment/interviews");
        return sb.toString();
    }

    /** The brief as Block Kit: header + one section (and optional button) per interview. */
    List<com.slack.api.model.block.LayoutBlock> briefBlocks(
            List<RecruitmentInterview> interviews, Context ctx, LocalDate today,
            boolean scorecardButtons) {
        List<com.slack.api.model.block.LayoutBlock> blocks = new ArrayList<>();
        blocks.add(com.slack.api.model.block.Blocks.section(s -> s.text(
                com.slack.api.model.block.composition.BlockCompositions.markdownText(
                        ":sunrise: *Your interviews today* — " + today.format(DAY_HEADER)))));
        for (RecruitmentInterview interview : interviews) {
            String line = interviewLine(interview, ctx);
            blocks.add(com.slack.api.model.block.Blocks.section(s -> s.text(
                    com.slack.api.model.block.composition.BlockCompositions.markdownText(line))));
            if (scorecardButtons && interview.getKind() == RecruitmentInterviewKind.ROUND) {
                blocks.add(SlackRecruitmentViews.scorecardActions(interview.getUuid()));
            }
        }
        blocks.add(com.slack.api.model.block.Blocks.context(
                com.slack.api.model.block.element.BlockElements.asContextElements(
                        com.slack.api.model.block.composition.BlockCompositions.markdownText(
                                "Your kit (CV, focus areas, scorecard): <" + baseUrl
                                        + "/recruitment/interviews|open the interviews page>"))));
        return blocks;
    }

    /** One interview as a single mrkdwn line — structural facts only. */
    private String interviewLine(RecruitmentInterview interview, Context ctx) {
        RecruitmentApplication application = ctx.applications.get(interview.getApplicationUuid());
        RecruitmentCandidate candidate = application == null ? null
                : ctx.candidates.get(application.getCandidateUuid());
        RecruitmentPosition position = application == null ? null
                : ctx.positions.get(application.getPositionUuid());
        boolean informal = interview.getKind() == RecruitmentInterviewKind.INFORMAL
                || interview.getRound() == null;
        StringBuilder sb = new StringBuilder(200)
                .append("• ").append(interview.getScheduledAt().format(TIME))
                .append(" — *").append(displayName(candidate)).append('*');
        if (position != null && position.getTitle() != null) {
            sb.append(" (*").append(SlackCandidateFacts.mrkdwnSafe(position.getTitle()))
                    .append('*');
            sb.append(informal ? ", informal chat" : ", round " + interview.getRound());
            sb.append(')');
        } else {
            sb.append(informal ? " (informal chat)" : " (round " + interview.getRound() + ")");
        }
        if (interview.getLocation() != null && !interview.getLocation().isBlank()) {
            sb.append(" · ").append(SlackCandidateFacts.mrkdwnSafe(interview.getLocation()));
        }
        if (!informal && position != null && position.getScorecardTemplate() != null
                && !position.getScorecardTemplate().isEmpty()) {
            sb.append("\n  Focus areas: ").append(position.getScorecardTemplate().stream()
                    .map(a -> SlackCandidateFacts.mrkdwnSafe(a.label()))
                    .collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }

    private static String displayName(RecruitmentCandidate candidate) {
        if (candidate == null) {
            return "a candidate";
        }
        String first = candidate.getFirstName() == null ? "" : candidate.getFirstName();
        String last = candidate.getLastName() == null ? "" : candidate.getLastName();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? "a candidate" : SlackCandidateFacts.mrkdwnSafe(name);
    }

    // ------------------------------------------------------------------
    // Batched context (the module's no-N+1 rule)
    // ------------------------------------------------------------------

    record Context(Map<String, RecruitmentApplication> applications,
                   Map<String, RecruitmentPosition> positions,
                   Map<String, RecruitmentCandidate> candidates) {

        static Context load(List<RecruitmentInterview> interviews) {
            List<String> applicationUuids = interviews.stream()
                    .map(RecruitmentInterview::getApplicationUuid)
                    .distinct()
                    .toList();
            List<RecruitmentApplication> applications = applicationUuids.isEmpty() ? List.of()
                    : RecruitmentApplication.list("uuid in ?1", applicationUuids);
            Map<String, RecruitmentApplication> byUuid = applications.stream()
                    .collect(Collectors.toMap(RecruitmentApplication::getUuid, a -> a));
            Set<String> positionUuids = applications.stream()
                    .map(RecruitmentApplication::getPositionUuid)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<String, RecruitmentPosition> positions = positionUuids.isEmpty() ? Map.of()
                    : RecruitmentPosition.<RecruitmentPosition>list("uuid in ?1",
                                    List.copyOf(positionUuids)).stream()
                            .collect(Collectors.toMap(RecruitmentPosition::getUuid, p -> p));
            Set<String> candidateUuids = applications.stream()
                    .map(RecruitmentApplication::getCandidateUuid)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<String, RecruitmentCandidate> candidates = candidateUuids.isEmpty() ? Map.of()
                    : RecruitmentCandidate.<RecruitmentCandidate>list("uuid in ?1",
                                    List.copyOf(candidateUuids)).stream()
                            .collect(Collectors.toMap(RecruitmentCandidate::getUuid, c -> c));
            return new Context(byUuid, positions, candidates);
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static RecruitmentEventVisibility visibilityFor(RecruitmentPosition position) {
        return position != null && position.getHiringTrack() == RecruitmentHiringTrack.PARTNER
                ? RecruitmentEventVisibility.CIRCLE
                : RecruitmentEventVisibility.NORMAL;
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

    /** Reads on batch threads need a transaction (lazily-bound EntityManager). */
    private <T> T inTx(java.util.function.Supplier<T> work) {
        return QuarkusTransaction.requiringNew().call(work::get);
    }
}
