package dk.trustworks.intranet.recruitmentservice.slack;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.view.View;
import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse;
import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse.LandingInterview;
import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse.LandingTask;
import dk.trustworks.intranet.recruitmentservice.dto.MyReferralRow;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralDerivedStatus;
import dk.trustworks.intranet.recruitmentservice.notifications.SlackCandidateFacts;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.divider;
import static com.slack.api.model.block.Blocks.header;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.asContextElements;
import static com.slack.api.model.view.Views.view;

/**
 * The App Home dashboard view (P23, Slack spec §5.7) — a personal,
 * role-aware mirror of the {@code /recruitment} landing queue rendered on
 * the mother app's Home tab. Pure builder (no I/O), sibling of
 * {@link SlackRecruitmentViews}, so layout and copy are unit-testable
 * without a Slack client.
 *
 * <h3>Content rules</h3>
 * <ul>
 *   <li><b>Input is the landing read model</b> ({@link LandingResponse} —
 *       the P17 aggregate, already visibility-filtered per viewer:
 *       partner-circle hard filter, involvement scoping, blind rule) plus
 *       the viewer's own {@link MyReferralRow} list. Nothing here
 *       re-derives authorization — the builder renders exactly what the
 *       intranet landing would show the same person.</li>
 *   <li><b>Moderate-PII rule</b> (Slack spec §2.2): candidate names,
 *       position titles, stages, dates and counts only — no note text, no
 *       scorecard prose, no email bodies (none of which exist on the
 *       landing DTO by construction). All interpolated names/titles are
 *       mrkdwn-escaped.</li>
 *   <li><b>Deep links, not buttons</b>, for navigation — mrkdwn links
 *       avoid dead {@code block_actions} payloads for URL-only buttons.
 *       The one real button is the P18 scorecard open
 *       ({@link SlackRecruitmentViews#scorecardActions}), which is
 *       surface-agnostic by construction.</li>
 *   <li>Sections render only when non-empty and are capped at
 *       {@link #MAX_ROWS_PER_SECTION} rows with a "+N more" overflow line
 *       — Slack rejects views above 100 blocks, and the intranet carries
 *       the full lists anyway.</li>
 * </ul>
 */
public final class SlackAppHomeViews {

    /** Per-section row cap — keeps the view far below Slack's 100-block limit. */
    static final int MAX_ROWS_PER_SECTION = 5;

    /** Wall-clock Europe/Copenhagen display format (the P11/P18 model). */
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE d MMM 'at' HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private SlackAppHomeViews() {
    }

    /**
     * Build the full Home view for one viewer.
     *
     * @param landing          the viewer's landing aggregate (P17 read model)
     * @param referrals        the viewer's own referrals, newest first
     * @param scorecardButtons render the P18 "Fill in scorecard" button on
     *                         overdue-scorecard rows (the
     *                         {@code recruitment.slack.scorecard.enabled}
     *                         toggle); off ⇒ deep-link-only rows — the
     *                         explicit degradation chain
     * @param baseUrl          intranet base URL for deep links
     * @param now              current wall-clock time for the "updated" stamp
     */
    public static View appHomeView(LandingResponse landing, List<MyReferralRow> referrals,
                                   boolean scorecardButtons, String baseUrl, LocalDateTime now) {
        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(header(h -> h.text(plainText("Recruitment"))));
        blocks.add(context(asContextElements(markdownText(
                "Your personal recruitment overview · updated " + now.format(WHEN)
                        + " · the full picture lives in <" + baseUrl + "/recruitment|the intranet>"))));

        boolean anyContent = false;
        anyContent |= scorecardSection(blocks, landing, scorecardButtons, baseUrl);
        anyContent |= interviewSection(blocks, landing, baseUrl);
        anyContent |= decisionSection(blocks, landing, baseUrl);
        anyContent |= recruiterQueueSection(blocks, landing, baseUrl);
        anyContent |= idleSection(blocks, landing, baseUrl);
        anyContent |= referralSection(blocks, referrals, baseUrl);

        if (!anyContent) {
            blocks.add(section(s -> s.text(markdownText(
                    ":white_check_mark: *Nothing needs you right now.*\n"
                            + "Tasks appear here the moment they need your attention — "
                            + "overdue scorecards, today's interviews, decisions waiting, "
                            + "and the status of people you refer."))));
            blocks.add(section(s -> s.text(markdownText(
                    ":raised_hands: *Know someone who'd fit Trustworks?* "
                            + "Referrals are our best hiring channel — it takes about 60 seconds: "
                            + "<" + baseUrl + "/recruitment/refer|Refer a candidate>"))));
        }

        blocks.add(divider());
        blocks.add(context(asContextElements(markdownText(
                "This tab is a convenience mirror of "
                        + "<" + baseUrl + "/recruitment|the recruitment landing page> — "
                        + "it refreshes when you open it and shortly after your queue changes."))));
        return view(v -> v.type("home").blocks(blocks));
    }

