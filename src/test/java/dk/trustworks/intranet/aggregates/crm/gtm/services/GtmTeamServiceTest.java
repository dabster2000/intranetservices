package dk.trustworks.intranet.aggregates.crm.gtm.services;

import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who may change which sectors a GTM team covers, and how a segment is read off the wire.
 * Fast tier: no Quarkus boot, no database.
 */
class GtmTeamServiceTest {

    private static final String OWNER = "u-owner";
    private static final String CO_OWNER = "u-co";

    @Test
    void theTeamsLeadAndCoLeadMayEditIt() {
        assertTrue(GtmTeamService.mayEditTeam(OWNER, CO_OWNER, OWNER, false));
        assertTrue(GtmTeamService.mayEditTeam(OWNER, CO_OWNER, CO_OWNER, false));
    }

    @Test
    void anybodyElseMayNotUnlessTheyAreManagement() {
        assertFalse(GtmTeamService.mayEditTeam(OWNER, CO_OWNER, "u-member", false));
        assertTrue(GtmTeamService.mayEditTeam(OWNER, CO_OWNER, "u-partner", true));
    }

    /** A missing actor never matches a missing co-owner: null == null must not grant anything. */
    @Test
    void aBlankActorIsRefusedEvenWhenTheTeamHasNoCoLead() {
        assertFalse(GtmTeamService.mayEditTeam(OWNER, null, null, false));
        assertFalse(GtmTeamService.mayEditTeam(OWNER, null, "  ", false));
    }

    @Test
    void segmentsParseCaseInsensitivelyAndAnUnknownOneIs400() {
        assertEquals(ClientSegment.ENERGY, GtmTeamService.parseSegment("energy"));
        WebApplicationException error = assertThrows(WebApplicationException.class,
                () -> GtmTeamService.parseSegment("RETAIL"));
        assertEquals(400, error.getResponse().getStatus());
    }
}
