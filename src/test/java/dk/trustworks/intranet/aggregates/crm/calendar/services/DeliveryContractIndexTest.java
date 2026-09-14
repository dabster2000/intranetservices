package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.aggregates.crm.calendar.services.DeliveryContractIndex.DeliveryContractRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The date arithmetic behind the delivery filter (decision D1).
 *
 * <p>Every assertion here is a meeting that either survives or does not. Getting a boundary
 * wrong by one day on the open end of a window would let a currently-placed consultant's
 * entire standup calendar back into the account page, which is the state this filter was
 * built to end.
 *
 * <p>Fast tier — no Quarkus boot, no database.
 */
class DeliveryContractIndexTest {

    private static final String CLIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OTHER_CLIENT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String ME = "11111111-1111-1111-1111-111111111111";
    private static final String SOMEBODY_ELSE = "22222222-2222-2222-2222-222222222222";

    @Test
    void aDateInsideTheWindowIsDelivery() {
        DeliveryContractIndex index = indexOf(row(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));

        assertTrue(index.isDelivering(CLIENT, ME, LocalDate.of(2026, 6, 15)));
    }

    /** Both ends are inclusive: the first and the last day on site are days on site. */
    @Test
    void bothEndsOfTheWindowAreInclusive() {
        DeliveryContractIndex index = indexOf(row(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));

        assertTrue(index.isDelivering(CLIENT, ME, LocalDate.of(2026, 1, 1)));
        assertTrue(index.isDelivering(CLIENT, ME, LocalDate.of(2026, 12, 31)));
        assertFalse(index.isDelivering(CLIENT, ME, LocalDate.of(2025, 12, 31)));
        assertFalse(index.isDelivering(CLIENT, ME, LocalDate.of(2027, 1, 1)));
    }

    /**
     * The case that matters most. Assignment rows are routinely created with no end date,
     * and reading a null end as "ended" would leave every currently-placed consultant's
     * standups flowing straight into the relationship graph.
     */
    @Test
    void aNullEndMeansStillRunning() {
        DeliveryContractIndex index = indexOf(row(LocalDate.of(2026, 1, 1), null));

        assertTrue(index.isDelivering(CLIENT, ME, LocalDate.of(2030, 1, 1)));
        assertFalse(index.isDelivering(CLIENT, ME, LocalDate.of(2025, 12, 31)));
    }

    @Test
    void aNullStartMeansOpenAtTheStart() {
        DeliveryContractIndex index = indexOf(row(null, LocalDate.of(2026, 6, 30)));

        assertTrue(index.isDelivering(CLIENT, ME, LocalDate.of(2001, 1, 1)));
        assertFalse(index.isDelivering(CLIENT, ME, LocalDate.of(2026, 7, 1)));
    }

    @Test
    void aRowWithBothEndsNullCoversEverything() {
        DeliveryContractIndex index = indexOf(row(null, null));

        assertTrue(index.isDelivering(CLIENT, ME, LocalDate.of(1999, 1, 1)));
        assertTrue(index.isDelivering(CLIENT, ME, LocalDate.of(2099, 1, 1)));
    }

    /** Delivery is a fact about one person at one client and never generalises. */
    @Test
    void theIndexIsKeyedOnBothTheClientAndThePerson() {
        DeliveryContractIndex index = indexOf(row(LocalDate.of(2026, 1, 1), null));

        assertFalse(index.isDelivering(OTHER_CLIENT, ME, LocalDate.of(2026, 6, 15)));
        assertFalse(index.isDelivering(CLIENT, SOMEBODY_ELSE, LocalDate.of(2026, 6, 15)));
    }

    /**
     * Extensions are new rows rather than edits, so one person at one client routinely has
     * several windows and any of them matching is enough. A gap between two assignments is
     * a real gap — the meetings held in it were not delivery.
     */
    @Test
    void severalAssignmentsAtTheSameClientAreAllConsidered() {
        DeliveryContractIndex index = DeliveryContractIndex.of(List.of(
                new DeliveryContractRow(CLIENT, ME, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 30)),
                new DeliveryContractRow(CLIENT, ME, LocalDate.of(2026, 1, 1), null)));

        assertTrue(index.isDelivering(CLIENT, ME, LocalDate.of(2024, 3, 1)));
        assertTrue(index.isDelivering(CLIENT, ME, LocalDate.of(2026, 3, 1)));
        assertFalse(index.isDelivering(CLIENT, ME, LocalDate.of(2025, 3, 1)), "the gap between the two");
        assertEquals(1, index.size(), "one (client, person) pair, two windows");
    }

    /**
     * A window that ends before it starts describes no time at all. Silently swapping the
     * ends would invent an assignment nobody ever made and filter meetings on the strength
     * of it.
     */
    @Test
    void aBackwardsWindowCoversNothingAndIsNotRepaired() {
        DeliveryContractIndex index = indexOf(
                row(LocalDate.of(2026, 12, 31), LocalDate.of(2026, 1, 1)));

        assertFalse(index.isDelivering(CLIENT, ME, LocalDate.of(2026, 6, 15)));
        assertEquals(0, index.size());
    }

    @Test
    void rowsWithoutAClientOrAPersonAreIgnoredRatherThanIndexedUnderNull() {
        DeliveryContractIndex index = DeliveryContractIndex.of(List.of(
                new DeliveryContractRow(null, ME, null, null),
                new DeliveryContractRow(CLIENT, null, null, null)));

        assertEquals(0, index.size());
    }

    @Test
    void anEmptyIndexFiltersNothing() {
        assertFalse(DeliveryContractIndex.empty().isDelivering(CLIENT, ME, LocalDate.of(2026, 6, 15)));
        assertEquals(0, DeliveryContractIndex.empty().size());
    }

    /** Null arguments are an absence of information, never a reason to drop a meeting. */
    @Test
    void nullArgumentsNeverFilterAMeeting() {
        DeliveryContractIndex index = indexOf(row(null, null));

        assertFalse(index.isDelivering(null, ME, LocalDate.of(2026, 6, 15)));
        assertFalse(index.isDelivering(CLIENT, null, LocalDate.of(2026, 6, 15)));
        assertFalse(index.isDelivering(CLIENT, ME, null));
    }

    private static DeliveryContractRow row(LocalDate activeFrom, LocalDate activeTo) {
        return new DeliveryContractRow(CLIENT, ME, activeFrom, activeTo);
    }

    private static DeliveryContractIndex indexOf(DeliveryContractRow row) {
        return DeliveryContractIndex.of(List.of(row));
    }
}