    // ------------------------------------------------------------------
    // Sections (each returns true when it rendered anything)
    // ------------------------------------------------------------------

    private static boolean scorecardSection(List<LayoutBlock> blocks, LandingResponse landing,
                                            boolean scorecardButtons, String baseUrl) {
        List<LandingTask> tasks = tasksOf(landing, LandingTask.TYPE_OVERDUE_SCORECARD);
        if (tasks.isEmpty()) {
            return false;
        }
        blocks.add(section(s -> s.text(markdownText(
                ":hourglass_flowing_sand: *Scorecards waiting for you* (" + tasks.size() + ")"))));
        for (LandingTask task : capped(tasks)) {
            StringBuilder sb = new StringBuilder(160)
                    .append("*").append(safe(task.candidateName())).append("*");
            appendPosition(sb, task.positionTitle(), task.round());
            if (task.ageHours() != null) {
                sb.append(" · interviewed ").append(age(task.ageHours())).append(" ago");
            }
            if (!scorecardButtons) {
                sb.append(" · <").append(baseUrl).append("/recruitment/interviews|Open scorecard>");
            }
            blocks.add(section(s -> s.text(markdownText(sb.toString()))));
            if (scorecardButtons) {
                blocks.add(SlackRecruitmentViews.scorecardActions(task.interviewUuid()));
            }
        }
        overflow(blocks, tasks.size(), baseUrl + "/recruitment/interviews");
        blocks.add(context(asContextElements(markdownText(
                "Colleagues can't see your scores until you submit your own — "
                        + "it takes about 90 seconds."))));
        return true;
    }

    private static boolean interviewSection(List<LayoutBlock> blocks, LandingResponse landing,
                                            String baseUrl) {
        List<LandingInterview> upcoming = landing.upcomingInterviews() == null
                ? List.of() : landing.upcomingInterviews();
        if (upcoming.isEmpty()) {
            return false;
        }
        blocks.add(section(s -> s.text(markdownText(
                ":calendar: *Your upcoming interviews* (" + upcoming.size() + ")"))));
        for (LandingInterview interview : upcoming.subList(0,
                Math.min(upcoming.size(), MAX_ROWS_PER_SECTION))) {
            StringBuilder sb = new StringBuilder(160)
                    .append("*").append(safe(interview.candidateName())).append("*");
            appendPosition(sb, interview.positionTitle(),
                    "INFORMAL".equals(interview.kind()) ? null : interview.round());
            if ("INFORMAL".equals(interview.kind())) {
                sb.append(" · informal chat");
            }
            if (interview.scheduledAt() != null) {
                sb.append(" · ").append(interview.scheduledAt().format(WHEN));
            }
            if (interview.location() != null && !interview.location().isBlank()) {
                sb.append(" · ").append(safe(interview.location()));
            }
            blocks.add(section(s -> s.text(markdownText(sb.toString()))));
        }
        overflow(blocks, upcoming.size(), baseUrl + "/recruitment/interviews");
        blocks.add(context(asContextElements(markdownText(
                "Your kit (CV, focus areas, scorecard) for each interview: <"
                        + baseUrl + "/recruitment/interviews|open the interviews page>"))));
        return true;
    }

    private static boolean decisionSection(List<LayoutBlock> blocks, LandingResponse landing,
                                           String baseUrl) {
        List<LandingTask> tasks = tasksOf(landing, LandingTask.TYPE_PENDING_DECISION);
        if (tasks.isEmpty()) {
            return false;
        }
        blocks.add(section(s -> s.text(markdownText(
                ":scales: *Decisions waiting for you* (" + tasks.size() + ")"))));
        for (LandingTask task : capped(tasks)) {
            StringBuilder sb = new StringBuilder(160)
                    .append("*").append(safe(task.candidateName())).append("*");
            appendPosition(sb, task.positionTitle(), task.round());
            if (task.ageHours() != null) {
                sb.append(" · all scorecards in for ").append(age(task.ageHours()));
            }
            sb.append(" · <").append(baseUrl).append("/recruitment/candidates/")
                    .append(task.candidateUuid()).append("|Review the debrief>");
            blocks.add(section(s -> s.text(markdownText(sb.toString()))));
        }
        overflow(blocks, tasks.size(), baseUrl + "/recruitment");
        return true;
    }

    private static boolean recruiterQueueSection(List<LayoutBlock> blocks, LandingResponse landing,
                                                 String baseUrl) {
        List<String> lines = new ArrayList<>(2);
        tasksOf(landing, LandingTask.TYPE_REFERRAL_TO_TRIAGE).stream().findFirst()
                .ifPresent(t -> lines.add(count(t) + " referral" + plural(count(t))
                        + " waiting for triage · <" + baseUrl
                        + "/recruitment/refer|Open the triage queue>"));
        tasksOf(landing, LandingTask.TYPE_EMAIL_REVIEW).stream().findFirst()
                .ifPresent(t -> lines.add(count(t) + " candidate email" + plural(count(t))
                        + " waiting for review before sending · <" + baseUrl
                        + "/recruitment|Review and send>"));
        if (lines.isEmpty()) {
            return false;
        }
        blocks.add(section(s -> s.text(markdownText(":file_cabinet: *Recruiter queues*"))));
        for (String line : lines) {
            blocks.add(section(s -> s.text(markdownText(line))));
        }
        return true;
    }

