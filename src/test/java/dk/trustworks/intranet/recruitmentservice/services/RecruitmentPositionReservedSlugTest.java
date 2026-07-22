package dk.trustworks.intranet.recruitmentservice.services;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reserved position slugs (P5): {@code unsolicited} would shadow the
 * literal {@code /apply/unsolicited} route; {@code privacy} and
 * {@code config} are reserved for the public surface's future pages. The
 * check runs inside {@code validateSlugUnique}, so both position create
 * and update paths hit it.
 */
class RecruitmentPositionReservedSlugTest {

    @Test
    void reservedSlugs_are400() {
        for (String slug : new String[]{"unsolicited", "privacy", "config"}) {
            WebApplicationException rejected = assertThrows(WebApplicationException.class,
                    () -> RecruitmentPositionService.validateSlugNotReserved(slug),
                    "'" + slug + "' must be rejected");
            assertEquals(400, rejected.getResponse().getStatus());
        }
    }

    @Test
    void ordinarySlugs_pass() {
        assertDoesNotThrow(() -> RecruitmentPositionService.validateSlugNotReserved("senior-consultant-pm"));
        assertDoesNotThrow(() -> RecruitmentPositionService.validateSlugNotReserved("unsolicited-2"));
        assertDoesNotThrow(() -> RecruitmentPositionService.validateSlugNotReserved(null));
    }

    @Test
    void reservedSetMatchesTheLiteralPublicRoutes() {
        assertTrue(RecruitmentPositionService.RESERVED_SLUGS.containsAll(
                        java.util.Set.of("unsolicited", "privacy", "config")),
                "the reserved set must cover every literal /apply route");
    }
}
