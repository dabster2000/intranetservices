package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two lists that keep the scorecard reminder budget honest (V531).
 *
 * <p>V531 added a second way to ask an interviewer for a scorecard — the
 * end-of-meeting prompt — alongside the existing 24-hour chase. The promise
 * made when it shipped was that it moves the FIRST ask earlier rather than
 * adding another one: both append an event, both events count toward the
 * single {@code recruitment.sla.max-scorecard-nudges} budget.
 *
 * <p>That promise rests on two separate lists agreeing.
 * {@code priorScorecardNudges} counts the types in {@code SCORECARD_ASK_TYPES},
 * but it counts them out of {@code Context.nudgeEvents}, which only holds the
 * types named in {@code NUDGE_EVENT_TYPES}. A type in the first list and not
 * the second is not a mild inconsistency — the lookup silently returns empty,
 * so the cap never trips AND, because the prompt sweep suppresses on
 * "have we asked before", it re-sends every 15 minutes for a full 24-hour
 * window. That is a Slack DM loop, not a missed reminder.
 *
 * <p>Both lists are plain constants, so this is a pure fast-tier test — which
 * matters, because the fast tier is the only suite the deploy gate runs. The
 * behavioural tests that exercise the cap end to end are {@code @QuarkusTest}
 * and are not part of that gate.
 */
class RecruitmentSlaScorecardAskTypesTest {

    @Test
    @DisplayName("both ways of asking for a scorecard count toward one budget")
    void bothAskTypesAreCounted() {
        assertTrue(RecruitmentSlaService.SCORECARD_ASK_TYPES
                        .contains(RecruitmentEventType.SCORECARD_NUDGED),
                "the 24h overdue chase must count toward the scorecard nudge cap");
        assertTrue(RecruitmentSlaService.SCORECARD_ASK_TYPES
                        .contains(RecruitmentEventType.SCORECARD_PROMPTED),
                "the end-of-meeting prompt must count toward the scorecard nudge cap — "
                        + "without it the cap doubles and interviewers get more DMs than "
                        + "the setting promises");
    }

    @Test
    @DisplayName("every counted ask type is actually loaded, or the cap silently stops working")
    void everyAskTypeIsLoadedIntoContext() {
        for (RecruitmentEventType type : RecruitmentSlaService.SCORECARD_ASK_TYPES) {
            assertTrue(RecruitmentSlaService.NUDGE_EVENT_TYPES.contains(type),
                    type + " is counted toward the scorecard cap but is not loaded into "
                            + "Context.nudgeEvents, so the count is always zero: the cap "
                            + "never trips and the end-of-meeting prompt re-sends every "
                            + "15 minutes for a full day");
        }
    }
}
