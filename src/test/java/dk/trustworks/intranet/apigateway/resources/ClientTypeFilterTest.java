package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code GET /clients?type=} — the one string that keeps prospects out of billing.
 *
 * <p>A third value on {@link ClientType} is only safe because every existing consumer keeps
 * excluding it until it opts in: the invoice picker, devices, project descriptions and the
 * AM alert summary all pass or default to {@code CLIENT}. The rule this suite locks is the
 * DEFAULT — anything unparseable, empty or absent must degrade to CLIENT alone, never to
 * "everything", because the failure mode of the other reading is a prospect in the invoice
 * picker.
 *
 * <p>Fast tier: a pure static parser, no Quarkus and no database.
 */
class ClientTypeFilterTest {

    @Test
    void nothingAtAllIsClientAlone() {
        assertEquals(Set.of(ClientType.CLIENT), ClientResource.parseTypes(null));
        assertEquals(Set.of(ClientType.CLIENT), ClientResource.parseTypes(""));
        assertEquals(Set.of(ClientType.CLIENT), ClientResource.parseTypes("   "));
        assertEquals(Set.of(ClientType.CLIENT), ClientResource.parseTypes(",,,"));
    }

    @Test
    void oneTypeIsThatTypeAndNothingElse() {
        assertEquals(Set.of(ClientType.PARTNER), ClientResource.parseTypes("PARTNER"));
        assertEquals(Set.of(ClientType.PROSPECT), ClientResource.parseTypes("PROSPECT"));
    }

    @Test
    void aCommaListIsTheCallersOptIn() {
        // What the accounts list, the lead form's picker and the sector tabs ask for.
        assertEquals(Set.of(ClientType.CLIENT, ClientType.PROSPECT),
                ClientResource.parseTypes("CLIENT,PROSPECT"));
        assertEquals(Set.of(ClientType.CLIENT, ClientType.PROSPECT),
                ClientResource.parseTypes(" client , prospect "));
    }

    @Test
    void duplicatesCollapse() {
        assertEquals(Set.of(ClientType.CLIENT), ClientResource.parseTypes("CLIENT,client,CLIENT"));
    }

    /**
     * Refused, not silently dropped.
     *
     * <p>A typo that degraded to CLIENT would answer a request for prospects with billing
     * clients and look like an empty prospect list, which is a bug somebody would chase in
     * the wrong place for an afternoon.
     */
    @Test
    void anUnknownTypeIsRefused() {
        BadRequestException thrown =
                assertThrows(BadRequestException.class, () -> ClientResource.parseTypes("CUSTOMER"));
        assertEquals("Unknown client type: CUSTOMER", thrown.getMessage());
        assertThrows(BadRequestException.class, () -> ClientResource.parseTypes("CLIENT,LEAD"));
    }
}
