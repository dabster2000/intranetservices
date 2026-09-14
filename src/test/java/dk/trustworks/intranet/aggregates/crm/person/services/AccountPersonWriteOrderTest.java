package dk.trustworks.intranet.aggregates.crm.person.services;

import dk.trustworks.intranet.aggregates.crm.person.model.AccountPerson;
import dk.trustworks.intranet.aggregates.crm.person.model.AccountPersonIdentity;
import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonIdentityKind;
import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonKind;
import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonSource;
import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService.ColleagueIndex;
import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService.PersonDraft;
import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService.RebuildSummary;
import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService.Registry;
import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService.RegistryWriter;
import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService.Sighting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ORDER {@code AccountPersonService.applyDrafts} writes one client's registry in, and what
 * happens to a row that turns out to be somebody the registry already knows.
 *
 * <h2>Why an order is worth a test class</h2>
 * Nothing here is about what the rebuild decides — {@code AccountPersonMergeTest} and
 * {@code AccountPersonClassificationTest} own that. This is about the sequence the decisions
 * reach MariaDB in, and it is worth pinning because both ways of getting it wrong fail the
 * same way: <b>the client's entire registry for that night is lost</b>, the pass logs one
 * warning with a code and no name, and the account page goes on showing yesterday's answer as
 * though nothing had happened.
 *
 * <ul>
 *   <li><b>Identities before their people.</b> {@code hibernate.order_inserts=true} lets the
 *       {@code InsertActionSorter} re-group queued inserts, and it can only see dependencies
 *       declared as associations. {@code AccountPersonIdentity.personUuid} is a plain column,
 *       so it sees none and is free to emit every identity first —
 *       {@code fk_account_person_identity}, error 1452.</li>
 *   <li><b>A name key taken while another row still holds it.</b> Inserts run before updates
 *       inside one flush, so the row that frees a key is written last unless somebody makes it
 *       otherwise — {@code uq_account_person_client_key}, error 1062.</li>
 *   <li><b>A merged-away row left visible.</b> Not a crash at all: the loser keeps its old
 *       name key and is drawn forever as a duplicate person with no edges, 0 meetings, tier 5.
 *       That is defect D8 surviving in the one place this cut was built to end it.</li>
 * </ul>
 *
 * <p>Fast tier. {@code applyDrafts} takes a {@link RegistryWriter}, so the writes can be
 * recorded and asserted without a Quarkus boot or a database — which is the point: an ordering
 * rule only guarded by an integration test is a rule the deploy gate does not hold.
 */
class AccountPersonWriteOrderTest {

