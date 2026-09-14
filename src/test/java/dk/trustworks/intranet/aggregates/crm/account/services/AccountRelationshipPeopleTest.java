package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.ClientPersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.RelationEdgeDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The people half of the answer: what each row on the table carries, and the order they come
 * in (spec §3.3, §3.4).
 *
 * <p>Three of these are rules a reader would not catch in review:
 * <ul>
 *   <li><b>A person with no edges at all is still listed.</b> The registry keeps a person
 *       whose sources have gone quiet — a claim, a star, or a classification somebody is about
 *       to correct all have to survive an alias being switched off — and a person missing from
 *       the table cannot be starred or claimed, which is exactly how they would stay
 *       missing.</li>
 *   <li><b>{@code ALUMNI} is clamped to tier 3, in both directions.</b> A former colleague who
 *       now works at the client is one of the warmest contacts the firm has (defect D4), so
 *       they can never rank below 3 however silent their edges are — and a real meeting last
 *       week still lifts them above it.</li>
 *   <li><b>{@code meetings} is a sum over MET edges only.</b> A claim, a channel and a
 *       LinkedIn invitation all weigh 0, and adding them would put a number in the meetings
 *       column that no calendar could account for.</li>
 * </ul>
 */
class AccountRelationshipPeopleTest {

    private static final String DORTE_UUID = "4e2f1f30-6a4b-49cd-9d4f-7c0a1f2b3c4d";
    private static final String CARLA_UUID = "8a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d";
    private static final String ALUMNI_UUID = "1a2b3c4d-5e6f-4071-8293-a4b5c6d7e8f9";
    private static final String ALUMNI_USER_UUID = "7948c5e8-162c-4053-b905-0f59a21d7746";

