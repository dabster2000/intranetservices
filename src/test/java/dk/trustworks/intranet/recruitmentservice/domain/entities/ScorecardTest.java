package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.ScorecardRecommendation;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ScorecardTest {

    @Test
    void newScorecard_canSetAllSixDimensions() {
        Scorecard s = new Scorecard();
        s.uuid = UUID.randomUUID().toString();
        s.interviewUuid = UUID.randomUUID().toString();
        s.interviewerUserUuid = UUID.randomUUID().toString();
        s.practiceSkillFit = (byte) 4;
        s.careerLevelFit = (byte) 3;
        s.consultingCommunication = (byte) 5;
        s.clientFacingMaturity = (byte) 4;
        s.cultureValueFit = (byte) 5;
        s.deliveryTrackPotential = (byte) 4;
        s.recommendation = ScorecardRecommendation.HIRE;
        s.submittedAt = LocalDateTime.now();

        assertEquals((byte) 4, s.practiceSkillFit);
        assertEquals(ScorecardRecommendation.HIRE, s.recommendation);
    }
}
