package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.domain.user.service.UserDanlonHistoryService;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Plain unit test (no DB / no @QuarkusTest) for the idempotent save behind
 * POST /users/{useruuid}/statuses.
 *
 * <p>Reproduces the production bug where a same-day re-save returned HTTP 409 on a
 * {@code uq_userstatus_user_date} (useruuid, statusdate) collision: the BFF create route mints a
 * fresh uuid on every POST, so the save arrived with an unknown uuid, missed the by-uuid upsert
 * branch, and the blind insert violated the unique key instead of converging on the existing
 * (user, day) row.
 *
 * <p>The Panache lookups, the bulk update, the entity persist and the event emission are isolated
 * behind the {@link StatusService#findByUuid}, {@link StatusService#findByUserAndDate},
 * {@link StatusService#bulkUpdate}, {@link StatusService#persistNew},
 * {@link StatusService#sendCreateEvent} and {@link StatusService#sendUpdateEvent} seams, so the
 * reconcile decision is exercised without a live database. Retry tests wire {@code self} to a
 * <b>separate</b> mock instance so a regression from {@code self.createInTx(...)} to a bare
 * {@code createInTx(...)} (which would bypass the Arc interceptor stack and run without a
 * transaction) fails the suite.
 */
class StatusServiceTest {

    private static final String USER = "a268e68b-d539-4480-8fde-579da776c4db";
    private static final LocalDate STATUS_DATE = LocalDate.of(2026, 8, 6);

    @Test
    void sameDayResaveWithFreshUuidUpdatesExistingRow_andNothingIsInserted() {
        // The prod incident: a row for (user, 2026-08-06) already exists under another uuid,
        // and the BFF re-save arrives with a freshly minted uuid the DB has never seen.
        UserStatus existing = newStatus("existing-uuid", StatusType.ACTIVE, 37);
        UserStatus incoming = newStatus("fresh-bff-uuid", StatusType.ACTIVE, 20);

        StatusService service = spy(new StatusService());
        doReturn(Optional.empty()).when(service).findByUuid("fresh-bff-uuid");
        doReturn(Optional.of(existing)).when(service).findByUserAndDate(USER, STATUS_DATE);
        doNothing().when(service).bulkUpdate(anyString(), any(UserStatus.class));
        doNothing().when(service).sendUpdateEvent(any(UserStatus.class));

        // A same-day re-save must complete normally (2xx) — no exception thrown to the mapper.
        service.createInTx(incoming);

        // Crux of the fix: no INSERT is attempted for an already-present (user, date), so
        // uq_userstatus_user_date never fires. The update targets the existing row's uuid —
        // the incoming fresh uuid is discarded.
        verify(service, never()).persistNew(any(UserStatus.class));
        verify(service, never()).sendCreateEvent(any(UserStatus.class));
        verify(service).bulkUpdate("existing-uuid", incoming);
        // The managed entity must stay clean: a mutated managed row would be dirty-check-flushed
        // AGAIN at JTA commit (Hibernate 7 does not auto-flush before JPQL DML), where a lost race
        // with a concurrent delete escapes as an unmappable commit-time 500.
        assertEquals(37, existing.getAllocation());
        assertEquals(StatusType.ACTIVE, existing.getStatus());
        // The reconcile emits an UPDATE (not CREATE) event carrying the incoming values under the
        // existing row's uuid; the incoming entity's own uuid stays untouched so the retry
        // orchestration can still recognize a deterministic update-collision.
        ArgumentCaptor<UserStatus> payload = ArgumentCaptor.forClass(UserStatus.class);
        verify(service).sendUpdateEvent(payload.capture());
        assertEquals("existing-uuid", payload.getValue().getUuid());
        assertEquals(20, payload.getValue().getAllocation());
        assertEquals("fresh-bff-uuid", incoming.getUuid());
    }

    @Test
    void resaveWithKnownUuidStillUpdatesThatRow() {
        // The pre-existing upsert contract: the PUT edit flow holds the row's real uuid and
        // updates it without consulting the natural key.
        UserStatus existing = newStatus("known-uuid", StatusType.ACTIVE, 37);
        UserStatus incoming = newStatus("known-uuid", StatusType.ACTIVE, 20);

        StatusService service = spy(new StatusService());
        doReturn(Optional.of(existing)).when(service).findByUuid("known-uuid");
        doNothing().when(service).bulkUpdate(anyString(), any(UserStatus.class));
        doNothing().when(service).sendUpdateEvent(any(UserStatus.class));

        service.createInTx(incoming);

        verify(service).bulkUpdate("known-uuid", incoming);
        verify(service, never()).findByUserAndDate(anyString(), any(LocalDate.class));
        verify(service, never()).persistNew(any(UserStatus.class));
    }

    @Test
    void newUserDateIsPersistedWithFlush() {
        UserStatus incoming = spy(newStatus("fresh-uuid", StatusType.ACTIVE, 37));

        StatusService service = spy(new StatusService());
        doReturn(Optional.empty()).when(service).findByUuid(anyString());
        doReturn(Optional.empty()).when(service).findByUserAndDate(anyString(), any(LocalDate.class));
        doNothing().when(incoming).persistAndFlush();
        doNothing().when(service).sendCreateEvent(any(UserStatus.class));

        service.createInTx(incoming);

        verify(service, never()).bulkUpdate(anyString(), any(UserStatus.class));
        // Flushed (not a deferred commit) so a concurrent-insert race surfaces as a catchable
        // PersistenceException the orchestrator can retry / map to 409 — never a deferred 500.
        verify(incoming).persistAndFlush();
        verify(service).sendCreateEvent(incoming);
        verify(service, never()).sendUpdateEvent(any(UserStatus.class));
    }

    @Test
    void missingUuidIsMintedBeforeTheSave() {
        // Unlike CareerLevelService (which ignores blank uuids), StatusService mints one — backend
        // callers may legitimately omit it.
        UserStatus incoming = newStatus(null, StatusType.ACTIVE, 37);

        StatusService service = spy(new StatusService());
        StatusService selfProxy = mock(StatusService.class);
        service.self = selfProxy;

        service.create(incoming);

        assertNotNull(incoming.getUuid());
        verify(selfProxy).createInTx(incoming);
    }

    @Test
    void lostInsertRaceIsRetriedOnceThroughTheSelfProxy() {
        UserStatus incoming = newStatus("fresh-uuid", StatusType.ACTIVE, 37);

        // self is a SEPARATE mock: the retry must route through the Arc client proxy to get a
        // fresh transaction — a bare this.createInTx(...) would silently run without one.
        StatusService service = spy(new StatusService());
        StatusService selfProxy = mock(StatusService.class);
        service.self = selfProxy;
        doThrow(duplicateKeyViolation()).doNothing().when(selfProxy).createInTx(incoming);
        doReturn(Optional.empty()).when(service).findByUuid("fresh-uuid");

        // The loser of a concurrent insert race must converge, not throw.
        service.create(incoming);

        verify(selfProxy, times(2)).createInTx(incoming);
        verify(service, never()).createInTx(any(UserStatus.class));
    }

    @Test
    void unrelatedPersistenceExceptionIsNotRetried() {
        UserStatus incoming = newStatus("fresh-uuid", StatusType.ACTIVE, 37);

        StatusService service = spy(new StatusService());
        StatusService selfProxy = mock(StatusService.class);
        service.self = selfProxy;
        doThrow(new PersistenceException("database unavailable")).when(selfProxy).createInTx(incoming);

        assertThrows(PersistenceException.class, () -> service.create(incoming));

        verify(selfProxy, times(1)).createInTx(incoming);
    }

    @Test
    void secondConsecutiveDuplicateFailurePropagatesFor409Mapping() {
        UserStatus incoming = newStatus("fresh-uuid", StatusType.ACTIVE, 37);

        StatusService service = spy(new StatusService());
        StatusService selfProxy = mock(StatusService.class);
        service.self = selfProxy;
        PersistenceException violation = duplicateKeyViolation();
        doThrow(violation).when(selfProxy).createInTx(incoming);
        doReturn(Optional.empty()).when(service).findByUuid("fresh-uuid");

        // No retry loop: the second failure reaches DatabaseConstraintViolationExceptionMapper (409).
        PersistenceException thrown = assertThrows(PersistenceException.class, () -> service.create(incoming));

        assertSame(violation, thrown);
        verify(selfProxy, times(2)).createInTx(incoming);
    }

    @Test
    void deterministicUpdateCollisionIsNotRetried() {
        // A caller holding a real row uuid moves its statusdate onto a date already owned by
        // another row of the same user. The by-uuid update path fails identically on every
        // attempt — retrying would just re-run a doomed transaction and log a misleading
        // "concurrent insert" WARN. It must surface as a 409 immediately.
        UserStatus incoming = newStatus("known-uuid", StatusType.ACTIVE, 37);
        UserStatus ownRow = newStatus("known-uuid", StatusType.ACTIVE, 20);

        StatusService service = spy(new StatusService());
        StatusService selfProxy = mock(StatusService.class);
        service.self = selfProxy;
        PersistenceException violation = duplicateKeyViolation();
        doThrow(violation).when(selfProxy).createInTx(incoming);
        doReturn(Optional.of(ownRow)).when(service).findByUuid("known-uuid");

        PersistenceException thrown = assertThrows(PersistenceException.class, () -> service.create(incoming));

        assertSame(violation, thrown);
        verify(selfProxy, times(1)).createInTx(incoming);
    }

    @Test
    void duplicateDetectedViaMessageFallbackIsRetried() {
        // The detector must also recognize the violation when the constraint name is only present
        // in the exception message (Hibernate may report a null constraint name).
        UserStatus incoming = newStatus("fresh-uuid", StatusType.ACTIVE, 37);

        StatusService service = spy(new StatusService());
        StatusService selfProxy = mock(StatusService.class);
        service.self = selfProxy;
        doThrow(new PersistenceException(
                "could not execute statement [Duplicate entry 'a268e68b-2026-08-06' for key 'uq_userstatus_user_date']"))
                .doNothing().when(selfProxy).createInTx(incoming);
        doReturn(Optional.empty()).when(service).findByUuid("fresh-uuid");

        service.create(incoming);

        verify(selfProxy, times(2)).createInTx(incoming);
    }

    @Test
    void lostRaceConvergesOnWinnersRowThroughTheRealUnitOfWork() {
        // Composition test: the retry must RE-RUN the natural-key lookup and land on the winner's
        // row — not re-attempt the insert with a cached lookup result.
        UserStatus winner = newStatus("winner-uuid", StatusType.ACTIVE, 37);
        UserStatus incoming = newStatus("fresh-uuid", StatusType.ACTIVE, 20);

        StatusService service = spy(new StatusService());
        service.self = service; // intentionally: this test drives the real createInTx twice
        doReturn(Optional.empty()).when(service).findByUuid("fresh-uuid");
        doReturn(Optional.empty()).doReturn(Optional.of(winner))
                .when(service).findByUserAndDate(USER, STATUS_DATE);
        doThrow(duplicateKeyViolation()).when(service).persistNew(incoming);
        doNothing().when(service).bulkUpdate(anyString(), any(UserStatus.class));
        doNothing().when(service).sendUpdateEvent(any(UserStatus.class));

        service.create(incoming);

        // First attempt: lookups miss, insert loses the race. Retry: lookup sees the winner and
        // reconciles via update — the insert is attempted exactly once, and no CREATE event is
        // ever emitted for the failed insert.
        verify(service, times(1)).persistNew(incoming);
        verify(service).bulkUpdate("winner-uuid", incoming);
        verify(service, times(2)).createInTx(incoming);
        verify(service, never()).sendCreateEvent(any(UserStatus.class));
    }

    @Test
    void sameDayActiveFlipWithMintedDanlonIsRejectedBeforeAnyWrite() {
        // Status-specific semantics: the natural-key reconcile must still route through the Danløn
        // orphan guard. A same-day re-save flipping ACTIVE → TERMINATED while a Danløn number was
        // already minted for the month must be rejected (400 via StatusResource), not overwrite.
        UserStatus existing = newStatus("existing-uuid", StatusType.ACTIVE, 37);
        UserStatus incoming = newStatus("fresh-bff-uuid", StatusType.TERMINATED, 0);

        StatusService service = spy(new StatusService());
        service.danlonHistoryService = mock(UserDanlonHistoryService.class);
        doReturn(true).when(service.danlonHistoryService)
                .hasDanlonChangedInMonth(USER, STATUS_DATE.withDayOfMonth(1));
        doReturn(Optional.empty()).when(service).findByUuid("fresh-bff-uuid");
        doReturn(Optional.of(existing)).when(service).findByUserAndDate(USER, STATUS_DATE);

        assertThrows(IllegalStateException.class, () -> service.createInTx(incoming));

        verify(service, never()).bulkUpdate(anyString(), any(UserStatus.class));
        verify(service, never()).persistNew(any(UserStatus.class));
        verify(service, never()).sendUpdateEvent(any(UserStatus.class));
    }

    /**
     * The exception shape Hibernate ORM 7 actually raises when {@code persistAndFlush()} trips
     * {@code uq_userstatus_user_date}: the Hibernate {@link ConstraintViolationException} is
     * thrown <b>directly</b> as the top-level exception (it extends jakarta
     * {@link PersistenceException} via JDBCException/HibernateException) — no wrapper.
     */
    private static PersistenceException duplicateKeyViolation() {
        return new ConstraintViolationException(
                "could not execute statement [Duplicate entry '" + USER + "-2026-08-06' for key 'uq_userstatus_user_date']",
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry '" + USER + "-2026-08-06' for key 'uq_userstatus_user_date'", "23000", 1062),
                "uq_userstatus_user_date");
    }

    private static UserStatus newStatus(String uuid, StatusType status, int allocation) {
        UserStatus userStatus = new UserStatus(ConsultantType.CONSULTANT, status, STATUS_DATE, allocation, USER);
        userStatus.setUuid(uuid);
        return userStatus;
    }
}