    private static final String DORTE = "Dorte Kirkegaard";
    private static final String CARLA = "Carla Bendtsen";
    private static final String ANNE = "Anne Sofie Holm";

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);
    private static final LocalDate LAST_WEEK = LocalDate.of(2026, 9, 7);
    private static final LocalDate LAST_YEAR = LocalDate.of(2025, 9, 7);
    private static final LocalDate LEFT_US = LocalDate.of(2023, 3, 31);

    private final Map<String, AccountRelationshipService.RegisteredPerson> visible = new LinkedHashMap<>();
    private final Map<String, String> stakeholders = new LinkedHashMap<>();
    private final Map<String, LocalDate> leftOn = new LinkedHashMap<>();

    /** A meeting last week is tier 1; nothing at all is tier 5; and both are listed. */
    @Test
    void aPersonWithNoEdgeIsStillOnThePage() {
        contact(DORTE_UUID, DORTE);
        contact(CARLA_UUID, CARLA);

        List<ClientPersonDTO> people = people(List.of(met(DORTE_UUID, "Hans Lassen", 3, LAST_WEEK)));

        assertEquals(2, people.size());
        assertEquals(1, byUuid(people, DORTE_UUID).tier());
        assertEquals(5, byUuid(people, CARLA_UUID).tier(),
                "nothing is known about her, which is a row on the page and not an absence from it");
        assertEquals(0, byUuid(people, CARLA_UUID).meetings());
        assertNull(byUuid(people, CARLA_UUID).lastContactOn());
    }

    /**
     * The alumni clamp, both ways. With only a LinkedIn connection she is tier 3 rather than
     * tier 5; with a meeting last week she is tier 1, because the clamp is a floor on how cold
     * she can be and never a ceiling on how warm.
     */
    @Test
    void anAlumniPersonIsNeverColderThanTierThree() {
        alumni(ALUMNI_UUID, ANNE, ALUMNI_USER_UUID);
        leftOn.put(ALUMNI_USER_UUID, LEFT_US);

        List<ClientPersonDTO> onlyConnected = people(List.of(connected(ALUMNI_UUID, "Hans Lassen")));
        assertEquals(3, onlyConnected.get(0).tier());
        assertEquals(ClientPersonDTO.ALUMNI, onlyConnected.get(0).kind());
        assertEquals(LEFT_US, onlyConnected.get(0).alumniLeftOn(), "the day she left us");

        List<ClientPersonDTO> alsoMet = people(List.of(
                connected(ALUMNI_UUID, "Hans Lassen"),
                met(ALUMNI_UUID, "Hans Lassen", 1, LAST_WEEK)));
        assertEquals(1, alsoMet.get(0).tier(), "a real meeting still lifts her above the clamp");
    }

    /** A contact never carries a left-on date, whatever the directory happens to hold. */
    @Test
    void aContactCarriesNoAlumniDate() {
        contact(DORTE_UUID, DORTE);
        leftOn.put(ALUMNI_USER_UUID, LEFT_US);

        assertNull(people(List.of()).get(0).alumniLeftOn());
    }

    /**
     * {@code meetings} sums the MET edges and only those; {@code lastContactOn} is the latest
     * of their days. A claim and a connection on the same person add nothing to either, which
     * is what keeps the meetings column answerable from a calendar.
     */
    @Test
    void meetingsSumOverMetEdgesAndNothingElse() {
        contact(DORTE_UUID, DORTE);

        List<ClientPersonDTO> people = people(List.of(
                met(DORTE_UUID, "Hans Lassen", 3, LAST_YEAR),
                met(DORTE_UUID, "Tobias Kjølsen", 2, LAST_WEEK),
                claim(DORTE_UUID, "Hans Lassen", 4, LAST_WEEK),
                connected(DORTE_UUID, "Marie Dorthea")));

        assertEquals(5, people.get(0).meetings());
        assertEquals(LAST_WEEK, people.get(0).lastContactOn());
    }

    /** A star is a {@code client_plan_stakeholder} row, and non-null is the whole of "matters here". */
    @Test
    void aStarIsTheStakeholderRowItCreated() {
        contact(DORTE_UUID, DORTE);
        contact(CARLA_UUID, CARLA);
        stakeholders.put(DORTE_UUID, "stake-1");

        List<ClientPersonDTO> people = people(List.of());

        assertEquals("stake-1", byUuid(people, DORTE_UUID).stakeholderUuid());
        assertNull(byUuid(people, CARLA_UUID).stakeholderUuid());
    }

    /**
     * The default order: tier, then the most recent contact, then the name. The name tiebreak
     * is not decoration — the frontend re-sorts with the same rule after an optimistic claim,
     * and two rows that swap places between renders of identical data read as a bug.
     */
    @Test
    void theDefaultOrderIsWarmestFirstThenMostRecentThenName() {
        contact(DORTE_UUID, DORTE);
        contact(CARLA_UUID, CARLA);
        contact(ALUMNI_UUID, ANNE);

        List<ClientPersonDTO> people = people(List.of(
                connected(CARLA_UUID, "Marie Dorthea"),
                met(DORTE_UUID, "Hans Lassen", 1, LAST_WEEK),
                met(ALUMNI_UUID, "Hans Lassen", 1, LAST_YEAR)));

        assertEquals(List.of(DORTE, ANNE, CARLA), people.stream().map(ClientPersonDTO::name).toList());
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    private List<ClientPersonDTO> people(List<RelationEdgeDTO> edges) {
        return AccountRelationshipService.people(
                new AccountRelationshipService.PersonIndex(visible, Map.of()),
                AccountRelationshipService.groupByPerson(edges),
                stakeholders,
                new AccountRelationshipService.Directory(Map.of(), java.util.Set.of(), leftOn),
                TODAY);
    }

    private static ClientPersonDTO byUuid(List<ClientPersonDTO> people, String uuid) {
        return people.stream().filter(person -> uuid.equals(person.uuid())).findFirst().orElseThrow();
    }

    private void contact(String personUuid, String name) {
        visible.put(personUuid, new AccountRelationshipService.RegisteredPerson(
                personUuid, name, PersonDTO.initialsOf(name), null,
                ClientPersonDTO.CONTACT, null, null));
    }

    private void alumni(String personUuid, String name, String userUuid) {
        visible.put(personUuid, new AccountRelationshipService.RegisteredPerson(
                personUuid, name, PersonDTO.initialsOf(name), null,
                ClientPersonDTO.ALUMNI, userUuid, null));
    }

    private static RelationEdgeDTO met(String personUuid, String colleague, int meetings, LocalDate lastMet) {
        return new RelationEdgeDTO(personUuid, colleague, null, "x", meetings, lastMet, null,
                RelationEdgeDTO.MET, null, null, null, null, null);
    }

    private static RelationEdgeDTO claim(String personUuid, String colleague, int strength, LocalDate claimedAt) {
        return new RelationEdgeDTO(personUuid, colleague, null, "x", 0, null, null,
                RelationEdgeDTO.CLAIM, null, null, strength, null, claimedAt);
    }

    private static RelationEdgeDTO connected(String personUuid, String colleague) {
        return new RelationEdgeDTO(personUuid, colleague, null, "x", 0, null, null,
                RelationEdgeDTO.CONNECTED, LocalDate.of(2013, 3, 1), null, null, null, null);
    }
}
