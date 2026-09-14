package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.ClientPersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.ColleagueDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.FreshnessDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.RelationEdgeDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the account is actually being talked to (spec §3.7) — the six counters above the
 * table and the ratio that explains why the calendar half may be empty.
 *
 * <p>Each of these answers a question an owner asks in the same breath, and each has an edge
 * that is easy to get wrong on its own:
 * <ul>
 *   <li><b>"Contact" means somebody did or said something.</b> A {@code MET}, a {@code CLAIM}
 *       or a {@code KNOWS}. A Slack channel talking about a person is not contact with them,
 *       and neither is a LinkedIn invitation from 2013 — counting either would tell an owner
 *       the account is covered when nobody has spoken to anyone.</li>
 *   <li><b>{@code quiet} is {@code SectorService.isQuiet} and is never re-derived here.</b>
 *       Two properties of that rule are easy to get independently wrong: never seen counts as
 *       quiet, and exactly 90 days does NOT (the comparison is a strict {@code >}). The
 *       portfolio's quiet badge and this one must agree, or the same account reads as quiet in
 *       a list and busy on its own page.</li>
 *   <li><b>Only people with an Intra user count in the ratio, on BOTH sides.</b> A TrustLink
 *       name that matched no user has no account to consent with; left in the denominator it
 *       holds "3 of 5 share calendar metadata" below 100% for ever, with nothing anybody could
 *       do about it.</li>
 * </ul>
 */
class AccountRelationshipFreshnessTest {

    private static final String DORTE_UUID = "4e2f1f30-6a4b-49cd-9d4f-7c0a1f2b3c4d";
    private static final String CARLA_UUID = "8a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d";
    private static final String DORTE = "Dorte Kirkegaard";
    private static final String CARLA = "Carla Bendtsen";

