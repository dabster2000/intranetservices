package dk.trustworks.intranet.recruitmentservice.filters;

import dk.trustworks.intranet.recruitmentservice.api.dto.RecruitmentTransitionError;
import dk.trustworks.intranet.recruitmentservice.domain.statemachines.InvalidTransitionException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecruitmentTransitionExceptionMapperTest {

    @Test
    void mapsInvalidTransitionTo409WithAllowedList() {
        var ex = new InvalidTransitionException(
                "Cannot move OpenRole from FILLED to SOURCING",
                List.of("CANCELLED"));
        var mapper = new RecruitmentTransitionExceptionMapper();

        try (Response r = mapper.toResponse(ex)) {
            assertEquals(409, r.getStatus());
            RecruitmentTransitionError body = (RecruitmentTransitionError) r.getEntity();
            assertTrue(body.error().contains("FILLED"));
            assertEquals(409, body.status());
            assertEquals(List.of("CANCELLED"), body.allowedTransitions());
        }
    }
}
