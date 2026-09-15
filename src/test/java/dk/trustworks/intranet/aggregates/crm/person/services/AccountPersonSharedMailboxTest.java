package dk.trustworks.intranet.aggregates.crm.person.services;

import dk.trustworks.intranet.aggregates.crm.person.model.AccountPerson;
import dk.trustworks.intranet.aggregates.crm.person.model.AccountPersonIdentity;
import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonIdentityKind;
import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccountPersonSharedMailboxTest {
    @Test void calendarOnlySharedMailboxIsRetiredWithoutDeletingIdentity() {
        AccountPerson person = person("CALENDAR");
        var registry = AccountPersonService.Registry.of(List.of(person), List.of(email("sg.it@client.example")));
        RecordingWriter writer = new RecordingWriter();
        AccountPersonService.retireSharedCalendarPeople(registry, writer);
        assertTrue(person.isRetired());
        assertEquals(List.of(person), writer.people);
        assertEquals(1, registry.identitiesByKey().size(), "identity remains available for deliberate correction");
    }

    @Test void anotherPersonalEmailOrIndependentSourcePreventsAutomaticRetirement() {
        for (String source : List.of("CALENDAR,TRUSTLINK", "CALENDAR,SIGNAL", "CALENDAR,REVIEW", "TRUSTLINK")) {
            AccountPerson person = person(source);
            AccountPersonService.retireSharedCalendarPeople(AccountPersonService.Registry.of(List.of(person),
                    List.of(email("sg.it@client.example"))), new RecordingWriter());
            assertFalse(person.isRetired(), source);
        }
        AccountPerson person = person("CALENDAR");
        AccountPersonService.retireSharedCalendarPeople(AccountPersonService.Registry.of(List.of(person),
                List.of(email("sg.it@client.example"), email("sara@client.example"))), new RecordingWriter());
        assertFalse(person.isRetired());
    }

    @Test void reviewedIdentityHasItsOwnSourceAndDoesNotInventCalendarEvidence() {
        var sighting = AccountPersonService.sighting(AccountPersonSource.REVIEW,
                "Sara Client", "sara@client.example", null, null, null);
        var drafts = AccountPersonService.merge(List.of(sighting));
        assertEquals(1, drafts.size());
        assertEquals("REVIEW", drafts.getFirst().sources());
        assertEquals("Sara Client", drafts.getFirst().name());
        assertEquals(List.of(AccountPersonSource.REVIEW), AccountPersonSource.split("REVIEW"));
    }

    static AccountPerson person(String sources) {
        var person = new AccountPerson();
        person.setUuid("person"); person.setClientUuid("client"); person.setName("IT");
        person.setNameKey("it|it"); person.setSources(sources);
        return person;
    }
    static AccountPersonIdentity email(String email) {
        var identity = new AccountPersonIdentity();
        identity.setUuid(email); identity.setPersonUuid("person"); identity.setClientUuid("client");
        identity.setKind(AccountPersonIdentityKind.EMAIL); identity.setValue(email);
        return identity;
    }
    static class RecordingWriter implements AccountPersonService.RegistryWriter {
        final List<AccountPerson> people = new ArrayList<>();
        public void write(AccountPerson person) { people.add(person); }
        public void write(AccountPersonIdentity identity) { fail("no identity rewrite expected"); }
        public void flush() { }
    }
}
