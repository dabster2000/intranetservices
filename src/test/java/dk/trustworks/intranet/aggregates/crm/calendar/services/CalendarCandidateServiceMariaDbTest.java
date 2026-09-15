package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarCandidateService.*;
import dk.trustworks.intranet.aggregates.crm.person.model.AccountPerson;
import dk.trustworks.intranet.aggregates.crm.calendar.model.CalendarUnmatchedMeeting;
import dk.trustworks.intranet.aggregates.crm.person.model.AccountPersonIdentity;
import dk.trustworks.intranet.aggregates.crm.plan.services.AccountPlanService;
import dk.trustworks.intranet.dao.crm.model.Client;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.WebApplicationException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real migration, native candidate queries and registry entities against isolated MariaDB. */
@Tag("calendar-mariadb")
@EnabledIfEnvironmentVariable(named = "CRM_CALENDAR_IT_JDBC_URL", matches = ".+")
class CalendarCandidateServiceMariaDbTest {
    static SessionFactory factory;
    static final LocalDateTime FROM = LocalDateTime.of(2025, 9, 15, 0, 0);
    static final LocalDateTime TO = FROM.plusYears(1);
    static String url;
    static Set<String> enabled;
    static AccountPlanService plan;

    @BeforeAll static void database() throws Exception {
        String base = System.getenv("CRM_CALENDAR_IT_JDBC_URL");
        if (!base.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/crm_calendar_test(?:_[a-z0-9]+)?")) {
            throw new IllegalArgumentException("A disposable localhost crm_calendar_test database is required");
        }
        url = base + "_evidence";
        String schema = url.substring(url.lastIndexOf('/') + 1);
        String password = System.getenv().getOrDefault("CRM_CALENDAR_IT_PASSWORD", "calendar-test-only");
        try (var connection = DriverManager.getConnection(base, "root", password); var sql = connection.createStatement()) {
            sql.execute("create database if not exists `" + schema + "` character set utf8mb4 collate utf8mb4_general_ci");
        }
        Configuration config = new Configuration().addAnnotatedClass(AccountPerson.class).addAnnotatedClass(AccountPersonIdentity.class).addAnnotatedClass(CalendarUnmatchedMeeting.class);
        config.setProperty("hibernate.connection.driver_class", "org.mariadb.jdbc.Driver");
        config.setProperty("hibernate.connection.url", url);
        config.setProperty("hibernate.connection.username", "root");
        config.setProperty("hibernate.connection.password", password);
        config.setProperty("hibernate.hbm2ddl.auto", "none");
        config.setProperty("hibernate.show_sql", "false");
        config.setProperty("hibernate.connection.pool_size", "2");
        config.setProperty("hibernate.order_inserts", "true");
        factory = config.buildSessionFactory();
        try (var connection = DriverManager.getConnection(url, "root", password); var sql = connection.createStatement()) {
            sql.execute("drop table if exists account_person_identity,account_person,account_calendar_candidate,account_calendar_review,client_plan_stakeholder,calendar_unmatched_meeting");
            // Extract real V604 CREATEs; line-end terminators avoid semicolons inside COMMENT strings.
            String registry = Files.readString(Path.of("src/main/resources/db/migration/V604__Account_person_registry.sql"));
            for (String name : List.of("account_person", "account_person_identity")) {
                int start = registry.indexOf("CREATE TABLE IF NOT EXISTS " + name + "\n");
                int end = registry.indexOf(";\n", start);
                sql.execute(registry.substring(start, end));
            }
            sql.execute("create table calendar_unmatched_meeting (uuid char(36) primary key,domain varchar(190),user_uuid char(36),occurred_on date,synced_at datetime)");
            sql.execute(Files.readString(Path.of("src/main/resources/db/migration/V624__Calendar_suggestion_event_identity.sql")));
            sql.execute("create table client_plan_stakeholder (client_uuid char(36), person_uuid char(36))");
            String migration = Files.readString(Path.of("src/main/resources/db/migration/V621__CRM_calendar_candidate_review.sql"));
            for (String statement : migration.split(";")) if (!statement.isBlank()) sql.execute(statement);
        }
    }