    private static boolean idleSection(List<LayoutBlock> blocks, LandingResponse landing,
                                       String baseUrl) {
        List<LandingTask> tasks = tasksOf(landing, LandingTask.TYPE_IDLE_CANDIDATE);
        if (tasks.isEmpty()) {
            return false;
        }
        blocks.add(section(s -> s.text(markdownText(
                ":zzz: *Idle candidates* (" + tasks.size() + ")"))));
        for (LandingTask task : capped(tasks)) {
            StringBuilder sb = new StringBuilder(160)
                    .append("*").append(safe(task.candidateName())).append("*");
            appendPosition(sb, task.positionTitle(), null);
            if (task.stage() != null) {
                sb.append(" · in ").append(SlackHandlerSupport.humanizeStage(task.stage()));
            }
            if (task.ageHours() != null) {
                sb.append(" for ").append(age(task.ageHours()));
            }
            sb.append(" · <").append(baseUrl).append("/recruitment/candidates/")
                    .append(task.candidateUuid()).append("|Open profile>");
            blocks.add(section(s -> s.text(markdownText(sb.toString()))));
        }
        overflow(blocks, tasks.size(), baseUrl + "/recruitment/pipeline");
        return true;
    }

    private static boolean referralSection(List<LayoutBlock> blocks, List<MyReferralRow> referrals,
                                           String baseUrl) {
        if (referrals == null || referrals.isEmpty()) {
            return false;
        }
        blocks.add(section(s -> s.text(markdownText(
                ":raised_hands: *Your referrals* (" + referrals.size() + ")"))));
        for (MyReferralRow row : referrals.subList(0,
                Math.min(referrals.size(), MAX_ROWS_PER_SECTION))) {
            StringBuilder sb = new StringBuilder(120)
                    .append("*").append(safe(row.candidateName())).append("*")
                    .append(" · ").append(statusLabel(row.derivedStatus()));
            if (row.submittedAt() != null) {
                sb.append(" · sent ").append(row.submittedAt().format(DAY));
            }
            blocks.add(section(s -> s.text(markdownText(sb.toString()))));
        }
        overflow(blocks, referrals.size(), baseUrl + "/recruitment/refer");
        blocks.add(context(asContextElements(markdownText(
                "Statuses update automatically as your referral moves through the pipeline. "
                        + "Refer someone new: <" + baseUrl + "/recruitment/refer|open the refer page>"))));
        return true;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static List<LandingTask> tasksOf(LandingResponse landing, String type) {
        return landing.tasks() == null ? List.of()
                : landing.tasks().stream().filter(t -> type.equals(t.type())).toList();
    }

    private static List<LandingTask> capped(List<LandingTask> tasks) {
        return tasks.subList(0, Math.min(tasks.size(), MAX_ROWS_PER_SECTION));
    }

    private static void overflow(List<LayoutBlock> blocks, int total, String url) {
        if (total > MAX_ROWS_PER_SECTION) {
            int more = total - MAX_ROWS_PER_SECTION;
            blocks.add(section(s -> s.text(markdownText(
                    "…and " + more + " more — <" + url + "|see the full list>"))));
        }
    }

    private static void appendPosition(StringBuilder sb, String positionTitle, Integer round) {
        if (positionTitle != null) {
            sb.append(" — ").append(safe(positionTitle));
        }
        if (round != null) {
            sb.append(" (round ").append(round).append(')');
        }
    }

    /** "3 hours" under two days, whole days after (the landing carries hours). */
    private static String age(long ageHours) {
        if (ageHours < 48) {
            return ageHours + " hour" + (ageHours == 1 ? "" : "s");
        }
        long days = ageHours / 24;
        return days + " days";
    }

    private static int count(LandingTask task) {
        return task.count() == null ? 0 : task.count();
    }

    private static String plural(int count) {
        return count == 1 ? "" : "s";
    }

    /** Milestone label in plain language (mirrors the refer page's vocabulary). */
    static String statusLabel(RecruitmentReferralDerivedStatus status) {
        if (status == null) {
            return "Unknown";
        }
        return switch (status) {
            case AWAITING_TRIAGE -> "Waiting for triage";
            case UNDER_REVIEW -> "Being reviewed";
            case IN_SCREENING -> "In screening";
            case INTERVIEWING -> "Interviewing";
            case OFFER -> "Offer stage";
            case IN_TALENT_POOL -> "In the talent pool";
            case HIRED -> "Hired :tada:";
            case NOT_PROCEEDING -> "Not proceeding";
            case CLOSED -> "Closed";
        };
    }

    private static String safe(String text) {
        return text == null ? "—" : SlackCandidateFacts.mrkdwnSafe(text);
    }
}
