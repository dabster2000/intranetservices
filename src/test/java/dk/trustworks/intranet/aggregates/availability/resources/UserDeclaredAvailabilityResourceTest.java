package dk.trustworks.intranet.aggregates.availability.resources;

import dk.trustworks.intranet.aggregates.availability.model.UserDeclaredAvailability.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The row's {@code source} is derived from the actor, never taken from the body. */
class UserDeclaredAvailabilityResourceTest {

    private static final String SUBJECT = "0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b";
    private static final String LEAD = "ffffffff-0000-1111-2222-333333333333";

    @Test
    void selfWriteIsSelf() {
        assertEquals(Source.SELF, UserDeclaredAvailabilityResource.sourceFor(SUBJECT, SUBJECT));
        assertEquals(Source.SELF, UserDeclaredAvailabilityResource.sourceFor(SUBJECT.toUpperCase(), SUBJECT));
    }

    @Test
    void anotherHumanIsTeamLead() {
        assertEquals(Source.TEAMLEAD, UserDeclaredAvailabilityResource.sourceFor(LEAD, SUBJECT));
    }

    @Test
    void headerlessMachineCallerIsSystem() {
        assertEquals(Source.SYSTEM, UserDeclaredAvailabilityResource.sourceFor(null, SUBJECT));
    }
}
