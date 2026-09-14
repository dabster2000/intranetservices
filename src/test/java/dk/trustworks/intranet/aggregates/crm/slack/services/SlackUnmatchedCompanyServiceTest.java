package dk.trustworks.intranet.aggregates.crm.slack.services;

import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarSuggestionService;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackSuggestionDecisionRequest;
import dk.trustworks.intranet.aggregates.crm.slack.model.SlackUnmatchedCompany;
import dk.trustworks.intranet.aggregates.crm.slack.model.SlackUnmatchedCompanySighting;
import dk.trustworks.intranet.aggregates.crm.slack.model.SlackUnmatchedCompanySightingAuthor;
import dk.trustworks.intranet.aggregates.crm.slack.model.enums.UnmatchedCompanyStatus;
import dk.trustworks.intranet.aggregates.crm.slack.services.SlackUnmatchedCompanyService.UnmatchedCompanySighting;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * "Heard in Slack" — the rules that make "3 mentions since June · Tommy, Lukas" a number
 * worth acting on (spec §4.3, §4.5, §5.3).
 *
 * <p>The lane re-reads a lookback window every night, so almost everything here is really
 * one question asked from four sides: <b>does reading the same day twice change anything?</b>
 * The key must normalise identically both times, the sighting must land on the same row both
 * times, the aggregates must be recomputed rather than nudged, and a colleague who wrote
 * about the same company in two channels must still count once. Get any of those wrong and
 * the panel reports one conversation as a fortnight of them — which is the failure V601 was
 * written to avoid and this lane inherits wholesale.
 *
 * <p>The other half is the decision. A decision is the only thing on these rows a human
 * made, so it is the only thing a run must never overwrite, and LINK is not a way of closing
 * a row: it is how the matcher LEARNS the spelling (D4). Both are locked below.
 *
 * <p><b>Fast tier — no Quarkus boot, no OpenAI, no database.</b> The service's Panache calls
 * go through {@code mockStatic(PanacheEntityBase.class)} onto a {@link #stored} map that
 * plays the part of the two tables, which is what lets a test run the same nightly write
 * twice and look at what the second one did. The native aggregate queries are mocked at the
 * {@link EntityManager}: what SQL they are is asserted, what MariaDB does with them is not
 * — see the notes on each test.
 */
class SlackUnmatchedCompanyServiceTest {

    private static final String NAME_KEY = "novo nordisk";
    private static final String DISPLAY = "Novo Nordisk";
    private static final String CHANNEL = "C0SALESCHAT";
    private static final String OTHER_CHANNEL = "C0LEDELSEN";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 14);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 15, 2, 25);
    private static final String ACTOR = "e1a8f3d2-2f3b-4a9d-9d64-0d2d0d0b1aa1";
    private static final String TOMMY = "1e0a2c44-1111-4444-8888-aaaaaaaaaaaa";
    private static final String LUKAS = "2e0a2c44-2222-4444-8888-bbbbbbbbbbbb";

    private SlackUnmatchedCompanyService service;
    private EntityManager em;
    private ClientService clientService;
    private CalendarSuggestionService prospects;

    /**
     * The two tables, as far as {@code findById} is concerned. Company rows are keyed by
     * their name key and sightings by their uuid, and those two key spaces never collide,
     * so one map is enough to let a test replay a night and then replay it again.
     */
    private final Map<String, Object> stored = new HashMap<>();

    /** Every row the service constructed, in order, and what each looked like at persist. */
    private final List<SlackUnmatchedCompany> companiesBuilt = new ArrayList<>();
    private final List<SlackUnmatchedCompanySighting> sightingsBuilt = new ArrayList<>();
    private final List<CompanyRow> companiesPersisted = new ArrayList<>();
    private final List<SightingRow> sightingsPersisted = new ArrayList<>();
    private final List<AuthorRow> authorsPersisted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new SlackUnmatchedCompanyService();
        service.em = em = mock(EntityManager.class);
        service.clientService = clientService = mock(ClientService.class);
        service.prospects = prospects = mock(CalendarSuggestionService.class);
        stored.clear();
        companiesBuilt.clear();
        sightingsBuilt.clear();
        companiesPersisted.clear();
        sightingsPersisted.clear();
        authorsPersisted.clear();
    }

    // ------------------------------------------------------------------------
    // The key: two spellings of one company are one company
    // ------------------------------------------------------------------------

    @Test
    void everySpellingOfOneNameCollapsesToOneKey() {
        assertEquals(NAME_KEY, SlackUnmatchedCompanyService.nameKey("Novo Nordisk"));
        assertEquals(NAME_KEY, SlackUnmatchedCompanyService.nameKey("novo nordisk"));
        assertEquals(NAME_KEY, SlackUnmatchedCompanyService.nameKey("  Novo   Nordisk  "));
        assertEquals(NAME_KEY, SlackUnmatchedCompanyService.nameKey("Novo\tNordisk"));
        assertEquals(NAME_KEY, SlackUnmatchedCompanyService.nameKey("Novo\nNordisk"));
        assertEquals("", SlackUnmatchedCompanyService.nameKey(null));
        assertEquals("", SlackUnmatchedCompanyService.nameKey("   "));
    }

    /**
     * The key is computed in two places — here and in the extractor that writes the
     * sightings. They have to agree on every input or a hint is stored under one spelling
     * and looked up under another, and nobody can ever decide it.
     */
    @Test
    void theExtractorAndThePanelComputeTheSameKey() {
        for (String raw : Arrays.asList("Novo Nordisk", "  NOVO   nordisk ", "NN", "Tryg A/S",
                "Nexi\tGroup", "E-Nettet", "", "   ")) {
            assertEquals(SlackMentionExtractionService.nameKey(raw),
                    SlackUnmatchedCompanyService.nameKey(raw),
                    "the two implementations of the key must agree on " + raw);
        }
    }

    @Test
    void theDisplayNameKeepsTheCasingItWasHeardIn() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<SlackUnmatchedCompany> companies = interceptCompanies();
             MockedConstruction<SlackUnmatchedCompanySighting> sightings = interceptSightings();
             MockedConstruction<SlackUnmatchedCompanySightingAuthor> authors = interceptAuthors()) {
            stubFindByIdFromStore(panache);

            service.record(List.of(sighting("  Novo   Nordisk ", 1, TOMMY)), NOW);

            assertEquals(1, companiesPersisted.size());
            CompanyRow company = companiesPersisted.get(0);
            assertEquals(NAME_KEY, company.nameKey(), "the identity is the normalised key");
            assertEquals("Novo   Nordisk", company.displayName(),
                    "the spelling a human reads is the one that was heard, not the key");
            assertEquals(UnmatchedCompanyStatus.NEW, company.status());
            assertEquals(NOW, company.createdAt());
            assertEquals(NOW, company.updatedAt());
        }
    }

    // ------------------------------------------------------------------------
    // The sighting uuid: the same channel-day tomorrow is the same ROW
    // ------------------------------------------------------------------------

    @Test
    void theSameNameChannelAndDayAlwaysProduceTheSameUuid() {
        String first = SlackUnmatchedCompanyService.sightingUuid(NAME_KEY, CHANNEL, DAY);
        String again = SlackUnmatchedCompanyService.sightingUuid(NAME_KEY, CHANNEL, DAY);
        assertEquals(first, again);
        assertEquals(36, first.length(), "must be a uuid the CHAR(36) column accepts: " + first);
        assertTrue(first.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "must be 8-4-4-4-12 hex: " + first);
    }

    @Test
    void changingAnyPartOfTheKeyProducesADifferentRow() {
        String base = SlackUnmatchedCompanyService.sightingUuid(NAME_KEY, CHANNEL, DAY);
        // The same company in two channels: two rows, which is what channels_count counts.
        assertNotEquals(base, SlackUnmatchedCompanyService.sightingUuid(NAME_KEY, OTHER_CHANNEL, DAY));
        // The same channel-day, another company: two rows.
        assertNotEquals(base, SlackUnmatchedCompanyService.sightingUuid("tryg", CHANNEL, DAY));
        // Tomorrow: a new row, and that is how mentions accrue at all.
        assertNotEquals(base, SlackUnmatchedCompanyService.sightingUuid(NAME_KEY, CHANNEL, DAY.plusDays(1)));
    }

    // ------------------------------------------------------------------------
    // The ledger: reading the same day twice writes the same row twice
    // ------------------------------------------------------------------------

    @Test
    void reReadingADayOverwritesItsOwnRowInsteadOfAddingOne() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<SlackUnmatchedCompany> companies = interceptCompanies();
             MockedConstruction<SlackUnmatchedCompanySighting> sightings = interceptSightings();
             MockedConstruction<SlackUnmatchedCompanySightingAuthor> authors = interceptAuthors()) {
            stubFindByIdFromStore(panache);

            service.record(List.of(sighting(DISPLAY, 3, TOMMY)), NOW);
            service.record(List.of(sighting(DISPLAY, 3, TOMMY)), NOW.plusDays(1));

            assertEquals(1, sightingsBuilt.size(),
                    "the second read must find its own row, not insert a second one");
            assertEquals(1, companiesBuilt.size(), "and must not re-create the parent row");
            assertEquals(2, sightingsPersisted.size(), "both reads wrote — to the same row");
            assertEquals(List.of(sightingUuid(), sightingUuid()),
                    sightingsPersisted.stream().map(SightingRow::uuid).toList());
            assertEquals(3, sightingsPersisted.get(1).mentionCount(),
                    "mention_count is SET from the read, never added to — six would be one "
                            + "conversation counted twice");
            assertEquals(1, sightingsPersisted.get(1).authorCount());
        }
    }

    @Test
    void aDecidedNameKeepsAccruingSightingsAndKeepsItsDecision() {
        SlackUnmatchedCompany ignored = company(NAME_KEY, DISPLAY, UnmatchedCompanyStatus.IGNORED);
        stored.put(NAME_KEY, ignored);

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<SlackUnmatchedCompany> companies = interceptCompanies();
             MockedConstruction<SlackUnmatchedCompanySighting> sightings = interceptSightings();
             MockedConstruction<SlackUnmatchedCompanySightingAuthor> authors = interceptAuthors()) {
            stubFindByIdFromStore(panache);

            service.record(List.of(sighting("NOVO NORDISK", 2, TOMMY)), NOW);

            assertTrue(companiesBuilt.isEmpty(), "an existing row is never rebuilt");
            assertEquals(UnmatchedCompanyStatus.IGNORED, ignored.getStatus(),
                    "an IGNORE is a deny-list, not a deletion — the run must not undo it");
            assertEquals(DISPLAY, ignored.getDisplayName(),
                    "and the spelling first heard is the one the panel keeps showing");
            assertEquals(1, sightingsPersisted.size(),
                    "the sightings keep accruing quietly so the decision stays reversible");
        }
    }

    @Test
    void theAuthorsOfADayAreReplacedWholesaleAndDeduplicated() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<SlackUnmatchedCompany> companies = interceptCompanies();
             MockedConstruction<SlackUnmatchedCompanySighting> sightings = interceptSightings();
             MockedConstruction<SlackUnmatchedCompanySightingAuthor> authors = interceptAuthors()) {
            stubFindByIdFromStore(panache);

            service.record(List.of(new UnmatchedCompanySighting(NAME_KEY, DISPLAY, CHANNEL, DAY, 4,
                    Arrays.asList(TOMMY, "  " + TOMMY + "  ", null, "", LUKAS), null)), NOW);

            panache.verify(() -> PanacheEntityBase.delete("sightingUuid", sightingUuid()), times(1));
            assertEquals(2, authorsPersisted.size(), "the same colleague twice is one author row");
            assertEquals(List.of(TOMMY, LUKAS),
                    authorsPersisted.stream().map(AuthorRow::userUuid).toList());
            assertEquals(List.of(sightingUuid(), sightingUuid()),
                    authorsPersisted.stream().map(AuthorRow::sightingUuid).toList());
            assertEquals(2, sightingsPersisted.get(0).authorCount());
            for (AuthorRow author : authorsPersisted) {
                assertNotNull(author.uuid(), "uuid is the assigned id — set before persist()");
            }
        }
    }

    @Test
    void aHalfSightingIsDroppedRatherThanStored() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<SlackUnmatchedCompany> companies = interceptCompanies();
             MockedConstruction<SlackUnmatchedCompanySighting> sightings = interceptSightings();
             MockedConstruction<SlackUnmatchedCompanySightingAuthor> authors = interceptAuthors()) {
            stubFindByIdFromStore(panache);

            service.record(null, NOW);
            service.record(List.of(), NOW);
            service.record(Arrays.asList(
                    new UnmatchedCompanySighting(null, DISPLAY, CHANNEL, DAY, 1, List.of(TOMMY), null),
                    new UnmatchedCompanySighting("   ", DISPLAY, CHANNEL, DAY, 1, List.of(TOMMY), null),
                    new UnmatchedCompanySighting(NAME_KEY, DISPLAY, null, DAY, 1, List.of(TOMMY), null),
                    new UnmatchedCompanySighting(NAME_KEY, DISPLAY, CHANNEL, null, 1, List.of(TOMMY), null),
                    // Longer than the primary key. Dropped here rather than left to abort the
                    // whole day's transaction on a "data too long".
                    new UnmatchedCompanySighting("x".repeat(191), DISPLAY, CHANNEL, DAY, 1,
                            List.of(TOMMY), null)), NOW);

            assertTrue(companiesPersisted.isEmpty());
            assertTrue(sightingsPersisted.isEmpty());
            assertTrue(authorsPersisted.isEmpty());
        }
    }

    @Test
    void aNegativeMentionCountIsFlooredAtZero() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<SlackUnmatchedCompany> companies = interceptCompanies();
             MockedConstruction<SlackUnmatchedCompanySighting> sightings = interceptSightings();
             MockedConstruction<SlackUnmatchedCompanySightingAuthor> authors = interceptAuthors()) {
            stubFindByIdFromStore(panache);

            service.record(List.of(sighting(DISPLAY, -3, TOMMY)), NOW);

            assertEquals(0, sightingsPersisted.get(0).mentionCount(),
                    "mention_count is NOT NULL and a count — never below zero");
        }
    }

    // ------------------------------------------------------------------------
    // The aggregates: recomputed from the ledger, never incremented
    // ------------------------------------------------------------------------

    @Test
    void theCountsAreRecomputedFromTheLedgerAndNotAddedTo() {
        SlackUnmatchedCompany existing = company(NAME_KEY, DISPLAY, UnmatchedCompanyStatus.NEW);
        existing.setMentionsTotal(99);
        existing.setMentions90d(99);
        existing.setPeopleCount(99);
        existing.setChannelsCount(99);
        stored.put(NAME_KEY, existing);
        ledgerReturns(List.<Object[]>of(aggregateRow(NAME_KEY, 3, 2, 1, "2026-06-02", "2026-09-14")),
                List.<Object[]>of(colleagueRow(NAME_KEY, 1)));

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<SlackUnmatchedCompany> companies = interceptCompanies()) {
            stubFindByIdFromStore(panache);
            panache.when(() -> PanacheEntityBase.listAll()).thenReturn(List.of(existing));

            service.refreshAggregates();

            assertTrue(companiesBuilt.isEmpty(), "a name that already has a row keeps it");
            assertEquals(3, existing.getMentionsTotal(), "recomputed — not 99 + 3");
            assertEquals(2, existing.getMentions90d());
            assertEquals(1, existing.getChannelsCount());
            assertEquals(LocalDate.of(2026, 6, 2), existing.getFirstSeen());
            assertEquals(DAY, existing.getLastSeen());
            verify(existing).persist();
            verify(existing, never()).delete();
        }
    }

    /**
     * The reason there are two queries and not one. Joining the authors into the aggregate
     * query fans each sighting out into a row per colleague, and summing {@code mention_count}
     * over that multiplies a day's mentions by the number of people who wrote them — so the
     * distinct-colleague count is asked for on its own, and this asserts the service uses
     * that answer rather than anything it could have summed (BRIEF R1).
     */
    @Test
    void peopleCountIsDistinctColleaguesAcrossSightingsNotASumOfAuthorCounts() {
        SlackUnmatchedCompany existing = company(NAME_KEY, DISPLAY, UnmatchedCompanyStatus.NEW);
        stored.put(NAME_KEY, existing);
        // Tommy wrote about them in two different channels: two sightings, author_count 1
        // each, four mentions between them — and ONE colleague.
        ledgerReturns(List.<Object[]>of(aggregateRow(NAME_KEY, 4, 4, 2, "2026-09-10", "2026-09-14")),
                List.<Object[]>of(colleagueRow(NAME_KEY, 1)));

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<SlackUnmatchedCompany> companies = interceptCompanies()) {
            stubFindByIdFromStore(panache);
            panache.when(() -> PanacheEntityBase.listAll()).thenReturn(List.of(existing));

            service.refreshAggregates();

            assertEquals(1, existing.getPeopleCount(),
                    "the same colleague in two channels is one person, not two");
            assertEquals(2, existing.getChannelsCount());
        }

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em, times(2)).createNativeQuery(sql.capture());
        assertTrue(sql.getAllValues().stream()
                        .anyMatch(q -> q.contains("count(distinct a.user_uuid)")
                                && q.contains("slack_unmatched_company_sighting_author")),
                "people_count must come from a distinct count over the author rows");
        assertTrue(sql.getAllValues().stream().noneMatch(q -> q.contains("sum(author_count)")),
                "author_count is a per-sighting number and cannot be summed into people_count");
    }

    @Test
    void aNameWithNoParentRowIsRebuiltWithEveryNotNullColumnSet() {
        ledgerReturns(List.<Object[]>of(aggregateRow("tryg", 2, 2, 1, "2026-09-01", "2026-09-14")),
                List.<Object[]>of(colleagueRow("tryg", 2)));

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<SlackUnmatchedCompany> companies = interceptCompanies()) {
            stubFindByIdFromStore(panache);
            panache.when(() -> PanacheEntityBase.listAll()).thenReturn(List.of());

            service.refreshAggregates();

            assertEquals(1, companiesPersisted.size());
            CompanyRow row = companiesPersisted.get(0);
            assertEquals("tryg", row.nameKey());
            assertEquals("tryg", row.displayName(),
                    "the key is the only spelling left — a lower-case hint beats one that vanished");
            assertEquals(UnmatchedCompanyStatus.NEW, row.status());
            assertNotNull(row.createdAt(), "created_at is NOT NULL — set before persist()");
            // The calendar lane has no such column and so has no write to mirror; unset, the
            // very first insert of this table fails.
            assertNotNull(row.updatedAt(), "updated_at is NOT NULL — set before persist()");
            assertEquals(2, row.peopleCount());
        }
    }

    /**
     * The calendar lane's purge lives inside its recompute rather than in a job, and this
     * one mirrors it — first statement, same window. The order is the point: run it after
     * the sums and the numbers describe rows that are about to be deleted.
     */
    @Test
    void theRetentionPurgeRunsBeforeTheNumbersAreComputed() {
        List<String> operations = new ArrayList<>();
        List<Object> purgeCall = new ArrayList<>();
        ledgerReturns(operations, List.of(), List.of());

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<SlackUnmatchedCompany> companies = interceptCompanies()) {
            stubFindByIdFromStore(panache);
            panache.when(() -> PanacheEntityBase.delete(anyString(), any(LocalDate.class)))
                    .thenAnswer(invocation -> {
                        operations.add("purge");
                        purgeCall.add(invocation.getArgument(0));
                        purgeCall.add(invocation.getArgument(1));
                        return 7L;
                    });
            panache.when(() -> PanacheEntityBase.listAll()).thenReturn(List.of());

            service.refreshAggregates();
        }

        assertEquals(List.of("purge", "aggregates", "colleagues"), operations,
                "the numbers must be computed over what is actually kept");
        assertEquals("sightedOn < ?1", purgeCall.get(0));
        // The authors go with the sightings on the foreign key's cascade, which is why there
        // is one delete here and not two.
        assertEquals(SlackUnmatchedCompanyService.RETENTION_MONTHS,
                ChronoUnit.MONTHS.between((LocalDate) purgeCall.get(1), LocalDate.now()));
    }

    @Test
    void aForgottenNameIsSweptOnlyWhenNobodyEverDecidedAboutIt() {
        SlackUnmatchedCompany undecided = company("a forgotten name", "A Forgotten Name",
                UnmatchedCompanyStatus.NEW);
        SlackUnmatchedCompany ignored = company("ignored name", "Ignored Name",
                UnmatchedCompanyStatus.IGNORED);
        SlackUnmatchedCompany linked = company("linked name", "Linked Name",
                UnmatchedCompanyStatus.LINKED);
        linked.setLinkedClientUuid("client-1");
        ledgerReturns(List.of(), List.of());

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<SlackUnmatchedCompany> companies = interceptCompanies()) {
            stubFindByIdFromStore(panache);
            panache.when(() -> PanacheEntityBase.listAll())
                    .thenReturn(List.of(undecided, ignored, linked));

            service.refreshAggregates();

            verify(undecided).delete();
            verify(ignored, never()).delete();
            verify(linked, never()).delete();
        }
    }

    // ------------------------------------------------------------------------
    // The aliases: a LINKED row is what the matcher learns from
    // ------------------------------------------------------------------------

    @Test
    void onlyLinkedRowsWithAClientBecomeAliases() {
        SlackUnmatchedCompany first = company(NAME_KEY, DISPLAY, UnmatchedCompanyStatus.LINKED);
        first.setLinkedClientUuid("client-novo");
        SlackUnmatchedCompany second = company("nn", "NN", UnmatchedCompanyStatus.LINKED);
        second.setLinkedClientUuid("client-novo");

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            // The status filter is the whole guarantee: a NEW row is a question nobody has
            // answered, and an IGNORED one is an answer of "never" — neither teaches the
            // matcher a spelling, so neither can be reached through this query.
            panache.when(() -> PanacheEntityBase.list("status = ?1 and linkedClientUuid is not null",
                    UnmatchedCompanyStatus.LINKED)).thenReturn(List.of(first, second));

            Map<String, String> aliases = service.linkedAliases();

            assertEquals(2, aliases.size());
            assertEquals("client-novo", aliases.get(NAME_KEY));
            assertEquals("client-novo", aliases.get("nn"));
            assertEquals(List.of(NAME_KEY, "nn"), List.copyOf(aliases.keySet()),
                    "insertion order is kept so the allowlist reads the same way twice");
        }
    }

    @Test
    void noLinkedRowsMeansNoAliasesRatherThanAnEmptyEntry() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("status = ?1 and linkedClientUuid is not null",
                    UnmatchedCompanyStatus.LINKED)).thenReturn(List.of());

            assertTrue(service.linkedAliases().isEmpty());
        }
    }

    // ------------------------------------------------------------------------
    // Decide
    // ------------------------------------------------------------------------

    @Test
    void aDecisionWithoutAnActorIsRefusedBeforeAnythingIsRead() {
        // No Panache mock is open: reaching the lookup at all would blow up on the
        // unenhanced stub, so this also proves the guard comes first.
        assertEquals(400, status(assertThrows(WebApplicationException.class,
                () -> service.decide(NAME_KEY, ignoreRequest(), null))));
        assertEquals(400, status(assertThrows(WebApplicationException.class,
                () -> service.decide(NAME_KEY, ignoreRequest(), "   "))));
    }

    @Test
    void theKeyIsNormalisedBeforeTheRowIsLookedUp() {
        SlackUnmatchedCompany row = company(NAME_KEY, DISPLAY, UnmatchedCompanyStatus.NEW);
        stored.put(NAME_KEY, row);

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFindByIdFromStore(panache);

            service.decide("  Novo   NORDISK  ", ignoreRequest(), ACTOR);

            panache.verify(() -> PanacheEntityBase.findById(NAME_KEY), times(1));
        }
    }

    @Test
    void anUnknownHintIsNotFoundAndTheNameIsNotEchoedBack() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFindByIdFromStore(panache);

            WebApplicationException thrown = assertThrows(WebApplicationException.class,
                    () -> service.decide("Some Company Nobody Stored", ignoreRequest(), ACTOR));

            assertEquals(404, status(thrown));
            assertFalse(String.valueOf(thrown.getMessage()).toLowerCase()
                            .contains("some company nobody stored"),
                    "the key is a company name a model wrote — it stays out of the error");
        }
    }

    @Test
    void aMissingOrUnknownDecisionIsRefused() {
        SlackUnmatchedCompany row = company(NAME_KEY, DISPLAY, UnmatchedCompanyStatus.NEW);
        stored.put(NAME_KEY, row);

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFindByIdFromStore(panache);

            assertEquals(400, status(assertThrows(WebApplicationException.class,
                    () -> service.decide(NAME_KEY, null, ACTOR))));
            assertEquals(400, status(assertThrows(WebApplicationException.class,
                    () -> service.decide(NAME_KEY, request(null, null, null), ACTOR))));
            assertEquals(400, status(assertThrows(WebApplicationException.class,
                    () -> service.decide(NAME_KEY, request("  ", null, null), ACTOR))));
            assertEquals(400, status(assertThrows(WebApplicationException.class,
                    () -> service.decide(NAME_KEY, request("MAYBE", null, null), ACTOR))));
            assertEquals(UnmatchedCompanyStatus.NEW, row.getStatus(), "nothing was decided");
            verify(row, never()).persist();
        }
    }

    @Test
    void linkNeedsAClientAndAClientThatExists() {
        SlackUnmatchedCompany row = company(NAME_KEY, DISPLAY, UnmatchedCompanyStatus.NEW);
        stored.put(NAME_KEY, row);
        when(clientService.findByUuid("client-gone")).thenReturn(null);

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFindByIdFromStore(panache);

            assertEquals(400, status(assertThrows(WebApplicationException.class,
                    () -> service.decide(NAME_KEY, request("LINK", null, null), ACTOR))));
            assertEquals(400, status(assertThrows(WebApplicationException.class,
                    () -> service.decide(NAME_KEY, request("LINK", null, "   "), ACTOR))));
            assertEquals(404, status(assertThrows(WebApplicationException.class,
                    () -> service.decide(NAME_KEY, request("LINK", null, "client-gone"), ACTOR))));
            assertEquals(UnmatchedCompanyStatus.NEW, row.getStatus());
            assertNull(row.getLinkedClientUuid());
        }
    }

    @Test
    void linkRecordsTheAliasAndWhoTookTheDecision() {
        SlackUnmatchedCompany row = company(NAME_KEY, DISPLAY, UnmatchedCompanyStatus.NEW);
        stored.put(NAME_KEY, row);
        when(clientService.findByUuid("client-novo")).thenReturn(client("client-novo"));

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFindByIdFromStore(panache);

            SlackUnmatchedCompany decided =
                    service.decide(NAME_KEY, request("  link  ", null, " client-novo "), ACTOR);

            assertEquals(UnmatchedCompanyStatus.LINKED, decided.getStatus(),
                    "the verb is matched case- and whitespace-insensitively");
            assertEquals("client-novo", decided.getLinkedClientUuid());
            assertEquals(ACTOR, decided.getDecidedBy());
            assertNotNull(decided.getDecidedAt());
            assertNotNull(decided.getUpdatedAt(), "updated_at is NOT NULL on every write");
            verify(row).persist();
            verify(row, never()).delete();
        }
    }

    /**
     * The name guard is the real {@code createProspect}'s, reached through a bare instance of
     * it: the check runs before the service touches a client or the database, and that is
     * the property worth locking — the two doors that create a company must refuse the same
     * things, or the duplicate-name rule has two answers.
     */
    @Test
    void addNeedsACompanyName() {
        SlackUnmatchedCompany row = company(NAME_KEY, DISPLAY, UnmatchedCompanyStatus.NEW);
        stored.put(NAME_KEY, row);
        service.prospects = new CalendarSuggestionService();

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFindByIdFromStore(panache);

            assertEquals(400, status(assertThrows(WebApplicationException.class,
                    () -> service.decide(NAME_KEY, request("ADD", null, null), ACTOR))));
            assertEquals(400, status(assertThrows(WebApplicationException.class,
                    () -> service.decide(NAME_KEY, request("ADD", "  ", null), ACTOR))));
            assertEquals(400, status(assertThrows(WebApplicationException.class,
                    () -> service.decide(NAME_KEY, request("ADD", "N", null), ACTOR))));
            assertEquals(UnmatchedCompanyStatus.NEW, row.getStatus());
        }
    }

    @Test
    void addCreatesTheCompanyAndThenLinksTheHintToIt() {
        SlackUnmatchedCompany row = company(NAME_KEY, DISPLAY, UnmatchedCompanyStatus.NEW);
        stored.put(NAME_KEY, row);
        when(prospects.createProspect(anyString(), any(), any(), anyBoolean(), anyString(), anyString()))
                .thenReturn(client("client-created"));

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFindByIdFromStore(panache);

            SlackUnmatchedCompany decided = service.decide(NAME_KEY,
                    new SlackSuggestionDecisionRequest("ADD", "Novo Nordisk A/S", "PUBLIC",
                            TOMMY, true, null),
                    ACTOR);

            verify(prospects).createProspect("Novo Nordisk A/S", "PUBLIC", TOMMY, true,
                    "Heard in Slack — " + DISPLAY, ACTOR);
            // The name the model read is not the name the company was created under, and the
            // row is the only place that correspondence exists (D4) — so ADD LINKS, it does
            // not close the hint as dealt with.
            assertEquals(UnmatchedCompanyStatus.LINKED, decided.getStatus());
            assertEquals("client-created", decided.getLinkedClientUuid());
            verify(row, never()).delete();
        }
    }

    @Test
    void ignoreIsADenyListEntryAndNotADeletion() {
        SlackUnmatchedCompany row = company(NAME_KEY, DISPLAY, UnmatchedCompanyStatus.LINKED);
        row.setLinkedClientUuid("client-novo");
        stored.put(NAME_KEY, row);

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFindByIdFromStore(panache);

            SlackUnmatchedCompany decided = service.decide(NAME_KEY, ignoreRequest(), ACTOR);

            assertEquals(UnmatchedCompanyStatus.IGNORED, decided.getStatus());
            assertNull(decided.getLinkedClientUuid(), "an ignored name is nobody's alias");
            assertEquals(ACTOR, decided.getDecidedBy());
            verify(row).persist();
            verify(row, never()).delete();
        }
    }

    // ------------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------------

    @Test
    void onlyUndecidedNamesAreSuggestedAndTheLimitIsClamped() {
        @SuppressWarnings("unchecked")
        PanacheQuery<SlackUnmatchedCompany> query = mock(PanacheQuery.class);
        when(query.page(anyInt(), anyInt())).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            // A company three people mentioned last month is a better suggestion than one
            // somebody named nine times two years ago.
            panache.when(() -> PanacheEntityBase.find(
                            "status = ?1 order by mentions90d desc, peopleCount desc, mentionsTotal desc, nameKey",
                            UnmatchedCompanyStatus.NEW))
                    .thenReturn(query);

            assertTrue(service.suggestions(0).isEmpty());
            assertTrue(service.suggestions(-5).isEmpty());
            assertTrue(service.suggestions(10_000).isEmpty());
            assertTrue(service.suggestions(SlackUnmatchedCompanyService.DEFAULT_LIMIT).isEmpty());

            verify(query, times(2)).page(0, 1);
            verify(query).page(0, SlackUnmatchedCompanyService.MAX_LIMIT);
            verify(query).page(0, SlackUnmatchedCompanyService.DEFAULT_LIMIT);
        }
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    /** One row as {@code persist()} saw it — see the class javadoc on why it is a copy. */
    private record CompanyRow(String nameKey, String displayName, UnmatchedCompanyStatus status,
                              int mentionsTotal, int mentions90d, int channelsCount, int peopleCount,
                              LocalDate firstSeen, LocalDate lastSeen,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {

        static CompanyRow of(SlackUnmatchedCompany row) {
            return new CompanyRow(row.getNameKey(), row.getDisplayName(), row.getStatus(),
                    row.getMentionsTotal(), row.getMentions90d(), row.getChannelsCount(),
                    row.getPeopleCount(), row.getFirstSeen(), row.getLastSeen(),
                    row.getCreatedAt(), row.getUpdatedAt());
        }
    }

    private record SightingRow(String uuid, String nameKey, String channelId, LocalDate sightedOn,
                               int mentionCount, int authorCount, String permalink) {

        static SightingRow of(SlackUnmatchedCompanySighting row) {
            return new SightingRow(row.getUuid(), row.getNameKey(), row.getChannelId(),
                    row.getSightedOn(), row.getMentionCount(), row.getAuthorCount(),
                    row.getPermalink());
        }
    }

    private record AuthorRow(String uuid, String sightingUuid, String userUuid) {

        static AuthorRow of(SlackUnmatchedCompanySightingAuthor row) {
            return new AuthorRow(row.getUuid(), row.getSightingUuid(), row.getUserUuid());
        }
    }

    private MockedConstruction<SlackUnmatchedCompany> interceptCompanies() {
        return mockConstruction(SlackUnmatchedCompany.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS),
                (row, context) -> {
                    companiesBuilt.add(row);
                    doAnswer(invocation -> {
                        companiesPersisted.add(CompanyRow.of(row));
                        stored.put(row.getNameKey(), row);
                        return null;
                    }).when(row).persist();
                    doNothing().when(row).delete();
                });
    }

    private MockedConstruction<SlackUnmatchedCompanySighting> interceptSightings() {
        return mockConstruction(SlackUnmatchedCompanySighting.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS),
                (row, context) -> {
                    sightingsBuilt.add(row);
                    doAnswer(invocation -> {
                        sightingsPersisted.add(SightingRow.of(row));
                        stored.put(row.getUuid(), row);
                        return null;
                    }).when(row).persist();
                });
    }

    private MockedConstruction<SlackUnmatchedCompanySightingAuthor> interceptAuthors() {
        return mockConstruction(SlackUnmatchedCompanySightingAuthor.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS),
                (row, context) -> doAnswer(invocation -> {
                    authorsPersisted.add(AuthorRow.of(row));
                    return null;
                }).when(row).persist());
    }

    /** Both tables answer {@code findById} out of {@link #stored}. */
    private void stubFindByIdFromStore(MockedStatic<PanacheEntityBase> panache) {
        panache.when(() -> PanacheEntityBase.findById(anyString()))
                .thenAnswer(invocation -> stored.get(invocation.getArgument(0, String.class)));
    }

    private void ledgerReturns(List<Object[]> aggregates, List<Object[]> colleagues) {
        ledgerReturns(new ArrayList<>(), aggregates, colleagues);
    }

    private void ledgerReturns(List<String> operations, List<Object[]> aggregates,
                               List<Object[]> colleagues) {
        Query aggregateQuery = mock(Query.class);
        Query colleagueQuery = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenAnswer(invocation ->
                invocation.getArgument(0, String.class).contains("count(distinct a.user_uuid)")
                        ? colleagueQuery
                        : aggregateQuery);
        when(aggregateQuery.setParameter(anyString(), any())).thenReturn(aggregateQuery);
        when(aggregateQuery.getResultList()).thenAnswer(invocation -> {
            operations.add("aggregates");
            return aggregates;
        });
        when(colleagueQuery.getResultList()).thenAnswer(invocation -> {
            operations.add("colleagues");
            return colleagues;
        });
    }

    /**
     * One row of the aggregate query, in the types MariaDB actually answers with: the sums
     * arrive as {@link BigDecimal}/{@link BigInteger} and the dates as {@link java.sql.Date}.
     */
    private static Object[] aggregateRow(String nameKey, int total, int last90, int channels,
                                         String firstSeen, String lastSeen) {
        return new Object[]{nameKey, BigDecimal.valueOf(total), BigDecimal.valueOf(last90),
                BigInteger.valueOf(channels), java.sql.Date.valueOf(firstSeen),
                java.sql.Date.valueOf(lastSeen)};
    }

    private static Object[] colleagueRow(String nameKey, int people) {
        return new Object[]{nameKey, BigInteger.valueOf(people)};
    }

    private static UnmatchedCompanySighting sighting(String displayName, int mentions, String author) {
        return new UnmatchedCompanySighting(SlackUnmatchedCompanyService.nameKey(displayName),
                displayName, CHANNEL, DAY, mentions, List.of(author), null);
    }

    private static String sightingUuid() {
        return SlackUnmatchedCompanyService.sightingUuid(NAME_KEY, CHANNEL, DAY);
    }

    /**
     * A spy, because the sweep and the decision call {@code delete()} and {@code persist()}
     * on rows that came out of the database — neither of which exists without one.
     */
    private static SlackUnmatchedCompany company(String nameKey, String displayName,
                                                 UnmatchedCompanyStatus status) {
        SlackUnmatchedCompany row = new SlackUnmatchedCompany();
        row.setNameKey(nameKey);
        row.setDisplayName(displayName);
        row.setStatus(status);
        row.setCreatedAt(LocalDateTime.of(2026, 6, 2, 2, 25));
        row.setUpdatedAt(LocalDateTime.of(2026, 6, 2, 2, 25));
        SlackUnmatchedCompany spy = spy(row);
        doNothing().when(spy).persist();
        doNothing().when(spy).delete();
        return spy;
    }

    private static Client client(String uuid) {
        Client client = new Client();
        client.setUuid(uuid);
        return client;
    }

    private static SlackSuggestionDecisionRequest ignoreRequest() {
        return request("IGNORE", null, null);
    }

    private static SlackSuggestionDecisionRequest request(String decision, String name,
                                                          String clientUuid) {
        return new SlackSuggestionDecisionRequest(decision, name, null, null, false, clientUuid);
    }

    private static int status(WebApplicationException thrown) {
        return thrown.getResponse().getStatus();
    }
}
