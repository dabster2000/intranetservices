package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Trustworks side of the graph must not name the same person twice.
 *
 * <p>Every edge names its Trustworks end by NAME — that is what lets a TrustLink
 * trustworker who matched no Intra user still be drawn, and it is how the Overview card
 * groups its chips. So two entries sharing a name are not two people on screen; they are
 * one person's chip rendered twice, once with an avatar and once without.
 *
 * <p>The case is not hypothetical. This firm has duplicate {@code user} rows for the same
 * human, and a duplicate is precisely what makes the trustworker matcher's name rungs
 * ambiguous — two users fit, it refuses to guess, and the sync writes the edge with a null
 * {@code user_uuid}. If either duplicate is also on this account, the card would say the
 * name twice.
 *
 * <p>The rule is exact-name equality and nothing looser: "Marie Dorthea" is not "Marie
 * Dorthea Sørensen", and collapsing those would be the matcher's refused guess smuggled in
 * through the read side.
 */
class AccountRelationshipPeopleDedupeTest {

    private static final String UNRESOLVED = "trustlink-name:";

    @Test
    @DisplayName("an unresolved TrustLink name is dropped when a real user on the account has that exact name")
    void unresolvedNameShadowedByARealUserIsDropped() {
        Map<String, PersonDTO> people = new LinkedHashMap<>();
        people.put("uuid-1", PersonDTO.named("uuid-1", "Christian Ingemann"));
        people.put(UNRESOLVED + "Christian Ingemann", PersonDTO.named(null, "Christian Ingemann"));

        AccountRelationshipService.dropShadowedUnresolvedPeople(people);

        assertEquals(1, people.size());
        assertEquals("uuid-1", people.values().iterator().next().uuid());
    }

    @Test
    @DisplayName("an unresolved name nobody on the account shares is kept in full")
    void unresolvedNameWithNoTwinSurvives() {
        Map<String, PersonDTO> people = new LinkedHashMap<>();
        people.put("uuid-1", PersonDTO.named("uuid-1", "Hans Ernst Lassen"));
        people.put(UNRESOLVED + "Marie Dorthea", PersonDTO.named(null, "Marie Dorthea"));

        AccountRelationshipService.dropShadowedUnresolvedPeople(people);

        assertEquals(2, people.size());
        assertTrue(people.containsKey(UNRESOLVED + "Marie Dorthea"));
    }

    @Test
    @DisplayName("a shorter TrustLink spelling is NOT collapsed into a longer payroll name")
    void aPrefixOfARealNameIsNotTreatedAsTheSamePerson() {
        Map<String, PersonDTO> people = new LinkedHashMap<>();
        people.put("uuid-1", PersonDTO.named("uuid-1", "Marie Dorthea Sørensen"));
        people.put(UNRESOLVED + "Marie Dorthea", PersonDTO.named(null, "Marie Dorthea"));

        // The matcher saw this pair and declined to resolve it — with 242 tier-5
        // connections riding on the answer, the read side must not quietly decide it after
        // the fact. Two entries here is the honest output.
        AccountRelationshipService.dropShadowedUnresolvedPeople(people);

        assertEquals(2, people.size());
    }

    @Test
    @DisplayName("order does not matter — the unresolved twin goes whether it arrived first or last")
    void theUnresolvedTwinGoesRegardlessOfInsertionOrder() {
        Map<String, PersonDTO> people = new LinkedHashMap<>();
        people.put(UNRESOLVED + "Henrik Falch Midtgaard", PersonDTO.named(null, "Henrik Falch Midtgaard"));
        people.put("uuid-9", PersonDTO.named("uuid-9", "Henrik Falch Midtgaard"));

        AccountRelationshipService.dropShadowedUnresolvedPeople(people);

        assertEquals(1, people.size());
        assertEquals("uuid-9", people.values().iterator().next().uuid());
    }

    @Test
    @DisplayName("a graph made only of unresolved names keeps every one of them")
    void nothingIsDroppedWhenNobodyResolved() {
        Map<String, PersonDTO> people = new LinkedHashMap<>();
        people.put(UNRESOLVED + "Marie Dorthea", PersonDTO.named(null, "Marie Dorthea"));
        people.put(UNRESOLVED + "Tanja Kaufmann", PersonDTO.named(null, "Tanja Kaufmann"));

        AccountRelationshipService.dropShadowedUnresolvedPeople(people);

        assertEquals(2, people.size());
    }
}
