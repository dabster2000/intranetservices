package dk.trustworks.intranet.recruitmentservice.domain.enums;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Slice3aEnumsTest {

    @Test
    void interviewStatus_hasFiveValues() {
        assertEquals(5, InterviewStatus.values().length);
        assertNotNull(InterviewStatus.valueOf("SCHEDULED"));
        assertNotNull(InterviewStatus.valueOf("HELD"));
        assertNotNull(InterviewStatus.valueOf("CANCELLED"));
        assertNotNull(InterviewStatus.valueOf("ROUNDED_UP"));
        assertNotNull(InterviewStatus.valueOf("RESCHEDULED"));
    }

    @Test
    void interviewRoundType_hasFourValues() {
        assertEquals(4, InterviewRoundType.values().length);
        for (String n : new String[]{"FIRST","CASE_OR_TECH","FINAL","SPECIAL"}) {
            assertNotNull(InterviewRoundType.valueOf(n));
        }
    }

    @Test
    void participantRole_hasFiveValues() {
        assertEquals(5, ParticipantRole.values().length);
        for (String n : new String[]{"LEAD_INTERVIEWER","SCORER","OBSERVER","TAM","PRACTICE_SUPPORT"}) {
            assertNotNull(ParticipantRole.valueOf(n));
        }
    }

    @Test
    void participantInvitationStatus_hasFourValues() {
        assertEquals(4, ParticipantInvitationStatus.values().length);
        for (String n : new String[]{"INVITED","ACCEPTED","DECLINED","TENTATIVE"}) {
            assertNotNull(ParticipantInvitationStatus.valueOf(n));
        }
    }

    @Test
    void scorecardRecommendation_hasFiveValues_orderingPreserved() {
        // Ordering matters for composite-score math: STRONG_HIRE > HIRE > LEAN_HIRE > LEAN_NO > NO_HIRE
        ScorecardRecommendation[] expected = {
            ScorecardRecommendation.STRONG_HIRE,
            ScorecardRecommendation.HIRE,
            ScorecardRecommendation.LEAN_HIRE,
            ScorecardRecommendation.LEAN_NO,
            ScorecardRecommendation.NO_HIRE
        };
        assertArrayEquals(expected, ScorecardRecommendation.values());
    }

    @Test
    void roundUpDecision_hasFourValues() {
        assertEquals(4, RoundUpDecision.values().length);
        for (String n : new String[]{"HIRE","REJECT","NEXT_ROUND","BACKLOG"}) {
            assertNotNull(RoundUpDecision.valueOf(n));
        }
    }
}