    @AfterAll static void close() { if (factory != null) factory.close(); }

    @BeforeEach void reset() {
        enabled = Set.of("user", "other");
        plan = mock(AccountPlanService.class);
        tx(service -> {
            for (String table : List.of("account_calendar_candidate", "account_calendar_review",
                    "account_person_identity", "account_person", "client_plan_stakeholder", "calendar_unmatched_meeting")) {
                service.em.createNativeQuery("delete from " + table).executeUpdate();
            }
            return null;
        });
    }

    @Test void fullReplayIsIdempotentAndDoesNotLoseFractionalTimestampWrites() {
        var pending = event("client", "user", "event", "ical", "person@client.example", "RECURRING");
        tx(service -> { service.record("user", 1, List.of(pending), FROM, TO, true); return null; });
        tx(service -> { service.record("user", 2, List.of(pending), FROM, TO, true); return null; });
        assertEquals(1L, count("account_calendar_candidate"));
        var rows = tx(service -> service.forClient("client"));
        assertEquals(1, rows.size());
        assertEquals(1, rows.getFirst().occurrences());
    }

    @Test void twoMailboxCopiesAreOneOccurrenceAndTwoEvidenceMailboxes() {
        tx(service -> {
            service.record("user", 1, List.of(event("client", "user", "a", "shared", "person@client.example", "RECURRING")), FROM, TO, true);
            service.record("other", 1, List.of(event("client", "other", "b", "shared", "person@client.example", "RECURRING")), FROM, TO, true);
            return null;
        });
        var row = tx(service -> service.forClient("client")).getFirst();
        assertEquals(1, row.occurrences());
        assertEquals(2, row.mailboxCount());
        assertEquals(2, row.seriesCount(), "series ids remain mailbox-specific");
        enabled = Set.of("user");
        assertEquals(1, tx(service -> service.forClient("client")).getFirst().mailboxCount());
        enabled = Set.of();
        assertTrue(tx(service -> service.forClient("client")).isEmpty());
    }

    @Test void incompleteReadKeepsOldCandidatesCompleteReadReconcilesOnlyItsWindow() {
        tx(service -> {
            service.record("user", 1, List.of(event("client", "user", "a", "a", "one@client.example", "RECURRING")), FROM, TO, true);
            service.record("other", 1, List.of(event("client", "other", "b", "b", "two@client.example", "DELIVERY")), FROM, TO, true);
            return null;
        });
        tx(service -> { service.record("user", 2, List.of(), FROM, TO, false); return null; });
        assertEquals(2L, count("account_calendar_candidate"));
        tx(service -> { service.record("user", 3, List.of(), TO.minusDays(1), TO, true); return null; });
        assertEquals(2L, count("account_calendar_candidate"));
        tx(service -> { service.record("user", 4, List.of(), FROM, TO, true); return null; });
        assertEquals(1L, count("account_calendar_candidate"));
    }

    @Test void starCreatesOnlyReviewedIdentityAndAnExplicitPlanStar() {
        String uuid = seed("RECURRING", "person@client.example");
        ReviewDTO result = tx(service -> service.review("client", uuid, new ReviewRequest("STAR"), "actor"));
        assertEquals("STARRED", result.status());
        assertNotNull(result.personUuid());
        assertEquals(1L, count("account_person"));
        assertEquals(1L, count("account_person_identity"));
        assertEquals(1L, count("account_calendar_review"));
        verify(plan).addStakeholder(eq("client"), argThat(request -> result.personUuid().equals(request.personUuid())), eq("actor"));
        assertTrue(tx(service -> service.forClient("client")).isEmpty());
        tx(service -> { service.record("user", 2, List.of(event("client", "user", "new", "new", "person@client.example", "DELIVERY")), FROM, TO, false); return null; });
        assertTrue(tx(service -> service.forClient("client")).isEmpty(), "review survives later event copies");
        assertEquals(result, tx(service -> service.review("client", uuid, new ReviewRequest("STAR"), "actor")));
        verifyNoMoreInteractions(plan);
    }