    private static final String HANS_UUID = "7948c5e8-162c-4053-b905-0f59a21d7746";
    private static final String HANS = "Hans Lassen";
    private static final String TOBIAS_UUID = "ca0e1027-061f-49e7-b66a-a487c815f5a0";
    private static final String TOBIAS = "Tobias Kjølsen";
    private static final String MARIE = "Marie Dorthea";

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);
    private static final LocalDate NINETY_DAYS_AGO = TODAY.minusDays(90);
    private static final LocalDate NINETY_ONE_DAYS_AGO = TODAY.minusDays(91);
    private static final LocalDate LAST_WEEK = TODAY.minusDays(7);

    /** The one line the owner reads first: when, with whom, and by whom. */
    @Test
    void theLastContactIsTheLatestMeetingAcrossTheWholeAccount() {
        FreshnessDTO freshness = freshness(List.of(
                        met(DORTE_UUID, DORTE, HANS, NINETY_ONE_DAYS_AGO),
                        met(CARLA_UUID, CARLA, TOBIAS, LAST_WEEK)),
                List.of(), List.of(), null);

        assertEquals(LAST_WEEK, freshness.lastContactOn());
        assertEquals(CARLA, freshness.lastContactWith());
        assertEquals(TOBIAS, freshness.lastContactBy());
    }

    /** No meeting anywhere and there is nothing to say, rather than a zero date. */
    @Test
    void anAccountNobodyHasMetHasNoLastContact() {
        FreshnessDTO freshness = freshness(List.of(
                        knows(DORTE_UUID, DORTE, HANS, "fra DTU"),
                        connected(CARLA_UUID, CARLA, MARIE)),
                List.of(), List.of(), null);

        assertNull(freshness.lastContactOn());
        assertNull(freshness.lastContactWith());
        assertNull(freshness.lastContactBy());
        assertEquals(0, freshness.peopleMet90d());
    }

    /**
     * {@code peopleMet90d} counts PEOPLE, not meetings, and the window is inclusive at exactly
     * 90 days — the same boundary {@code RelationshipWarmth} puts between tier 1 and tier 2, so
     * "met recently" means one thing on the counter and on the row it counts.
     */
    @Test
    void peopleMetInNinetyDaysCountsPeopleAndIncludesTheBoundary() {
        FreshnessDTO freshness = freshness(List.of(
                        met(DORTE_UUID, DORTE, HANS, LAST_WEEK),
                        met(DORTE_UUID, DORTE, TOBIAS, NINETY_DAYS_AGO),
                        met(CARLA_UUID, CARLA, HANS, NINETY_ONE_DAYS_AGO)),
                List.of(), List.of(), null);

        assertEquals(1, freshness.peopleMet90d(), "Dorte once, counted once; Carla is outside the window");
    }

    @Test
    void exactlyNinetyDaysIsStillInsideTheWindow() {
        FreshnessDTO freshness = freshness(List.of(met(DORTE_UUID, DORTE, HANS, NINETY_DAYS_AGO)),
                List.of(), List.of(), null);

        assertEquals(1, freshness.peopleMet90d());
    }

    /**
     * A colleague "has contact" when they met somebody, claimed somebody, or wrote a signal
     * about somebody. Being in a Slack conversation and holding a LinkedIn connection are
     * neither — they are the two sources nobody acted on.
     */
    @Test
    void onlyMeetingsClaimsAndSignalsCountAsContact() {
        FreshnessDTO freshness = freshness(List.of(
                        met(DORTE_UUID, DORTE, HANS, LAST_WEEK),
                        claim(CARLA_UUID, CARLA, TOBIAS, 4),
                        knows(DORTE_UUID, DORTE, "Nicolas Bruun", "fra KOMBIT"),
                        heard(CARLA_UUID, CARLA, "Nicky Rasmussen", "Kent efterlyser reelle ejere"),
                        connected(CARLA_UUID, CARLA, MARIE)),
                List.of(), List.of(), null);

        assertEquals(3, freshness.colleaguesWithContact(),
                "Hans met, Tobias claimed, Nicolas wrote — Nicky and Marie did neither");
    }

    /**
     * The white spots: people we have said matter and have nothing on. Tier 4 is a channel or
     * a signal and tier 5 a LinkedIn invitation; tier 3 is an alumni contact, which is warm by
     * construction and is not a gap.
     */
    @Test
    void starredPeopleWithoutContactAreTheWhiteSpots() {
        FreshnessDTO freshness = freshness(List.of(), List.of(
                starred(DORTE_UUID, DORTE, 5),
                starred(CARLA_UUID, CARLA, 4),
                starred("8f0e1d2c-0000-4000-8000-000000000003", "Anne Sofie Holm", 3),
                unstarred("9f0e1d2c-0000-4000-8000-000000000004", "Bo Sandbjerg", 5)),
                List.of(), null);

        assertEquals(2, freshness.starredWithoutContact());
    }

    // ------------------------------------------------------------------------
    // quiet — SectorService's rule, never re-derived
    // ------------------------------------------------------------------------

    /** Never seen anything counts as quiet. That is the rule, and it is the honest reading. */
    @Test
    void anAccountWithNothingOnItIsQuiet() {
        assertTrue(freshness(List.of(), List.of(), List.of(), null).quiet());
    }

    /** Exactly 90 days is NOT quiet — the comparison is a strict greater-than. */
    @Test
    void exactlyNinetyDaysIsNotQuietYet() {
        assertFalse(freshness(List.of(met(DORTE_UUID, DORTE, HANS, NINETY_DAYS_AGO)),
                List.of(), List.of(), null).quiet());
    }

    @Test
    void ninetyOneDaysIsQuiet() {
        assertTrue(freshness(List.of(met(DORTE_UUID, DORTE, HANS, NINETY_ONE_DAYS_AGO)),
                List.of(), List.of(), null).quiet());
    }

    /**
     * The quiet rule asks about meetings AND signals. A capture filed last week keeps an
     * account out of the quiet list even when nobody has been in a room with anybody for half
     * a year — somebody heard something, which is the whole reason signals exist.
     */
    @Test
    void aRecentSignalKeepsAnAccountOutOfTheQuietList() {
        assertFalse(freshness(List.of(met(DORTE_UUID, DORTE, HANS, NINETY_ONE_DAYS_AGO)),
                List.of(), List.of(), LAST_WEEK).quiet());
        assertTrue(freshness(List.of(met(DORTE_UUID, DORTE, HANS, NINETY_ONE_DAYS_AGO)),
                        List.of(), List.of(), NINETY_ONE_DAYS_AGO).quiet(),
                "an old signal rescues nothing");
    }

    // ------------------------------------------------------------------------
    // sharing
    // ------------------------------------------------------------------------

    /**
     * The ratio, and who to go and ask. A colleague without an Intra user — a TrustLink name
     * the matcher would not guess at — is counted on neither side and never appears in the
     * list, because there is no setting for them to change.
     */
    @Test
    void onlyColleaguesWithAnIntraUserAreInTheRatio() {
        FreshnessDTO freshness = freshness(List.of(), List.of(), List.of(
                colleague(HANS_UUID, HANS, true),
                colleague(TOBIAS_UUID, TOBIAS, false),
                unmatched(MARIE)), null);

        assertEquals(1, freshness.sharing().consented());
        assertEquals(2, freshness.sharing().total());
        assertEquals(List.of(new PersonDTO(TOBIAS_UUID, TOBIAS, PersonDTO.initialsOf(TOBIAS))),
                freshness.sharing().notSharing());
    }

    /** Nobody on the account at all is an honest 0 of 0 rather than a division nobody can read. */
    @Test
    void anAccountWithNoColleaguesIsZeroOfZero() {
        FreshnessDTO freshness = freshness(List.of(), List.of(), List.of(), null);

        assertEquals(0, freshness.sharing().consented());
        assertEquals(0, freshness.sharing().total());
        assertTrue(freshness.sharing().notSharing().isEmpty());
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    private static FreshnessDTO freshness(List<RelationEdgeDTO> edges, List<ClientPersonDTO> people,
                                          List<ColleagueDTO> colleagues, LocalDate lastSignalOn) {
        return AccountRelationshipService.freshness(edges, people, colleagues, lastSignalOn, TODAY);
    }

    private static RelationEdgeDTO met(String personUuid, String person, String colleague, LocalDate lastMet) {
        return new RelationEdgeDTO(personUuid, colleague, null, person, 1, lastMet, null,
                RelationEdgeDTO.MET, null, null, null, null, null);
    }

    private static RelationEdgeDTO claim(String personUuid, String person, String colleague, int strength) {
        return new RelationEdgeDTO(personUuid, colleague, null, person, 0, null, null,
                RelationEdgeDTO.CLAIM, null, null, strength, null, LAST_WEEK);
    }

    private static RelationEdgeDTO knows(String personUuid, String person, String colleague, String via) {
        return new RelationEdgeDTO(personUuid, colleague, null, person, 0, null, via,
                RelationEdgeDTO.KNOWS, null, null, null, null, null);
    }

    private static RelationEdgeDTO heard(String personUuid, String person, String colleague, String headline) {
        return new RelationEdgeDTO(personUuid, colleague, null, person, 0, null, headline,
                RelationEdgeDTO.HEARD, null, LAST_WEEK, null, null, null);
    }

    private static RelationEdgeDTO connected(String personUuid, String person, String colleague) {
        return new RelationEdgeDTO(personUuid, colleague, null, person, 0, null, null,
                RelationEdgeDTO.CONNECTED, LocalDate.of(2013, 3, 1), null, null, null, null);
    }

    private static ClientPersonDTO starred(String uuid, String name, int tier) {
        return new ClientPersonDTO(uuid, name, PersonDTO.initialsOf(name), null,
                ClientPersonDTO.CONTACT, null, "stake-" + uuid, tier, null, 0, null);
    }

    private static ClientPersonDTO unstarred(String uuid, String name, int tier) {
        return new ClientPersonDTO(uuid, name, PersonDTO.initialsOf(name), null,
                ClientPersonDTO.CONTACT, null, null, tier, null, 0, null);
    }

    private static ColleagueDTO colleague(String uuid, String name, boolean sharesCalendar) {
        return new ColleagueDTO(uuid, name, PersonDTO.initialsOf(name), null, sharesCalendar, 1);
    }

    /** A TrustLink trustworker the matcher could not resolve: drawn, but with no account. */
    private static ColleagueDTO unmatched(String name) {
        return new ColleagueDTO(null, name, PersonDTO.initialsOf(name), null, false, 1);
    }
}
