package dk.trustworks.intranet.aggregates.crm.account.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Who a signal row connects to the person it named (CRM spec §3.7, V593).
 *
 * <p>Before V593 the answer was always "the author, and nobody else", because a capture
 * had nowhere to record a second name. <i>"…som jeg har mødt i KOMBIT sammen Tobias
 * Kjølsen"</i> names two of us, and Tobias was thrown away — he appeared neither in
 * "Who knows them" nor in the relationship graph, which is half of what was reported.
 *
 * <p>Plain JUnit against the pure helper, so the fast tier that gates deploys holds it.
 * The SQL around it needs a database; the part that is easy to get subtly wrong — and
 * whose failure is a doubled or missing edge that nothing else would catch — does not.
 */
class AccountRelationshipSignalEdgeTest {

    private static final String HANS = "7948c5e8-162c-4053-b905-0f59a21d7746";
    private static final String TOBIAS = "ca0e1027-061f-49e7-b66a-a487c815f5a0";
    private static final String METTE = "44444444-4444-4444-4444-444444444444";

    /** A capture nobody else was named on draws exactly one edge, from its author. */
    @Test
    void anUnaccompaniedCaptureIsJustItsAuthor() {
        assertEquals(List.of(HANS), AccountRelationshipService.namedTrustworksPeople(HANS, null));
        assertEquals(List.of(HANS), AccountRelationshipService.namedTrustworksPeople(HANS, ""));
        assertEquals(List.of(HANS), AccountRelationshipService.namedTrustworksPeople(HANS, "   "));
    }

    /** The reported case: the author plus the colleague the line named. */
    @Test
    void theAuthorComesFirstThenEveryNamedColleague() {
        assertEquals(List.of(HANS, TOBIAS, METTE),
                AccountRelationshipService.namedTrustworksPeople(HANS, TOBIAS + "," + METTE));
    }

    /**
     * {@code group_concat} is a string, and a stray space around a uuid would make it
     * match nothing in {@code User.findById} — a colleague who silently vanishes from the
     * graph rather than an error anybody sees.
     */
    @Test
    void whitespaceAroundConcatenatedUuidsIsTolerated() {
        assertEquals(List.of(HANS, TOBIAS, METTE),
                AccountRelationshipService.namedTrustworksPeople(HANS, " " + TOBIAS + " , " + METTE + " "));
    }

    /**
     * The author must never appear twice, whatever the child rows say.
     *
     * <p>{@code AccountSignalService} filters the author out on write, so this should be
     * unreachable — but a row written before that rule existed, or by hand in a datafix,
     * would otherwise draw the author's KNOWS edge twice and make one person look like
     * two relationships on the same account.
     */
    @Test
    void theAuthorIsNeverDoubledEvenIfAChildRowNamesThem() {
        assertEquals(List.of(HANS, TOBIAS),
                AccountRelationshipService.namedTrustworksPeople(HANS, HANS + "," + TOBIAS));
    }

    /** Nor is anybody else, if the same colleague somehow appears twice. */
    @Test
    void aRepeatedColleagueIsOneEdge() {
        assertEquals(List.of(HANS, TOBIAS),
                AccountRelationshipService.namedTrustworksPeople(HANS, TOBIAS + "," + TOBIAS));
    }

    /** Empty elements from a concat over a sparse join must not become blank uuids. */
    @Test
    void emptyElementsAreDropped() {
        assertEquals(List.of(HANS, TOBIAS),
                AccountRelationshipService.namedTrustworksPeople(HANS, "," + TOBIAS + ",,"));
    }
}
