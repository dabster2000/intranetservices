package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.aggregates.crm.calendar.model.AccountMeeting;
import dk.trustworks.intranet.aggregates.crm.calendar.model.AccountMeetingAttendee;
import dk.trustworks.intranet.aggregates.crm.calendar.model.CalendarSyncState;
import dk.trustworks.intranet.aggregates.crm.calendar.model.CalendarSyncJob;
import jakarta.ws.rs.WebApplicationException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual service + Hibernate + MariaDB, opt-in against a disposable localhost database.
 * Run src/test/scripts/calendar-sync-mariadb-tests.sh. No Quarkus application or production
 * configuration is loaded. The URL guard intentionally refuses remote or ordinary databases.
 */
@Tag("calendar-mariadb")
@EnabledIfEnvironmentVariable(named = "CRM_CALENDAR_IT_JDBC_URL", matches = ".+")
class CalendarSyncStateServiceMariaDbTest {
    static final Instant START = Instant.parse("2026-09-15T02:20:00.123456789Z");
    static SessionFactory factory;
    static String url;
    static String password;

    @BeforeAll
    static void database() throws Exception {
        url = System.getenv("CRM_CALENDAR_IT_JDBC_URL");
        if (!url.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/crm_calendar_test(?:_[a-z0-9]+)?")) {
            throw new IllegalArgumentException("Calendar integration tests require a disposable localhost crm_calendar_test database");
        }
        password = System.getenv().getOrDefault("CRM_CALENDAR_IT_PASSWORD", "calendar-test-only");
        try (Connection c = connection()) {
            sql(c, "drop table if exists account_meeting_attendee,calendar_sync_state,account_meeting,user_calendar_consent,roles,`user`,calendar_sync_job,calendar_sync_job_lock");
            sql(c, "create table `user` (uuid char(36) primary key)");
            sql(c, "create table roles (useruuid char(36), role varchar(32))");
            sql(c, "create table user_calendar_consent (user_uuid char(36) primary key, enabled boolean not null)");
            sql(c, """
                    create table account_meeting (
                      uuid char(36) primary key, client_uuid char(36) not null,
                      user_uuid char(36) not null, graph_event_id varchar(600) not null,
                      occurred_at datetime not null, duration_minutes int not null,
                      attendee_count int not null, own_attendee_count int not null,
                      synced_at datetime not null, ical_uid varchar(255),
                      unique key uq_account_meeting_event(graph_event_id,user_uuid)
                    ) engine=InnoDB default charset=utf8mb4
                    """);
            sql(c, """
                    create table account_meeting_attendee (
                      uuid char(36) primary key, meeting_uuid char(36) not null,
                      email varchar(255) not null, display_name varchar(255), domain varchar(190) not null,
                      unique key uq_account_meeting_attendee(meeting_uuid,email),
                      foreign key(meeting_uuid) references account_meeting(uuid) on delete cascade
                    ) engine=InnoDB default charset=utf8mb4
                    """);
            String migration = Files.readString(Path.of("src/main/resources/db/migration/V620__Calendar_reconciliation_generation.sql"));
            for (String statement : migration.split(";")) if (!statement.isBlank()) sql(c, statement);
            String jobs = Files.readString(Path.of("src/main/resources/db/migration/V622__Calendar_recovery_jobs.sql"));
            for (String statement : jobs.split(";")) if (!statement.isBlank()) sql(c, statement);
            String boundary = Files.readString(Path.of("src/main/resources/db/migration/V623__Calendar_recovery_staging_boundary.sql"));
            sql(c, "drop procedure if exists sp_sync_prod_to_staging");
            // Validate CREATE only. This synthetic schema must never CALL the cross-schema routine.
            sql(c, boundary.substring(boundary.indexOf("CREATE PROCEDURE"), boundary.lastIndexOf("END$$") + 3));
        }
        Configuration cfg = new Configuration().addAnnotatedClass(AccountMeeting.class)
                .addAnnotatedClass(AccountMeetingAttendee.class).addAnnotatedClass(CalendarSyncState.class)
                .addAnnotatedClass(CalendarSyncJob.class);
        cfg.setProperty("hibernate.connection.driver_class", "org.mariadb.jdbc.Driver");
        cfg.setProperty("hibernate.connection.url", url);
        cfg.setProperty("hibernate.connection.username", "root");
        cfg.setProperty("hibernate.connection.password", password);
        cfg.setProperty("hibernate.connection.pool_size", "4");
        cfg.setProperty("hibernate.hbm2ddl.auto", "none");
        cfg.setProperty("hibernate.show_sql", "false");
        cfg.setProperty("hibernate.order_inserts", "true");
        factory = cfg.buildSessionFactory();
    }