    private static final String CLIENT = "6d0e4b3a-51b0-4a2e-9a09-6a6f0d2c9a11";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 3, 0);
    private static final LocalDateTime YESTERDAY = LocalDateTime.of(2026, 9, 13, 3, 0);
    private static final LocalDateTime LAST_YEAR = LocalDateTime.of(2025, 9, 13, 3, 0);

    // ------------------------------------------------------------------------
    // The foreign key: every person is in the database before any identity points at one
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Every person is written, and flushed, before the first identity")
    void everyPersonIsWrittenAndFlushedBeforeTheFirstIdentity() {
        RecordingWriter writer = new RecordingWriter();

        AccountPersonService.applyDrafts(CLIENT,
                drafts(calendar("Sif Broby Madsen", "sif@dsb.dk"), calendar("Ole Brandt", "ole@dsb.dk")),
                ColleagueIndex.empty(), Set.of(), Registry.empty(), NOW, writer);

        int lastPerson = writer.lastIndexOfKind("person");
        int firstIdentity = writer.firstIndexOfKind("identity");
        assertTrue(lastPerson >= 0 && firstIdentity >= 0, "the fixture must write both kinds of row");
        assertTrue(lastPerson < firstIdentity,
                "an identity insert queued before a person insert is error 1452 on fk_account_person_identity, "
                        + "and it costs the client's whole rebuild");
        assertTrue(writer.hasFlushBetween(lastPerson, firstIdentity),
                "queuing them in the right order is not enough — order_inserts re-groups the queue, "
                        + "so the people have to be FLUSHED before the identities are queued at all");
    }

    // ------------------------------------------------------------------------
    // The unique key: a freed name key reaches the database before it is taken
    // ------------------------------------------------------------------------

    /**
     * The production shape: a client's Exchange starts writing a colleague's married name, so
     * the row that held {@code anne|hansen} moves to {@code anne|berg} — and the same night a
     * second mailbox at the client turns out to be a different Anne Hansen, who needs a new row
     * under the key the first one just left. The vacating UPDATE and the taking INSERT are two
     * statements against one unique index, and Hibernate emits inserts first.
     */
    @Test
    @DisplayName("A draft that takes a freed name key is written after a flush, never before")
    void aDraftThatTakesAFreedNameKeyIsWrittenAfterAFlush() {
        AccountPerson existing = person("p-anne", "anne|hansen", "Anne Hansen", "CALENDAR", LAST_YEAR, YESTERDAY);
        Registry registry = Registry.of(List.of(existing), List.of(
                identity("i-mail", "p-anne", AccountPersonIdentityKind.EMAIL, "anne.hansen@dagrofa.dk"),
                identity("i-name", "p-anne", AccountPersonIdentityKind.NAME, "anne|hansen")));
        RecordingWriter writer = new RecordingWriter();

        RebuildSummary summary = AccountPersonService.applyDrafts(CLIENT,
                drafts(calendar("Anne Hansen Berg", "anne.hansen@dagrofa.dk"),
                        calendar("Anne Hansen", "a.hansen@dagrofa.dk")),
                ColleagueIndex.empty(), Set.of(), registry, NOW, writer);

        assertEquals(2, summary.peopleUpserted(), "the same mailbox renamed, and a second person who is not her");
        assertEquals("anne|berg", existing.getNameKey(), "the existing row is the one that moved");

        int vacating = writer.indexOfPerson("p-anne");
        int taking = writer.lastIndexOfKind("person");
        assertTrue(vacating < taking, "the fixture must write the vacating row first");
        assertTrue(writer.hasFlushBetween(vacating, taking),
                "without a flush the INSERT that takes anne|hansen runs while the old row still holds it — "
                        + "error 1062 on uq_account_person_client_key, and the client's rebuild is lost");
        assertFalse(existing.isRetired(), "a row that merely changed key has not merged into anybody");
    }

    // ------------------------------------------------------------------------
    // D8: two rows that turn out to be one human
    // ------------------------------------------------------------------------

    /**
     * {@code "Sif S. Broby Madsen"} from a calendar and {@code "Sif Broby Madsen"} from
     * TrustLink were two rows until the address that bridges them arrived. One row survives —
     * the one a claim or a star may already point at — and the other has to stop being drawn,
     * without being deleted.
     */
    @Test
    @DisplayName("The loser of a merge is retired, not deleted, and not stamped as seen")
    void theLoserOfAMergeIsRetiredNotDeleted() {
        AccountPerson survivor = person("p-survivor", "sif|madsen", "Sif S. Broby Madsen",
                "CALENDAR", LAST_YEAR, YESTERDAY);
        AccountPerson loser = person("p-loser", "sif|broby", "Sif Broby", "TRUSTLINK", LAST_YEAR, YESTERDAY);
        AccountPersonIdentity stale =
                identity("i-old", "p-loser", AccountPersonIdentityKind.EMAIL, "sif.old@dsb.dk");
        Registry registry = Registry.of(List.of(survivor, loser), List.of(
                identity("i-name", "p-survivor", AccountPersonIdentityKind.NAME, "sif|madsen"),
                identity("i-mail", "p-loser", AccountPersonIdentityKind.EMAIL, "sif@dsb.dk"),
                stale));
        RecordingWriter writer = new RecordingWriter();

        RebuildSummary summary = AccountPersonService.applyDrafts(CLIENT,
                drafts(calendar("Sif Broby Madsen", "sif@dsb.dk")),
                ColleagueIndex.empty(), Set.of(), registry, NOW, writer);

        assertEquals(1, summary.peopleUpserted(), "one human, so one person was upserted — the loser is not one");

        assertTrue(loser.isRetired(), "a row nothing backs any more must stop being drawn as a person");
        assertEquals(AccountPerson.RETIRED_SOURCES, loser.getSources());
        assertEquals(YESTERDAY, loser.getLastSeenAt(),
                "last_seen_at must NOT move: the loser was last seen when it was last seen, and moving it "
                        + "forward tells the retention purge this row is current");
        assertEquals("sif|broby", loser.getNameKey(),
                "the key stays — the row is retired, not rewritten, so nothing collides with it");
        assertNotNull(loser.getUuid(), "the uuid survives, because a claim or a stakeholder may point at it");

        assertFalse(survivor.isRetired());
        assertEquals(NOW, survivor.getLastSeenAt());
        assertEquals("CALENDAR", survivor.getSources());

        assertEquals("p-survivor", stale.getPersonUuid(),
                "an address the sources have gone quiet about follows its person to the surviving row — "
                        + "left behind it resolves to a row the page no longer draws, and the edge silently vanishes");
        assertTrue(writer.hasFlushBetween(writer.indexOfPerson("p-survivor"), writer.indexOfIdentity("i-old")),
                "re-pointing an identity writes a person_uuid the FK has to be able to see");
    }

    /**
     * The other direction, which is the one that would quietly delete real people from the
     * page: a row that a draft did not merge with must keep its sources whatever else the run
     * touches.
     */
    @Test
    @DisplayName("A person nobody merged with keeps their sources")
    void aPersonNobodyMergedWithKeepsTheirSources() {
        AccountPerson untouched = person("p-ole", "ole|brandt", "Ole Brandt", "TRUSTLINK", LAST_YEAR, YESTERDAY);
        Registry registry = Registry.of(List.of(untouched), List.of(
                identity("i-ole", "p-ole", AccountPersonIdentityKind.NAME, "ole|brandt")));

        AccountPersonService.applyDrafts(CLIENT, drafts(calendar("Mette Brandt", "mette@dsb.dk")),
                ColleagueIndex.empty(), Set.of(), registry, NOW, new RecordingWriter());

        assertFalse(untouched.isRetired(), "a different person at the same client is not a merge loser");
        assertEquals("TRUSTLINK", untouched.getSources());
        assertEquals(YESTERDAY, untouched.getLastSeenAt(), "a row no source backed tonight is not touched at all");
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    /** Every write the rebuild asked for, in the order it asked — nothing else. */
    private static final class RecordingWriter implements RegistryWriter {

        private final List<String> writes = new ArrayList<>();

        @Override
        public void write(AccountPerson person) {
            writes.add("person:" + person.getUuid());
        }

        @Override
        public void write(AccountPersonIdentity identity) {
            writes.add("identity:" + identity.getUuid());
        }

        @Override
        public void flush() {
            writes.add("flush");
        }

        int firstIndexOfKind(String kind) {
            for (int i = 0; i < writes.size(); i++) {
                if (writes.get(i).startsWith(kind + ":")) {
                    return i;
                }
            }
            return -1;
        }

        int lastIndexOfKind(String kind) {
            for (int i = writes.size() - 1; i >= 0; i--) {
                if (writes.get(i).startsWith(kind + ":")) {
                    return i;
                }
            }
            return -1;
        }

        int indexOfPerson(String uuid) {
            return writes.indexOf("person:" + uuid);
        }

        int indexOfIdentity(String uuid) {
            return writes.indexOf("identity:" + uuid);
        }

        boolean hasFlushBetween(int from, int to) {
            if (from < 0 || to < 0) {
                return false;
            }
            for (int i = from + 1; i < to; i++) {
                if ("flush".equals(writes.get(i))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static List<PersonDraft> drafts(Sighting... sightings) {
        return AccountPersonService.merge(List.of(sightings));
    }

    private static Sighting calendar(String displayName, String email) {
        Sighting sighting = AccountPersonService.sighting(
                AccountPersonSource.CALENDAR, displayName, email, null, null, null);
        assertNotNull(sighting, "the fixture must produce a sighting");
        return sighting;
    }

    private static AccountPerson person(String uuid, String nameKey, String name, String sources,
                                        LocalDateTime firstSeen, LocalDateTime lastSeen) {
        AccountPerson person = new AccountPerson();
        person.setUuid(uuid);
        person.setClientUuid(CLIENT);
        person.setName(name);
        person.setNameKey(nameKey);
        person.setKind(AccountPersonKind.CONTACT);
        person.setSources(sources);
        person.setFirstSeenAt(firstSeen);
        person.setLastSeenAt(lastSeen);
        return person;
    }

    private static AccountPersonIdentity identity(String uuid, String personUuid,
                                                  AccountPersonIdentityKind kind, String value) {
        AccountPersonIdentity identity = new AccountPersonIdentity();
        identity.setUuid(uuid);
        identity.setPersonUuid(personUuid);
        identity.setClientUuid(CLIENT);
        identity.setKind(kind);
        identity.setValue(value);
        identity.setFirstSeenAt(LAST_YEAR);
        identity.setLastSeenAt(YESTERDAY);
        return identity;
    }
}
