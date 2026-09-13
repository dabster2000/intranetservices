package dk.trustworks.intranet.aggregates.crm.trustlink.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the unmatched names survive a trip through {@code VARCHAR(2000)} and back.
 *
 * <p>The packing is the only part of the bookkeeping row that can be wrong without anything
 * failing: an over-long run would be refused by the database (a 500 on the nightly job, for a
 * diagnostic), and a separator that appears inside a name would split one colleague into two
 * that nobody can find in the directory. Neither shows up in a compile.
 */
class TrustLinkSyncStateNamesTest {

    @Test
    @DisplayName("names round-trip in the order they were written")
    void roundTripsInOrder() {
        List<String> names = List.of("Marie Dorthea", "Tanja Kaufmann", "André Engsbye");
        assertEquals(names, TrustLinkSyncState.decodeNames(TrustLinkSyncState.encodeNames(names)));
    }

    /**
     * The reason the separator is a newline and not a comma. TrustLink holds names with commas
     * in them; splitting on one would invent a colleague called "MBA" and lose the real person.
     */
    @Test
    @DisplayName("a name containing a comma stays one name")
    void aCommaInsideANameDoesNotSplitIt() {
        List<String> names = List.of("Joao Paulo Venezian de Carvalho, MBA", "Michelle R. Cantor");
        assertEquals(names, TrustLinkSyncState.decodeNames(TrustLinkSyncState.encodeNames(names)));
    }

    @Test
    @DisplayName("nothing to report is a null column, not an empty string")
    void nothingToReportIsNull() {
        assertNull(TrustLinkSyncState.encodeNames(List.of()));
        assertNull(TrustLinkSyncState.encodeNames(null));
        assertNull(TrustLinkSyncState.encodeNames(List.of("  ")));
    }

    @Test
    @DisplayName("an empty column decodes to an empty list, never to null")
    void anEmptyColumnDecodesToAnEmptyList() {
        assertTrue(TrustLinkSyncState.decodeNames(null).isEmpty());
        assertTrue(TrustLinkSyncState.decodeNames("").isEmpty());
        assertTrue(TrustLinkSyncState.decodeNames("   ").isEmpty());
    }

    /**
     * The column is a diagnostic, not a contract: overflowing it must cost the last few names
     * and never the nightly run. Truncation happens at a name boundary, because a half-written
     * name sends somebody searching the staff directory for a colleague who does not exist.
     */
    @Test
    @DisplayName("an absurd run is truncated at a name boundary, and still fits the column")
    void overflowIsTruncatedWholeNamesFirst() {
        List<String> tooMany = IntStream.rangeClosed(1, 200)
                .mapToObj(i -> "A Very Long Trustworker Name Number " + i)
                .toList();

        String packed = TrustLinkSyncState.encodeNames(tooMany);

        assertTrue(packed.length() <= 2000, "must fit VARCHAR(2000)");
        List<String> decoded = TrustLinkSyncState.decodeNames(packed);
        assertTrue(decoded.size() < tooMany.size(), "the tail is expected to be dropped");
        assertEquals(tooMany.subList(0, decoded.size()), decoded, "every surviving name must be whole");
    }

    /** A newline inside a name would corrupt the separator, so it is flattened on the way in. */
    @Test
    @DisplayName("a newline inside a name cannot forge a separator")
    void aNewlineInsideANameIsFlattened() {
        assertEquals(List.of("Hans Ernst Lassen"),
                TrustLinkSyncState.decodeNames(TrustLinkSyncState.encodeNames(List.of("Hans\nErnst Lassen"))));
    }
}
