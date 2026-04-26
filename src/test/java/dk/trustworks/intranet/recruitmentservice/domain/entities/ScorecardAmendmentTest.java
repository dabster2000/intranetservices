package dk.trustworks.intranet.recruitmentservice.domain.entities;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ScorecardAmendmentTest {
    @Test
    void newAmendment_setsRequiredFields() {
        ScorecardAmendment a = new ScorecardAmendment();
        a.uuid = UUID.randomUUID().toString();
        a.scorecardUuid = UUID.randomUUID().toString();
        a.authorUuid = UUID.randomUUID().toString();
        a.body = "Adding context after reflection.";
        assertEquals("Adding context after reflection.", a.body);
    }
}