    @Test void failedPlanWriteRollsBackBothRegistryAndReview() {
        String uuid = seed("DELIVERY", "person@client.example");
        when(plan.addStakeholder(anyString(), any(), anyString())).thenThrow(new IllegalStateException("synthetic plan failure"));
        assertThrows(IllegalStateException.class, () -> tx(service -> service.review("client", uuid, new ReviewRequest("STAR"), "actor")));
        assertEquals(0L, count("account_person"));
        assertEquals(0L, count("account_person_identity"));
        assertEquals(0L, count("account_calendar_review"));
        assertEquals(1, tx(service -> service.forClient("client")).size());
    }

    @Test void sharedAddressMayBeDismissedButNeverCreatesPersonOrStar() {
        String uuid = seed("SHARED_ADDRESS", "sg.it@client.example");
        assertEquals(409, assertThrows(WebApplicationException.class,
                () -> tx(service -> service.review("client", uuid, new ReviewRequest("STAR"), "actor"))).getResponse().getStatus());
        assertEquals("DISMISSED", tx(service -> service.review("client", uuid, new ReviewRequest("DISMISS"), "actor")).status());
        assertEquals(0L, count("account_person"));
        assertEquals(0L, count("account_person_identity"));
        verifyNoInteractions(plan);
    }

    @Test void crossAccountAndRevokedConsentReviewsAreRejected() {
        String uuid = seed("DELIVERY", "person@client.example");
        assertEquals(404, assertThrows(WebApplicationException.class,
                () -> tx(service -> service.review("other-client", uuid, new ReviewRequest("STAR"), "actor"))).getResponse().getStatus());
        enabled = Set.of();
        assertEquals(404, assertThrows(WebApplicationException.class,
                () -> tx(service -> service.review("client", uuid, new ReviewRequest("STAR"), "actor"))).getResponse().getStatus());
        assertEquals(0L, count("account_calendar_review"));
        verifyNoInteractions(plan);
    }

    @Test void suggestionCountsDeduplicateOccurrenceCopiesAndKeepLegacyFallbackSeparate() {
        tx(service -> {
            var suggestions = new CalendarSuggestionService(); suggestions.em = service.em;
            suggestions.record("user", List.of(
                    new CalendarSyncTally.UnmatchedDomainSighting("client.example", "a", "shared", TO.toLocalDate().minusDays(1)),
                    new CalendarSyncTally.UnmatchedDomainSighting("client.example", "old", "old", TO.toLocalDate().minusDays(100)),
                    new CalendarSyncTally.UnmatchedDomainSighting("client.example", "legacy1", TO.toLocalDate().minusDays(2)),
                    new CalendarSyncTally.UnmatchedDomainSighting("other.example", "same", "shared", TO.toLocalDate().minusDays(1))), TO);
            suggestions.record("other", List.of(
                    new CalendarSyncTally.UnmatchedDomainSighting("client.example", "copy", "shared", TO.toLocalDate().minusDays(1)),
                    new CalendarSyncTally.UnmatchedDomainSighting("client.example", "legacy2", " ", TO.toLocalDate().minusDays(3))), TO);
            service.em.flush();
            var rows = suggestions.aggregateRows(TO.toLocalDate().minusDays(90));
            Object[] client = rows.stream().filter(row -> "client.example".equals(row[0])).findFirst().orElseThrow();
            assertEquals(4, ((Number) client[1]).intValue());
            assertEquals(3, ((Number) client[2]).intValue());
            assertEquals(2, ((Number) client[3]).intValue());
            assertEquals(1, ((Number) rows.stream().filter(row -> "other.example".equals(row[0])).findFirst().orElseThrow()[1]).intValue());
            return null;
        });
    }

