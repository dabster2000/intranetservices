package dk.trustworks.intranet.aggregates.crm.trustlink.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paging against an upstream whose own page count understates what it serves.
 *
 * <p>TrustLink counts DISTINCT people in {@code totalCount} but returns one row per
 * matching company, and the alias table deliberately points one client at several company
 * names for the same organisation — "Netcompany" and "Netcompany A/S" are two rows for one
 * firm. A batch spanning both came back with 470 items against a {@code totalCount} of 457.
 *
 * <p>That broke paging in two separate ways, and both are pinned here: the response was
 * rejected outright as implausible (see {@code TrustLinkClientParseTest}), and
 * {@code totalPages}, being derived from the same understated count, would have stopped the
 * loop with rows still unread.
 */
class TrustLinkSearchResponsePagingTest {

    private static TrustLinkSearchResponse page(int items, int totalCount, int page, int pageSize, int totalPages) {
        return new TrustLinkSearchResponse(
                java.util.Collections.nCopies(items, (TrustLinkConnectionDTO) null),
                totalCount, page, pageSize, totalPages);
    }

    @Test
    @DisplayName("a full page means another page, even when totalPages says this was the last")
    void aFullPageOutranksAnUnderstatedTotalPages() {
        // The real shape: 500 returned, upstream claims one page because it counted 457 people.
        assertTrue(page(500, 457, 1, 500, 1).hasMorePages());
    }

    @Test
    @DisplayName("a short page ends the batch — there is nothing after it")
    void aShortPageEndsIt() {
        assertFalse(page(470, 457, 1, 500, 1).hasMorePages());
        assertFalse(page(151, 645, 2, 500, 2).hasMorePages());
    }

    @Test
    @DisplayName("an empty page always ends the batch, whatever totalPages claims")
    void anEmptyPageAlwaysEndsIt() {
        assertFalse(page(0, 1000, 2, 500, 9).hasMorePages());
    }

    @Test
    @DisplayName("ordinary paging still works: page 1 of 2 has more, page 2 of 2 does not")
    void ordinaryPagingIsUnchanged() {
        assertTrue(page(500, 507, 1, 500, 2).hasMorePages());
        assertFalse(page(7, 507, 2, 500, 2).hasMorePages());
    }

    @Test
    @DisplayName("null items is not a crash")
    void nullItemsIsSafe() {
        assertFalse(new TrustLinkSearchResponse(null, 0, 1, 500, 0).hasMorePages());
    }
}
