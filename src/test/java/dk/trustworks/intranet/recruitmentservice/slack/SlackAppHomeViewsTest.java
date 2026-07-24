package dk.trustworks.intranet.recruitmentservice.slack;

import com.slack.api.model.view.View;
import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse;
import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse.LandingInterview;
import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse.LandingKpis;
import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse.LandingTask;
import dk.trustworks.intranet.recruitmentservice.dto.MyReferralRow;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralDerivedStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-builder tests for the App Home view (P23): section presence per
 * content, the row cap + overflow line, the scorecard-button degradation,
 * the empty state, and plain-language referral status labels. No Quarkus,
 * no Slack client — the builder is deliberately I/O-free.
 */
class SlackAppHomeViewsTest {

    private static final String BASE = "https://intra.example";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 24, 8, 0);

    @Test
    void emptyLanding_rendersEmptyStateAndReferPrompt() {
        View view = SlackAppHomeViews.appHomeView(emptyLanding(), List.of(), true, BASE, NOW);

        assertEquals("home", view.getType());
        String blocks = view.getBlocks().toString();
        assertTrue(blocks.contains("Nothing needs you right now"));
        assertTrue(blocks.contains("/recruitment/refer"), "the refer prompt has a deep link");
        assertTrue(blocks.contains("convenience mirror"), "the footer explains what the tab is");
    }

    @Test
    void scorecardTask_buttonWhenToggleOn_deepLinkWhenOff() {
        LandingResponse landing = landingWithTasks(List.of(
                scorecardTask("int-1", "Anna Ager", "Senior Consultant", 26L)));

        String withButtons = SlackAppHomeViews
                .appHomeView(landing, List.of(), true, BASE, NOW).getBlocks().toString();
        assertTrue(withButtons.contains("Scorecards waiting for you"));
        assertTrue(withButtons.contains(SlackRecruitmentViews.SCORECARD_OPEN));
        assertTrue(withButtons.contains("int-1"), "the button value is the interview uuid");
        assertTrue(withButtons.contains("26 hours"), "hour-grain age under two days");

        String withoutButtons = SlackAppHomeViews
                .appHomeView(landing, List.of(), false, BASE, NOW).getBlocks().toString();
        assertFalse(withoutButtons.contains(SlackRecruitmentViews.SCORECARD_OPEN),
                "toggle off ⇒ deep-link-only degradation");
        assertTrue(withoutButtons.contains("/recruitment/interviews|Open scorecard"));
    }

    @Test
    void sectionsCapAtFiveRows_withOverflowLine() {
        List<LandingTask> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(scorecardTask("int-" + i, "Candidate " + i, "Position", 30L + i));
        }
        String blocks = SlackAppHomeViews
                .appHomeView(landingWithTasks(tasks), List.of(), false, BASE, NOW)
                .getBlocks().toString();

        assertTrue(blocks.contains("Candidate 4"), "the fifth row renders");
        assertFalse(blocks.contains("Candidate 5"), "the sixth row is capped away");
        assertTrue(blocks.contains("and 3 more"), "the overflow line counts the rest");
    }

    @Test
    void referralSection_plainLanguageStatuses_neverEnumNames() {
        List<MyReferralRow> referrals = List.of(
                referral("Jens Hansen", RecruitmentReferralDerivedStatus.AWAITING_TRIAGE),
                referral("Mia Madsen", RecruitmentReferralDerivedStatus.INTERVIEWING));
        String blocks = SlackAppHomeViews
                .appHomeView(emptyLanding(), referrals, false, BASE, NOW).getBlocks().toString();

        assertTrue(blocks.contains("Your referrals"));
        assertTrue(blocks.contains("Jens Hansen"));
        assertTrue(blocks.contains("Waiting for triage"), "milestones read as plain language");
        assertFalse(blocks.contains("AWAITING_TRIAGE"), "never raw enum names");
    }

    @Test
    void recruiterQueues_aggregateCountsWithDeepLinks() {
        LandingResponse landing = landingWithTasks(List.of(
                new LandingTask(LandingTask.TYPE_REFERRAL_TO_TRIAGE,
                        null, null, null, null, null, null, null, null, null, null, 3),
                new LandingTask(LandingTask.TYPE_EMAIL_REVIEW,
                        null, null, null, null, null, null, null, null, null, null, 1),
                new LandingTask(LandingTask.TYPE_IDLE_CANDIDATE,
                        "cand-1", "Idle Ida", "pos-1", "Consultant", "app-1",
                        null, null, "SCREENING", 240L, null, null)));
        String blocks = SlackAppHomeViews
                .appHomeView(landing, List.of(), false, BASE, NOW).getBlocks().toString();

        assertTrue(blocks.contains("3 referrals waiting for triage"));
        assertTrue(blocks.contains("1 candidate email waiting for review"), "singular form");
        assertTrue(blocks.contains("Idle candidates"));
        assertTrue(blocks.contains("in Screening"), "stage humanized");
        assertTrue(blocks.contains("10 days"), "day-grain age over two days");
        assertTrue(blocks.contains("/recruitment/candidates/cand-1"), "profile deep link");
    }

    @Test
    void upcomingInterviews_renderTimePlaceAndInformalLabel() {
        LandingResponse landing = new LandingResponse(LandingResponse.SHAPE_INTERVIEWER,
                new LandingKpis(0, 0, 2, 0), List.of(), List.of(),
                List.of(
                        new LandingInterview("int-9", "cand-9", "Nora Nord", "Consultant",
                                "ROUND", 2, LocalDateTime.of(2026, 7, 25, 10, 0),
                                "HQ room 2", true, false),
                        new LandingInterview("int-10", "cand-9", "Nora Nord", "Consultant",
                                "INFORMAL", null, LocalDateTime.of(2026, 7, 26, 13, 0),
                                null, false, false)),
                List.of());
        String blocks = SlackAppHomeViews
                .appHomeView(landing, List.of(), false, BASE, NOW).getBlocks().toString();

        assertTrue(blocks.contains("Your upcoming interviews"));
        assertTrue(blocks.contains("(round 2)"));
        assertTrue(blocks.contains("HQ room 2"));
        assertTrue(blocks.contains("informal chat"));
    }

    @Test
    void mrkdwnCharactersInNames_areEscaped() {
        LandingResponse landing = landingWithTasks(List.of(
                scorecardTask("int-1", "Anna <script> & *bold*", "Consulting & <Friends>", 5L)));
        String blocks = SlackAppHomeViews
                .appHomeView(landing, List.of(), false, BASE, NOW).getBlocks().toString();

        assertFalse(blocks.contains("<script>"), "angle brackets are escaped");
        assertTrue(blocks.contains("&lt;script&gt;"));
        assertTrue(blocks.contains("Consulting &amp;"));
    }

    // ---- Fixtures ---------------------------------------------------------------

    private static LandingResponse emptyLanding() {
        return new LandingResponse(LandingResponse.SHAPE_EMPLOYEE,
                new LandingKpis(0, 0, 0, 0), List.of(), List.of(), List.of(), List.of());
    }

    private static LandingResponse landingWithTasks(List<LandingTask> tasks) {
        return new LandingResponse(LandingResponse.SHAPE_RECRUITER,
                new LandingKpis(1, 1, 0, tasks.size()), tasks,
                List.of(), List.of(), List.of());
    }

    private static LandingTask scorecardTask(String interviewUuid, String candidateName,
                                             String positionTitle, Long ageHours) {
        return new LandingTask(LandingTask.TYPE_OVERDUE_SCORECARD,
                "cand-x", candidateName, "pos-x", positionTitle, "app-x",
                interviewUuid, 1, "INTERVIEW_1", ageHours, null, null);
    }

    private static MyReferralRow referral(String name, RecruitmentReferralDerivedStatus status) {
        return new MyReferralRow(java.util.UUID.randomUUID().toString(), name, null, null,
                LocalDateTime.of(2026, 7, 12, 9, 0), status);
    }
}