    @Test void suggestionReconciliationPreservesIncompleteReadsAndPartiallyReadDays() {
        tx(service -> {
            var suggestions = new CalendarSuggestionService(); suggestions.em = service.em;
            suggestions.record("user", List.of(
                    new CalendarSyncTally.UnmatchedDomainSighting("client.example", "first", FROM.toLocalDate()),
                    new CalendarSyncTally.UnmatchedDomainSighting("client.example", "middle", FROM.toLocalDate().plusDays(1)),
                    new CalendarSyncTally.UnmatchedDomainSighting("client.example", "last", TO.toLocalDate())), TO, 1, FROM, TO, false);
            return null;
        });
        tx(service -> { var suggestions = new CalendarSuggestionService(); suggestions.em=service.em;
            suggestions.record("user", List.of(), TO, 2, FROM, TO, false); return null; });
        assertEquals(3L, count("calendar_unmatched_meeting"));
        tx(service -> { var suggestions = new CalendarSuggestionService(); suggestions.em=service.em;
            suggestions.record("user", List.of(), TO, 3, FROM.plusHours(1), TO.plusHours(1), true); return null; });
        assertEquals(2L, count("calendar_unmatched_meeting"), "partial first and last days remain");
        tx(service -> { var suggestions = new CalendarSuggestionService(); suggestions.em=service.em;
            suggestions.record("user", List.of(), TO, 4, FROM, TO.plusDays(1), true); return null; });
        assertEquals(0L, count("calendar_unmatched_meeting"));
    }

    @Test void providerIdentitiesAreCaseSensitiveForCandidateAndSuggestionCounts() {
        tx(service -> {
            service.record("user", 1, List.of(
                    event("client", "user", "one", "UID-A", "person@client.example", "RECURRING"),
                    event("client", "user", "two", "uid-a", "person@client.example", "RECURRING")), FROM, TO, true);
            assertEquals(2, service.forClient("client").getFirst().occurrences());
            var suggestions = new CalendarSuggestionService(); suggestions.em=service.em;
            suggestions.record("user", List.of(
                    new CalendarSyncTally.UnmatchedDomainSighting("client.example", "one", "UID-A", TO.toLocalDate()),
                    new CalendarSyncTally.UnmatchedDomainSighting("client.example", "two", "uid-a", TO.toLocalDate())), TO);
            service.em.flush();
            Object[] row = suggestions.aggregateRows(TO.toLocalDate().minusDays(90)).getFirst();
            assertEquals(2, ((Number) row[1]).intValue());
            assertEquals(2, ((Number) row[2]).intValue());
            return null;
        });
    }

    static String seed(String reason, String email) {
        tx(service -> { service.record("user", 1, List.of(event("client", "user", "event", "ical", email, reason)), FROM, TO, true); return null; });
        return CalendarCandidateService.candidateUuid("client", "user", "event", email, reason);
    }
    static PendingCandidate event(String client, String user, String event, String ical, String email, String reason) {
        return new PendingCandidate(client, user, event, ical, FROM.plusMonths(3).withNano(123456789), "series-" + user,
                reason, List.of(new Attendee(email, "Client Person", "client.example")));
    }
    static long count(String table) { return tx(service -> ((Number) service.em.createNativeQuery("select count(*) from " + table).getSingleResult()).longValue()); }
    static <T> T tx(Function<CalendarCandidateService, T> work) {
        try (Session session = factory.openSession()) {
            var transaction = session.beginTransaction();
            var service = new CalendarCandidateService();
            EntityManager em = mock(EntityManager.class, invocation -> invocation.getMethod().invoke(session, invocation.getArguments()));
            doReturn(new Client()).when(em).find(eq(Client.class), anyString());
            doReturn(new Client()).when(em).find(eq(Client.class), anyString(), eq(LockModeType.PESSIMISTIC_WRITE));
            service.em = em;
            service.planService = plan;
            service.consentService = mock(CalendarConsentService.class);
            when(service.consentService.consentedUserUuids()).thenAnswer(ignored -> enabled);
            when(service.consentService.isEnabled(anyString())).thenAnswer(call -> enabled.contains(call.getArgument(0)));
            try { T answer = work.apply(service); transaction.commit(); return answer; }
            catch (RuntimeException | Error failure) { if (transaction.isActive()) transaction.rollback(); throw failure; }
        }
    }
}
