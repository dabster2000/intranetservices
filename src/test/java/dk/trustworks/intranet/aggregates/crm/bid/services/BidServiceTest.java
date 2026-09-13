package dk.trustworks.intranet.aggregates.crm.bid.services;

import dk.trustworks.intranet.aggregates.crm.bid.model.enums.BidGoNoGo;
import dk.trustworks.intranet.aggregates.crm.bid.model.enums.BidOutcome;
import dk.trustworks.intranet.aggregates.crm.bid.model.enums.BidType;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The parsing and trimming in {@link BidService}.
 *
 * <p>The two rules that keep the win rate honest — a no-go has no outcome, and only a loss
 * names a competitor — live in {@code apply()}, which needs an entity and is exercised
 * against the local database during verification. What is locked here is the input
 * handling that decides what {@code apply()} ever sees.
 */
class BidServiceTest {

    @Test
    void enumsParseCaseInsensitively() {
        assertEquals(BidType.SKI_MINI, BidService.parse(BidType.class, "ski_mini", "type"));
        assertEquals(BidType.UDBUD, BidService.parse(BidType.class, " UDBUD ", "type"));
        assertEquals(BidGoNoGo.NOGO, BidService.parse(BidGoNoGo.class, "NoGo", "goNoGo"));
        assertEquals(BidOutcome.NOT_PUBLISHED, BidService.parse(BidOutcome.class, "not_published", "outcome"));
    }

    /**
     * An unknown outcome is refused rather than defaulted. Quietly turning a typo into OPEN
     * would keep a decided bid out of the win rate for good, and nobody would ever see it.
     */
    @Test
    void anUnknownValueIsRefusedAndNamesTheField() {
        WebApplicationException error = assertThrows(WebApplicationException.class,
                () -> BidService.parse(BidOutcome.class, "MAYBE", "outcome"));
        assertEquals(400, error.getResponse().getStatus());
        assertEquals("Unknown outcome: MAYBE", error.getMessage());
    }

    @Test
    void blankTextBecomesNullAndOverlongTextIsCutToTheColumn() {
        assertNull(BidService.trimTo("   ", 10));
        assertNull(BidService.trimTo(null, 10));
        assertEquals("Netcompany", BidService.trimTo("  Netcompany  ", 200));
        assertEquals("abc", BidService.trimTo("abcdef", 3));
    }
}
