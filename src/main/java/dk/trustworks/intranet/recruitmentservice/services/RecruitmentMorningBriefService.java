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

import java.time.DayOfWeek;
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
 * The interviewer briefs (Slack spec §5.8): the preparation pack an assigned
 * interviewer gets before they walk into the room — candidate, position,
 * round (or informal chat), time and place, the template's focus areas, the
 * kit deep link and (when the P18 toggle is on) a "Fill in scorecard" button
 * per round. No interviews in the window ⇒ no message.
 *
 * <h3>Two briefs, two jobs</h3>
 * <ul>
 *   <li>{@link BriefKind#EVE} — the prep pack, sent the working day before
 *       (V531). This is the one that can actually change what you do: there
 *       is still a working day left to read the CV, skim the answers and
 *       move something. The 06:00 brief could not do that job — for a 09:00
 *       interview it lands three hours before the room.</li>
 *   <li>{@link BriefKind#MORNING} — the day-of line, unchanged in timing and
 *       deliberately kept. It degrades to a short "today's schedule" line for
 *       any pair the eve brief already covered, because repeating the whole
 *       pack twelve hours later is how a useful reminder becomes noise. For a
 *       pair the eve brief missed — an interview booked this morning, an
 *       interviewer added overnight — it stays the FULL pack, so a
 *       short-notice booking never loses its prep.</li>
 * </ul>
 *
 * <h3>"The working day before"</h3>
 * {@code recruitment.brief.lead-days} days back from the interview, then
 * backwards off Saturday and Sunday. So a Monday interview briefs on Friday,
 * and Friday's run covers Saturday, Sunday and Monday in one DM. Briefing on
 * a Sunday would land when nobody is looking — the exact failure this change
 * exists to fix.
 *
 * <h3>Idempotency — per (interviewer, interview, date)</h3>
 * The P17 event-derived idiom, no new tables: every briefed
 * (interviewer, interview) pair appends a {@code MORNING_BRIEF_SENT} or
 * {@code EVE_BRIEF_SENT} event carrying {@code brief_date} (the date the
 * brief COVERS, not the date it was sent); a re-run (manual trigger, second
 * instance during ECS cutover) briefs only pairs with no event for that date
 * and kind — so a partial failure retries exactly the missed interviewers,
 * never re-DMing the delivered ones. The two kinds key independently on
 * purpose: an eve-briefed pair must still get its day-of line.
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
 * a DM moment must not arm while the module is dark) AND the kind's own
 * flag ({@code recruitment.slack.morning-brief.enabled} /
 * {@code recruitment.slack.eve-brief.enabled}), both read fresh per run. The
 * morning batchlet fires at 06:00 UTC, the eve batchlet at 13:00 UTC
 * (15:00/14:00 Copenhagen — mid-afternoon, while there is still a working
 * day left to act on it). "Today" and all interview times are wall-clock
 * Europe/Copenhagen (the P11 model), so a rescheduled interview is picked up
 * by its state at run time — only {@code SCHEDULED} interviews of the
 * Copenhagen calendar dates in the window brief.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentMorningBriefService {

    /**
     * Interview timestamps are wall-clock Europe/Copenhagen (the P11 model).
     * Aliased to the single definition on {@link RecruitmentIdleFacts} so the
     * briefs and the sweeps can never drift onto different clocks.
     */
    static final ZoneId COPENHAGEN = RecruitmentIdleFacts.COPENHAGEN;

    private static final DateTimeFormatter DAY_HEADER =
            DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    /** The eve brief can span a weekend, so its lines name the day too. */
    private static final DateTimeFormatter DAY_AND_TIME =
            DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH);

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
    RecruitmentSlaThresholds thresholds;

    @Inject
    SlackService slackService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    /**
     * Which of the two briefs a run is producing. The kind decides the flag
     * that gates it, the event type that keys its idempotency, and the
     * heading — not the facts, which are identical by design so an
     * interviewer never has to reconcile two versions of the same interview.
     */
    public enum BriefKind {

        /** The prep pack, sent the working day before. */
        EVE(RecruitmentEventType.EVE_BRIEF_SENT),

        /** The day-of line, sent at 06:00 on the day itself. */
        MORNING(RecruitmentEventType.MORNING_BRIEF_SENT);

        private final RecruitmentEventType eventType;

        BriefKind(RecruitmentEventType eventType) {
            this.eventType = eventType;
        }

        RecruitmentEventType eventType() {
            return eventType;
        }
    }

    /** Result of one run, for logs and the batchlet exit status. */
    public record BriefSummary(boolean enabled, int briefsSent, int interviewsCovered,
                               int failures) {

        @Override
        public String toString() {
            if (!enabled) {
                return "brief[disabled]";
            }
            return "brief[briefs=%d, interviews=%d%s]"
                    .formatted(briefsSent, interviewsCovered,
                            failures > 0 ? ", failures=" + failures : "");
        }
    }

    /**
     * Run one MORNING brief pass for today (Europe/Copenhagen). Safe to call
     * at any time and from several instances concurrently — idempotency is
     * event-derived (class javadoc).
     */
    public BriefSummary run() {
        return runBrief(BriefKind.MORNING);
    }

    /**
     * Run one EVE brief pass: every interview date whose working-day-before
     * is today. Safe to call at any time and from several instances
     * concurrently — idempotency is event-derived (class javadoc).
     */
    public BriefSummary runEve() {
        return runBrief(BriefKind.EVE);
    }

    BriefSummary runBrief(BriefKind kind) {
        // Fresh transactions for the settings reads — the sweep must see
        // CURRENT flag values, never a session-cached row (the P17 lesson).
        boolean kindEnabled = kind == BriefKind.EVE
                ? inTx(slackFlags::isEveBriefEnabled)
                : inTx(slackFlags::isMorningBriefEnabled);
        if (!inTx(featureFlag::isPipelineEnabled) || !kindEnabled) {
            log.debugf("recruitment-%s-brief skipped: flag off", kind.name().toLowerCase(Locale.ROOT));
            return new BriefSummary(false, 0, 0, 0);
        }
        LocalDate today = LocalDate.now(COPENHAGEN);
        List<LocalDate> targetDates = kind == BriefKind.MORNING
                ? List.of(today)
                : eveTargetDates(today, inTx(thresholds::briefLeadDays));
        if (targetDates.isEmpty()) {
            // An eve run on a Saturday or Sunday: no interview date has today
            // as its working-day-before, so there is nothing to send.
            return new BriefSummary(true, 0, 0, 0);
        }
        LocalDateTime windowStart = targetDates.get(0).atStartOfDay();
        LocalDateTime windowEnd = targetDates.get(targetDates.size() - 1).plusDays(1).atStartOfDay();

        List<RecruitmentInterview> inWindow = inTx(() -> RecruitmentInterview.list(
                "status = ?1 and scheduledAt >= ?2 and scheduledAt < ?3",
                RecruitmentInterviewStatus.SCHEDULED, windowStart, windowEnd));
        // The window is contiguous, but the target set may not be (a long
        // lead time can skip a weekend), so drop anything that fell in the gap.
        Set<LocalDate> targets = Set.copyOf(targetDates);
        inWindow = inWindow.stream()
                .filter(i -> targets.contains(i.getScheduledAt().toLocalDate()))
                .toList();
        if (inWindow.isEmpty()) {
            return new BriefSummary(true, 0, 0, 0);
        }
        final List<RecruitmentInterview> loaded = inWindow;
        Context ctx = inTx(() -> Context.load(loaded));

        // Interviews on decided applications would brief someone about a
        // candidate who is already out — skip them.
        List<RecruitmentInterview> inPlay = inWindow.stream()
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

        // Already-briefed (interviewer, interview) pairs for this kind — the
        // idempotency lookup (event-derived, class javadoc).
        Map<String, Set<String>> briefed = inTx(() -> briefedPairs(inPlay, kind.eventType()));
        // The morning brief also asks what the EVE brief already covered, so a
        // pair that got the full pack yesterday gets the short line today —
        // and a pair the eve brief missed still gets the full pack.
        Map<String, Set<String>> eveBriefed = kind == BriefKind.MORNING
                ? inTx(() -> briefedPairs(inPlay, RecruitmentEventType.EVE_BRIEF_SENT))
                : Map.of();

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
                log.infof("%s brief: interviewer %s has no Slack link — skipping "
                        + "(no event, a later Slack link picks up naturally)",
                        kind, interviewerUuid);
                continue;
            }
            // Which of THIS interviewer's interviews were already prepped.
            Set<String> alreadyPrepped = interviews.stream()
                    .map(RecruitmentInterview::getUuid)
                    .filter(uuid -> eveBriefed.getOrDefault(uuid, Set.of()).contains(interviewerUuid))
                    .collect(Collectors.toSet());
            try {
                // DM before bookkeeping, one transaction per interviewer
                // (the P17 order — a Slack failure rolls the events back).
                QuarkusTransaction.requiringNew().run(() -> {
                    String fallback = briefText(interviews, ctx, today, kind, alreadyPrepped);
                    List<com.slack.api.model.block.LayoutBlock> blocks =
                            briefBlocks(interviews, ctx, today, scorecardButtons, kind, alreadyPrepped);
                    try {
                        slackService.sendMessage(interviewer, fallback, blocks);
                    } catch (Exception e) {
                        throw new IllegalStateException("Slack DM failed", e);
                    }
                    for (RecruitmentInterview interview : interviews) {
                        recordBriefed(interview, ctx, interviewerUuid, kind);
                    }
                });
                briefs++;
                covered += interviews.size();
            } catch (Exception e) {
                failures++;
                log.warnf(e, "%s brief: DM to interviewer %s failed — continuing "
                        + "(the next run retries)", kind, interviewerUuid);
            }
        }
        return new BriefSummary(true, briefs, covered, failures);
    }

    // ------------------------------------------------------------------
    // "The working day before"
    // ------------------------------------------------------------------

    /**
     * The interview dates whose brief day is {@code today} — i.e. every date
     * {@code d} where stepping {@code leadDays} back from {@code d} and then
     * off any weekend lands exactly on today.
     *
     * <p>With the default lead of one day that means Monday-to-Friday maps
     * one-to-one, and Friday additionally picks up Saturday, Sunday and
     * Monday: a Monday interview must be briefed on Friday, because a brief
     * delivered on Sunday is a brief nobody reads. A run on a Saturday or
     * Sunday returns empty — no date has a weekend day as its brief day.
     *
     * <p>The scan horizon is {@code leadDays + 3}, enough to clear the
     * longest run of non-working days a weekend can produce.
     */
    static List<LocalDate> eveTargetDates(LocalDate today, int leadDays) {
        List<LocalDate> dates = new ArrayList<>();
        for (int offset = 1; offset <= leadDays + 3; offset++) {
            LocalDate candidate = today.plusDays(offset);
            if (today.equals(briefDayFor(candidate, leadDays))) {
                dates.add(candidate);
            }
        }
        return List.copyOf(dates);
    }

    /** {@code leadDays} calendar days back, then backwards off the weekend. */
    private static LocalDate briefDayFor(LocalDate interviewDate, int leadDays) {
        LocalDate day = interviewDate.minusDays(leadDays);
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY
                || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.minusDays(1);
        }
        return day;
    }

    // ------------------------------------------------------------------
    // Idempotency lookup (event-derived)
    // ------------------------------------------------------------------

    /**
     * interview uuid → interviewer uuids already briefed for that interview's
     * own date, by this brief kind.
     *
     * <p>{@code brief_date} is the date the brief COVERS, never the date it
     * was sent — otherwise the eve brief's key would move with the calendar
     * and a Friday run covering Monday could not recognise its own work on a
     * retry. Matching per interview (rather than against one run-wide date)
     * is what lets a single eve run cover Saturday, Sunday and Monday
     * together.
     */
    private Map<String, Set<String>> briefedPairs(List<RecruitmentInterview> interviews,
                                                  RecruitmentEventType eventType) {
        List<String> applicationUuids = interviews.stream()
                .map(RecruitmentInterview::getApplicationUuid)
                .distinct()
                .toList();
        Map<String, String> dateByInterview = interviews.stream()
                .collect(Collectors.toMap(RecruitmentInterview::getUuid,
                        i -> i.getScheduledAt().toLocalDate().toString(), (a, b) -> a));
        List<RecruitmentEvent> events = RecruitmentEvent.list(
                "applicationUuid in ?1 and eventType = ?2", applicationUuids, eventType);
        Map<String, Set<String>> briefed = new HashMap<>();
        for (RecruitmentEvent event : events) {
            Map<String, Object> payload = parse(event.getPayload());
            Object interviewUuid = payload.get("interview_uuid");
            if (interviewUuid == null) {
                continue;
            }
            String expected = dateByInterview.get(interviewUuid.toString());
            if (expected == null || !expected.equals(payload.get("brief_date"))) {
                // Either an interview outside this run, or a brief for a date
                // the interview has since been moved off — a reschedule
                // re-arms the brief by construction.
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
                               String interviewerUuid, BriefKind kind) {
        RecruitmentApplication application = ctx.applications.get(interview.getApplicationUuid());
        RecruitmentPosition position = application == null ? null
                : ctx.positions.get(application.getPositionUuid());
        RecruitmentEventBuilder builder = RecruitmentEventBuilder
                .event(kind.eventType())
                .actorScheduler()
                .visibility(visibilityFor(position))
                .payload("interview_uuid", interview.getUuid())
                .payload("brief_date", interview.getScheduledAt().toLocalDate().toString())
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

    /**
     * The heading — the one place the two kinds read differently, because a
     * pack about tomorrow and a schedule for today are answering different
     * questions and must not look interchangeable in a notification preview.
     */
    private String heading(List<RecruitmentInterview> interviews, LocalDate today, BriefKind kind) {
        if (kind == BriefKind.MORNING) {
            return ":sunrise: *Your interviews today* — " + today.format(DAY_HEADER);
        }
        // The eve brief may span a weekend, so it names the days it covers
        // rather than claiming everything is "tomorrow".
        List<LocalDate> days = interviews.stream()
                .map(i -> i.getScheduledAt().toLocalDate())
                .distinct()
                .sorted()
                .toList();
        String when = days.size() == 1
                ? (days.get(0).equals(today.plusDays(1))
                        ? "tomorrow, " + days.get(0).format(DAY_HEADER)
                        : days.get(0).format(DAY_HEADER))
                : days.get(0).format(DAY_HEADER) + " – "
                        + days.get(days.size() - 1).format(DAY_HEADER);
        return ":books: *Coming up* — " + when + ". Time to prepare.";
    }

    /** The whole brief as plain text (Block Kit fallback / notification preview). */
    String briefText(List<RecruitmentInterview> interviews, Context ctx, LocalDate today,
                     BriefKind kind, Set<String> alreadyPrepped) {
        StringBuilder sb = new StringBuilder(256)
                .append(heading(interviews, today, kind))
                .append(interviews.size() == 1 ? " (1 interview)"
                        : " (" + interviews.size() + " interviews)");
        for (RecruitmentInterview interview : interviews) {
            sb.append('\n').append(interviewLine(interview, ctx, kind, alreadyPrepped));
        }
        sb.append('\n').append(footerText(kind, alreadyPrepped, interviews));
        return sb.toString();
    }

    /** The brief as Block Kit: header + one section (and optional button) per interview. */
    List<com.slack.api.model.block.LayoutBlock> briefBlocks(
            List<RecruitmentInterview> interviews, Context ctx, LocalDate today,
            boolean scorecardButtons, BriefKind kind, Set<String> alreadyPrepped) {
        List<com.slack.api.model.block.LayoutBlock> blocks = new ArrayList<>();
        blocks.add(com.slack.api.model.block.Blocks.section(s -> s.text(
                com.slack.api.model.block.composition.BlockCompositions.markdownText(
                        heading(interviews, today, kind)))));
        for (RecruitmentInterview interview : interviews) {
            String line = interviewLine(interview, ctx, kind, alreadyPrepped);
            blocks.add(com.slack.api.model.block.Blocks.section(s -> s.text(
                    com.slack.api.model.block.composition.BlockCompositions.markdownText(line))));
            // The scorecard button belongs on the pack, not on the day-of
            // line: a pair that already has yesterday's button does not need
            // a second one twelve hours later, and the end-of-meeting prompt
            // carries the one that actually gets used.
            if (scorecardButtons && interview.getKind() == RecruitmentInterviewKind.ROUND
                    && !isShort(interview, kind, alreadyPrepped)) {
                blocks.add(SlackRecruitmentViews.scorecardActions(interview.getUuid()));
            }
        }
        blocks.add(com.slack.api.model.block.Blocks.context(
                com.slack.api.model.block.element.BlockElements.asContextElements(
                        com.slack.api.model.block.composition.BlockCompositions.markdownText(
                                footerBlockText(kind, alreadyPrepped, interviews)))));
        return blocks;
    }

    /**
     * Whether this interview renders as the one-line day-of form: the
     * morning brief for a pair the eve pack already covered. Everything else
     * — the eve pack itself, and any morning brief for an interview booked
     * too late to make the eve run — renders in full.
     */
    private static boolean isShort(RecruitmentInterview interview, BriefKind kind,
                                   Set<String> alreadyPrepped) {
        return kind == BriefKind.MORNING && alreadyPrepped.contains(interview.getUuid());
    }

    private static boolean allShort(List<RecruitmentInterview> interviews, BriefKind kind,
                                    Set<String> alreadyPrepped) {
        return interviews.stream().allMatch(i -> isShort(i, kind, alreadyPrepped));
    }

    private String footerText(BriefKind kind, Set<String> alreadyPrepped,
                              List<RecruitmentInterview> interviews) {
        if (allShort(interviews, kind, alreadyPrepped)) {
            return "You have yesterday's prep. All your interviews: "
                    + baseUrl + "/recruitment/interviews";
        }
        return "Each name opens their brief — CV, answers and focus areas."
                + " All your interviews: " + baseUrl + "/recruitment/interviews";
    }

    private String footerBlockText(BriefKind kind, Set<String> alreadyPrepped,
                                   List<RecruitmentInterview> interviews) {
        if (allShort(interviews, kind, alreadyPrepped)) {
            return "You have yesterday's prep. <" + baseUrl
                    + "/recruitment/interviews|All your interviews>";
        }
        return "Each name opens their brief — CV, answers and focus areas. <"
                + baseUrl + "/recruitment/interviews|All your interviews>";
    }

    /** One interview as a single mrkdwn line — structural facts only. */
    private String interviewLine(RecruitmentInterview interview, Context ctx, BriefKind kind,
                                 Set<String> alreadyPrepped) {
        RecruitmentApplication application = ctx.applications.get(interview.getApplicationUuid());
        RecruitmentCandidate candidate = application == null ? null
                : ctx.candidates.get(application.getCandidateUuid());
        RecruitmentPosition position = application == null ? null
                : ctx.positions.get(application.getPositionUuid());
        // "round n" only when there IS one — an informal chat and an offer
        // meeting carry no round and must never render "round null".
        String kindLabel = switch (interview.getKind()) {
            case OFFER -> "offer meeting";
            case INFORMAL -> "informal chat";
            // A ROUND without a number is impossible by CHECK constraint;
            // the fallback keeps a migrated oddity readable (pre-V442 rows).
            case ROUND -> interview.getRound() == null
                    ? "informal chat"
                    : "round " + interview.getRound();
        };
        StringBuilder sb = new StringBuilder(200).append("• ");
        // The eve brief can span days, so its lines carry the weekday; the
        // day-of brief already said "today" in its heading.
        if (kind == BriefKind.EVE) {
            sb.append(interview.getScheduledAt().format(DAY_AND_TIME));
        } else {
            sb.append(interview.getScheduledAt().format(TIME));
        }
        sb.append(" — *").append(briefLink(candidate)).append('*');
        if (position != null && position.getTitle() != null) {
            sb.append(" (*").append(SlackCandidateFacts.mrkdwnSafe(position.getTitle()))
                    .append('*');
            sb.append(", ").append(kindLabel);
            sb.append(')');
        } else {
            sb.append(" (").append(kindLabel).append(')');
        }
        if (interview.getLocation() != null && !interview.getLocation().isBlank()) {
            sb.append(" · ").append(SlackCandidateFacts.mrkdwnSafe(interview.getLocation()));
        }
        // Focus areas belong to the scored kinds only — an informal chat
        // and an offer meeting take no scorecard, so the template would
        // be a prompt to fill in something that cannot be submitted. They
        // are also the bulk of the pack, so the short day-of line omits
        // them: the interviewer read them yesterday.
        if (!isShort(interview, kind, alreadyPrepped)
                && interview.getKind().takesScorecard() && position != null
                && position.getScorecardTemplate() != null
                && !position.getScorecardTemplate().isEmpty()) {
            sb.append("\n  Focus areas: ").append(position.getScorecardTemplate().stream()
                    .map(a -> SlackCandidateFacts.mrkdwnSafe(a.label()))
                    .collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }

    /**
     * The candidate's name as an mrkdwn link to their restricted brief —
     * the recipient is an assigned interviewer for this very interview, so
     * the brief endpoint admits them. Degrades to the bare name when the
     * candidate leg is missing, rather than emitting a link that 404s.
     */
    private String briefLink(RecruitmentCandidate candidate) {
        String name = displayName(candidate);
        if (candidate == null || candidate.getUuid() == null) {
            return name;
        }
        return "<" + baseUrl + "/recruitment/brief/" + candidate.getUuid() + "|" + name + ">";
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