    @AfterAll
    static void close() { if (factory != null) factory.close(); }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = connection()) {
            for (String table : List.of("account_meeting_attendee", "account_meeting", "calendar_sync_state", "user_calendar_consent", "roles", "`user`", "calendar_sync_job")) sql(c, "delete from " + table);
            sql(c, "insert into `user` values ('owner'),('other'),('consultant')");
            sql(c, "insert into roles values ('owner','SALES'),('other','PARTNER')");
        }
    }

    @Test
    void generationSurvivesTheActualZeroPrecisionColumn() {
        var lease = reserve("owner");
        var applied = tx(svc -> svc.complete(lease, true, true, () -> {
            svc.em.persist(meeting("fresh", "owner", "acme", lease.generation(), START));
            svc.em.flush();
            int removed = svc.em.createQuery("delete from AccountMeeting where userUuid='owner' and syncGeneration <> :generation")
                    .setParameter("generation", lease.generation()).executeUpdate();
            return new CalendarSyncStateService.SyncCounts(1, 0, removed);
        }));
        assertTrue(applied.applied());
        assertEquals(0, applied.counts().staleRemoved());
        assertEquals(1L, meetingCount());
        assertEquals(0, tx(svc -> svc.em.find(AccountMeeting.class, "fresh").getSyncedAt().getNano()).intValue());
    }

    @Test
    void actualPersistAndReconcileKeepFreshRowsAndCascadeRemovedAttendees() {
        var first = reserve("owner");
        actualCommit(first, true, List.of(pending("kept", "owner", "acme", "2026-09-02T10:00:00"),
                pending("cancelled", "owner", "acme", "2026-09-02T11:00:00")));
        var next = reserve("owner");
        var result = actualCommit(next, true, List.of(pending("kept", "owner", "acme", "2026-09-02T10:00:00")));
        assertTrue(result.applied());
        assertEquals(1, result.counts().meetings());
        assertEquals(1, result.counts().attendees());
        assertEquals(1, result.counts().staleRemoved());
        assertEquals(1L, tx(svc -> svc.em.createQuery("select count(a) from AccountMeetingAttendee a", Long.class).getSingleResult()).longValue());
    }

    @Test
    void actualIncompleteReadNeverPrunesMissingEvents() {
        actualCommit(reserve("owner"), true, List.of(pending("old", "owner", "acme", "2026-09-02T10:00:00")));
        var next = actualCommit(reserve("owner"), false, List.of(pending("new", "owner", "acme", "2026-09-02T11:00:00")));
        assertEquals(0, next.counts().staleRemoved());
        assertEquals(2L, meetingCount());
    }

    @Test
    void legacyTwoPartUuidIsReusedBeforeFirstFullGenerationReconciliation() {
        tx(svc -> { svc.em.persist(meeting("legacy-two-part-id", "owner", "acme", 0, START)); return null; });
        var lease = reserve("owner");
        var projection = new AccountCalendarSyncService.PendingMeeting("new-three-part-id", "acme", "owner",
                "shared-event", "ical-shared", LocalDateTime.parse("2026-09-02T10:00:00"), 60, 2, 1,
                List.of(new AccountCalendarSyncService.PendingAttendee("guest@acme.dk", "Guest", "acme.dk")));
        var result = actualCommit(lease, true, List.of(projection));
        assertEquals(1, result.counts().meetings());
        assertEquals(1, result.counts().attendees());
        assertEquals(0, result.counts().staleRemoved());
        assertEquals(List.of("legacy-two-part-id"), tx(svc -> svc.em.createQuery("select m.uuid from AccountMeeting m", String.class).getResultList()));
        assertEquals(List.of("legacy-two-part-id"), tx(svc -> svc.em.createQuery("select a.meetingUuid from AccountMeetingAttendee a", String.class).getResultList()));
    }

    @Test
    void caseSensitiveProviderIdsRemainDistinctDuringLegacyLookupAndPersistence() {
        tx(svc -> {
            var legacy = meeting("legacy-upper-case-id", "owner", "acme", 0, START);
            legacy.setGraphEventId("GraphEventCase");
            svc.em.persist(legacy);
            return null;
        });
        var occurred = LocalDateTime.parse("2026-09-02T10:00:00");
        var attendee = List.of(new AccountCalendarSyncService.PendingAttendee("guest@acme.dk", "Guest", "acme.dk"));
        var lower = new AccountCalendarSyncService.PendingMeeting("new-lower-case-id", "acme", "owner",
                "grapheventcase", "icalcase", occurred, 60, 2, 1, attendee, "seriescase", true, "STARRED_OVERRIDE");
        var upper = new AccountCalendarSyncService.PendingMeeting("new-upper-case-id", "acme", "owner",
                "GraphEventCase", "ICalCase", occurred, 30, 2, 1, attendee, "SeriesCase", true, "STARRED_OVERRIDE");
        // Lowercase arrives first: a case-insensitive natural-key fallback would overwrite
        // the unrelated legacy event before the uppercase projection is even examined.
        var result = actualCommit(reserve("owner"), true, List.of(lower, upper));
        assertEquals(2, result.counts().meetings());
        assertEquals(2, result.counts().attendees());
        assertEquals(0, result.counts().staleRemoved());
        assertEquals(Set.of("legacy-upper-case-id", "new-lower-case-id"),
                tx(svc -> Set.copyOf(svc.em.createQuery("select m.uuid from AccountMeeting m", String.class).getResultList())));
        assertEquals(30, tx(svc -> svc.em.find(AccountMeeting.class, "legacy-upper-case-id").getDurationMinutes()).intValue());
        assertEquals(60, tx(svc -> svc.em.find(AccountMeeting.class, "new-lower-case-id").getDurationMinutes()).intValue());
        assertEquals(2L, tx(svc -> svc.em.createQuery("select count(distinct m.icalUid) from AccountMeeting m", Long.class).getSingleResult()).longValue());
        assertEquals(2L, tx(svc -> svc.em.createQuery("select count(distinct m.seriesMasterId) from AccountMeeting m", Long.class).getSingleResult()).longValue());
    }

    @Test
    void actualReconcileHonoursInclusiveDateBoundsAndMailboxScope() {
        var first = reserve("owner");
        actualCommit(first, false, List.of(pending("lower", "owner", "acme", "2026-09-01T10:00:00"),
                pending("upper", "owner", "acme", "2026-09-03T10:00:00"),
                pending("outside", "owner", "acme", "2026-09-01T09:59:59")));
        actualCommit(reserve("other"), false, List.of(pending("other", "other", "acme", "2026-09-02T10:00:00")));
        var removed = actualCommit(reserve("owner"), true, List.of());
        assertEquals(2, removed.counts().staleRemoved());
        assertEquals(Set.of("outside", "other"), tx(svc -> Set.copyOf(svc.em.createQuery("select m.uuid from AccountMeeting m", String.class).getResultList())));
    }

    @Test
    void actualPersistRollbackRestoresPreviousRowsAndAttendees() {
        actualCommit(reserve("owner"), true, List.of(pending("original", "owner", "acme", "2026-09-02T10:00:00")));
        var next = reserve("owner");
        assertThrows(IllegalStateException.class, () -> tx(svc -> svc.complete(next, true, true, () -> {
            var sync = actualSync(svc);
            sync.persist(List.of(pending("replacement", "owner", "acme", "2026-09-02T11:00:00")), next.startedAt(), next.generation());
            svc.em.flush();
            sync.reconcile("owner", window(), next.generation());
            throw new IllegalStateException("synthetic failure after actual reconciliation");
        })));
        assertEquals(List.of("original"), tx(svc -> svc.em.createQuery("select m.uuid from AccountMeeting m", String.class).getResultList()));
        assertEquals(1L, tx(svc -> svc.em.createQuery("select count(a) from AccountMeetingAttendee a", Long.class).getSingleResult()).longValue());
    }

    @Test
    void migratedUniqueKeyAllowsOneEventAtTwoAccounts() {
        var lease = reserve("owner");
        tx(svc -> {
            svc.em.persist(meeting("a", "owner", "acme", lease.generation(), START));
            svc.em.persist(meeting("b", "owner", "beta", lease.generation(), START));
            return null;
        });
        assertEquals(2L, meetingCount());
    }

    @Test
    void explicitConsentOverridesRoleDefaultsAtReservation() {
        tx(svc -> { sql(svc.em.unwrap(Session.class), "insert into user_calendar_consent values ('owner',false),('consultant',true)"); return null; });
        assertNull(reserve("owner"));
        assertNotNull(reserve("consultant"));
        assertNotNull(reserve("other"));
    }

    @Test
    void revocationRejectsInFlightReadAndWriteButKeepsPastSharing() {
        var lease = reserve("owner");
        tx(svc -> { svc.em.persist(meeting("past", "owner", "acme", 0, START)); return null; });
        tx(svc -> { svc.fenceConsentChange("owner", false); sql(svc.em.unwrap(Session.class), "insert into user_calendar_consent values ('owner',false)"); return null; });
        assertFalse(tx(svc -> svc.canRead(lease)).booleanValue());
        AtomicBoolean called = new AtomicBoolean();
        var result = tx(svc -> svc.complete(lease, true, true, () -> { called.set(true); return CalendarSyncStateService.SyncCounts.empty(); }));
        assertFalse(result.applied());
        assertFalse(called.get());
        assertEquals(1L, meetingCount());
    }

    @Test
    void roleRemovalIsRecheckedBeforePageAndCommit() {
        var lease = reserve("owner");
        tx(svc -> { sql(svc.em.unwrap(Session.class), "delete from roles where useruuid='owner'"); return null; });
        assertFalse(tx(svc -> svc.canRead(lease)).booleanValue());
        assertFalse(tx(svc -> svc.complete(lease, true, true, CalendarSyncStateService.SyncCounts::empty)).applied());
    }

    @Test
    void newerPassCannotBeOverwrittenByAnOlderCompletionOrFailure() {
        var old = reserve("owner");
        var current = reserve("owner");
        tx(svc -> svc.complete(current, true, true, () -> new CalendarSyncStateService.SyncCounts(7, 9, 2)));
        assertFalse(tx(svc -> svc.complete(old, true, true, CalendarSyncStateService.SyncCounts::empty)).applied());
        tx(svc -> { svc.fail(old, "GRAPH_READ"); return null; });
        var state = state("owner");
        assertEquals("SUCCEEDED", state.getOutcome());
        assertEquals(7, state.getMeetingsKept());
        assertEquals(current.generation(), state.getGeneration());
    }

    @Test
    void incompleteAndIncrementalReadsCannotFinishVersionedRecovery() {
        var first = reserve("owner");
        assertTrue(first.recoveryRequired());
        tx(svc -> svc.complete(first, true, false, CalendarSyncStateService.SyncCounts::empty));
        assertEquals("INCOMPLETE", state("owner").getOutcome());
        var second = reserve("owner");
        assertTrue(second.recoveryRequired());
        tx(svc -> svc.complete(second, false, true, CalendarSyncStateService.SyncCounts::empty));
        assertNull(state("owner").getLastFullSuccessfulAt());
        var third = reserve("owner");
        assertTrue(third.recoveryRequired());
        tx(svc -> svc.complete(third, true, true, CalendarSyncStateService.SyncCounts::empty));
        assertFalse(reserve("owner").recoveryRequired());
    }

    @Test
    void starOrDomainChangeSupersedesReadAndRequestsFullRecovery() {
        var lease = reserve("owner");
        tx(svc -> svc.complete(lease, true, true, CalendarSyncStateService.SyncCounts::empty));
        var inFlight = reserve("owner");
        tx(svc -> { svc.requestFullRead(Set.of("owner", "other")); return null; });
        assertFalse(tx(svc -> svc.canRead(inFlight)).booleanValue());
        assertFalse(tx(svc -> svc.complete(inFlight, true, true, CalendarSyncStateService.SyncCounts::empty)).applied());
        assertTrue(reserve("owner").recoveryRequired());
    }

    @Test
    void failedWriteRollsBackMeetingsAndDoesNotDeclareSuccess() {
        var lease = reserve("owner");
        assertThrows(IllegalStateException.class, () -> tx(svc -> svc.complete(lease, true, true, () -> {
            svc.em.persist(meeting("rolled-back", "owner", "acme", lease.generation(), START));
            svc.em.flush();
            throw new IllegalStateException("synthetic failure");
        })));
        assertEquals(0L, meetingCount());
        assertNull(state("owner").getLastSuccessfulAt());
        tx(svc -> { svc.fail(lease, "PERSISTENCE"); return null; });
        assertEquals("FAILED", state("owner").getOutcome());
        assertEquals("PERSISTENCE", state("owner").getFailureCode());
    }

    @Test
    void errorPayloadIsNotWrittenToHealth() {
        var lease = reserve("owner");
        tx(svc -> { svc.fail(lease, "Graph failed for private@example.org: secret"); return null; });
        assertEquals("INTERNAL", state("owner").getFailureCode());
        var second = reserve("owner");
        tx(svc -> { svc.fail(second, "SECRET_ACCOUNT_TOKEN"); return null; });
        assertEquals("INTERNAL", state("owner").getFailureCode());
    }

    @Test
    void completedLeaseCannotReadAgainOrOverwriteItsOwnResult() {
        var lease = reserve("owner");
        tx(svc -> svc.complete(lease, true, true, () -> new CalendarSyncStateService.SyncCounts(3, 4, 5)));
        assertFalse(tx(svc -> svc.canRead(lease)).booleanValue());
        assertFalse(tx(svc -> svc.complete(lease, true, true, CalendarSyncStateService.SyncCounts::empty)).applied());
        tx(svc -> { svc.fail(lease, "SYNC_FAILED"); return null; });
        assertEquals("SUCCEEDED", state("owner").getOutcome());
        assertEquals(3, state("owner").getMeetingsKept());
    }

    @Test
    void recoveryMigrationsCreateQueueAndExcludeCalendarEvidenceFromStaging() {
        assertEquals(1, tx(svc -> ((Number) svc.em.createNativeQuery("select count(*) from calendar_sync_job_lock where id=1").getSingleResult()).intValue()).intValue());
        String definition = tx(svc -> (String) svc.em.createNativeQuery("select routine_definition from information_schema.routines where routine_schema=database() and routine_name='sp_sync_prod_to_staging'").getSingleResult());
        for (String table : List.of("calendar_sync_state", "account_calendar_candidate", "account_calendar_review", "calendar_sync_job", "calendar_sync_job_lock")) {
            assertTrue(definition.contains("'" + table + "'"), table);
        }
    }

    @Test
    void concurrentRecoveryRequestsEnqueueExactlyOneJob() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> request = () -> {
                try { return tx(svc -> jobs(svc).enqueueInTransaction(true, "owner")).status(); }
                catch (WebApplicationException conflict) { assertEquals(409, conflict.getResponse().getStatus()); return "CONFLICT"; }
            };
            var results = executor.invokeAll(List.of(request, request));
            assertEquals(Set.of("QUEUED", "CONFLICT"), Set.of(results.get(0).get(), results.get(1).get()));
            assertEquals(1L, tx(svc -> svc.em.createQuery("select count(j) from CalendarSyncJob j", Long.class).getSingleResult()).longValue());
        }
    }

    @Test
    void concurrentRecoveryWorkersClaimTheQueueExactlyOnce() throws Exception {
        var queued = tx(svc -> jobs(svc).enqueueInTransaction(true, "owner"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<CalendarSyncJobService.Job> claim = () -> tx(svc -> jobs(svc).claim());
            var results = executor.invokeAll(List.of(claim, claim));
            var a = results.get(0).get(); var b = results.get(1).get();
            assertTrue((a == null) != (b == null));
            var claimed = a == null ? b : a;
            assertEquals(queued.uuid(), claimed.uuid());
            assertEquals("RUNNING", claimed.status());
            assertTrue(claimed.startedAt().endsWith("Z"));
        }
    }

    @Test
    void interruptedRecoveryCanBeRequeuedWithoutRevivingTheOldJob() {
        var first = tx(svc -> jobs(svc).enqueueInTransaction(true, "owner"));
        tx(svc -> jobs(svc).claim());
        tx(svc -> { svc.em.find(CalendarSyncJob.class, first.uuid()).setClaimedAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(3)); return null; });
        var next = tx(svc -> jobs(svc).enqueueInTransaction(true, "owner"));
        assertNotEquals(first.uuid(), next.uuid());
        var old = tx(svc -> svc.em.find(CalendarSyncJob.class, first.uuid()));
        assertEquals("INTERRUPTED", old.getStatus());
        assertEquals("WORKER_INTERRUPTED", old.getFailureCode());
        assertNotNull(old.getFinishedAt());
        assertEquals(next.uuid(), tx(svc -> jobs(svc).claim()).uuid());
    }

    @Test
    void aggregateHealthRetainsSuccessTimeAcrossLaterFailureAndExcludesOptOuts() {
        var first = reserve("owner");
        tx(svc -> svc.complete(first, true, true, CalendarSyncStateService.SyncCounts::empty));
        var failed = reserve("owner");
        tx(svc -> { svc.fail(failed, "GRAPH_READ"); return null; });
        var health = tx(CalendarSyncStateService::health);
        assertEquals(2, health.enabled());
        assertEquals(1, health.failed());
        assertEquals(1, health.neverSynced());
        assertNotNull(health.lastSuccessfulAt());
        assertNotNull(health.lastFullSuccessfulAt());
        tx(svc -> { svc.fenceConsentChange("owner", false); sql(svc.em.unwrap(Session.class), "insert into user_calendar_consent values ('owner',false)"); return null; });
        assertEquals(1, tx(CalendarSyncStateService::health).enabled());
    }

    @Test
    void abandonedWorkerIsReportedAsInterruptedWithoutChangingItsLease() {
        var old = reserve("owner");
        var current = tx(svc -> svc.reserve("other", Instant.now()));
        var health = tx(CalendarSyncStateService::health);
        assertEquals(1, health.interrupted());
        assertEquals(1, health.failed());
        assertEquals(1, health.inProgress());
        assertEquals("RUNNING", state("owner").getOutcome());
        assertTrue(tx(svc -> svc.canRead(old)).booleanValue());
        assertTrue(tx(svc -> svc.canRead(current)).booleanValue());
    }

    @Test
    void concurrentReservationsAreSerializedByTheDatabase() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<CalendarSyncStateService.Lease> task = () -> reserve("owner");
            var futures = executor.invokeAll(List.of(task, task));
            var a = futures.get(0).get();
            var b = futures.get(1).get();
            assertNotEquals(a.generation(), b.generation());
            assertEquals(1, Math.abs(a.generation() - b.generation()));
            assertNotEquals(tx(svc -> svc.canRead(a)).booleanValue(), tx(svc -> svc.canRead(b)).booleanValue());
        }
    }

    static CalendarSyncStateService.Lease reserve(String user) { return tx(svc -> svc.reserve(user, START)); }
    static CalendarSyncJobService jobs(CalendarSyncStateService state) {
        var jobs = new CalendarSyncJobService(); jobs.em = state.em; return jobs;
    }
    static AccountCalendarSyncService actualSync(CalendarSyncStateService state) {
        var sync = new AccountCalendarSyncService(); sync.em = state.em; return sync;
    }
    static AccountCalendarSyncService.ReadWindow window() {
        return new AccountCalendarSyncService.ReadWindow(
                LocalDateTime.parse("2026-09-01T10:00:00").atZone(CalendarTime.ZONE).toInstant(),
                LocalDateTime.parse("2026-09-03T10:00:00").atZone(CalendarTime.ZONE).toInstant(), true);
    }
    static AccountCalendarSyncService.PendingMeeting pending(String id, String user, String client, String occurred) {
        return new AccountCalendarSyncService.PendingMeeting(id, client, user, "graph-" + id, "ical-" + id,
                LocalDateTime.parse(occurred), 60, 2, 1,
                List.of(new AccountCalendarSyncService.PendingAttendee("guest@acme.dk", "Client Guest", "acme.dk")));
    }
    static CalendarSyncStateService.Completion actualCommit(CalendarSyncStateService.Lease lease, boolean complete,
                                                            List<AccountCalendarSyncService.PendingMeeting> meetings) {
        return tx(svc -> svc.complete(lease, true, complete, () -> {
            var sync = actualSync(svc);
            sync.persist(meetings, lease.startedAt(), lease.generation());
            svc.em.flush();
            int stale = complete ? (int) sync.reconcile(lease.userUuid(), window(), lease.generation()) : 0;
            int retained = svc.em.createQuery("select count(m) from AccountMeeting m where m.userUuid=:user and m.syncGeneration=:generation", Long.class)
                    .setParameter("user", lease.userUuid()).setParameter("generation", lease.generation()).getSingleResult().intValue();
            int attendees = ((Number) svc.em.createNativeQuery("select count(*) from account_meeting_attendee a join account_meeting m on m.uuid=a.meeting_uuid where m.user_uuid=:user and m.sync_generation=:generation")
                    .setParameter("user", lease.userUuid()).setParameter("generation", lease.generation()).getSingleResult()).intValue();
            return new CalendarSyncStateService.SyncCounts(retained, attendees, stale);
        }));
    }
    static CalendarSyncState state(String user) { return tx(svc -> svc.em.find(CalendarSyncState.class, user)); }
    static long meetingCount() { return tx(svc -> svc.em.createQuery("select count(m) from AccountMeeting m", Long.class).getSingleResult()); }
    static <T> T tx(Function<CalendarSyncStateService, T> work) {
        try (Session session = factory.openSession()) {
            var transaction = session.beginTransaction();
            var service = new CalendarSyncStateService();
            service.em = session;
            try { T answer = work.apply(service); transaction.commit(); return answer; }
            catch (RuntimeException | Error error) { if (transaction.isActive()) transaction.rollback(); throw error; }
        }
    }
    static AccountMeeting meeting(String id, String user, String client, long generation, Instant stamp) {
        var row = new AccountMeeting();
        row.setUuid(id); row.setUserUuid(user); row.setClientUuid(client); row.setGraphEventId("shared-event");
        row.setOccurredAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        row.setSyncedAt(LocalDateTime.ofInstant(stamp, java.time.ZoneOffset.UTC));
        row.setSyncGeneration(generation); row.setDurationMinutes(60); row.setAttendeeCount(2); row.setOwnAttendeeCount(1);
        return row;
    }
    static Connection connection() throws Exception { return DriverManager.getConnection(url, "root", password); }
    static void sql(Connection c, String text) throws Exception { try (var s = c.createStatement()) { s.execute(text); } }
    static void sql(Session session, String text) { session.createNativeMutationQuery(text).executeUpdate(); }
}
