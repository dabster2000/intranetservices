package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.NotFoundException;
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
 * POST /users/{useruuid}/salaries.
 *
 * <p>Same defect shape as {@link CareerLevelServiceTest}: the salary row is unique on
 * {@code uq_salary_user_date} (useruuid, activefrom), and the add flow arrives with a freshly
 * minted uuid, so saving a salary for a month that already has a row missed the by-uuid upsert
 * branch and tripped the unique key instead of converging on the existing (user, month) row.
 *
 * <p>The Panache lookups, the bulk update, the entity persist, the event emission and the Danløn
 * transition hook are isolated behind the {@link SalaryService#findByUuid},
 * {@link SalaryService#findByUserAndDate}, {@link SalaryService#bulkUpdate},
 * {@link SalaryService#persistNew}, {@link SalaryService#sendCreateEvent},
 * {@link SalaryService#sendUpdateEvent} and {@link SalaryService#handleSalaryTypeChange} seams,
 * so the reconcile decision is exercised without a live database. Retry tests wire {@code self}
 * to a <b>separate</b> mock instance so a regression from {@code self.createInTx(...)} to a bare
 * {@code createInTx(...)} (which would bypass the Arc interceptor stack and run without a
 * transaction) fails the suite.
 */
class SalaryServiceTest {

    private static final String USER = "a268e68b-d539-4480-8fde-579da776c4db";
    private static final LocalDate ACTIVE_FROM = LocalDate.of(2026, 8, 1);

    @Test
    void sameMonthResaveWithFreshUuidUpdatesExistingRow_andNothingIsInserted() {
        // A row for (user, 2026-08-01) already exists under another uuid, and the re-save
        // arrives with a freshly minted uuid the DB has never seen.
        Salary existing = newSalary("existing-uuid", 40000, SalaryType.NORMAL);
        Salary incoming = newSalary("fresh-uuid", 45000, SalaryType.NORMAL);

        SalaryService service = spy(new SalaryService());
        doReturn(Optional.empty()).when(service).findByUuid("fresh-uuid");
        doReturn(Optional.of(existing)).when(service).findByUserAndDate(USER, ACTIVE_FROM);
        doNothing().when(service).bulkUpdate(anyString(), any(Salary.class));
        doNothing().when(service).sendUpdateEvent(any(Salary.class));

        // A same-month re-save must complete normally (2xx) — no exception thrown to the mapper.
        service.createInTx(incoming);

        // Crux of the fix: no INSERT is attempted for an already-present (user, month), so
        // uq_salary_user_date never fires. The update targets the existing row's uuid —
        // the incoming fresh uuid is discarded.
        verify(service, never()).persistNew(any(Salary.class));
        verify(service, never()).sendCreateEvent(any(Salary.class));
        verify(service).bulkUpdate("existing-uuid", incoming);
        // NORMAL → NORMAL is not a type flip: the Danløn transition hook must stay silent.
        verify(service, never()).handleSalaryTypeChange(anyString(), any(LocalDate.class));
        // The managed entity must stay clean: a mutated managed row would be dirty-check-flushed
        // AGAIN at JTA commit (Hibernate 7 does not auto-flush before JPQL DML), where a lost race
        // with a concurrent delete escapes as an unmappable commit-time 500.
        assertEquals(40000, existing.getSalary());
        // The reconcile emits an UPDATE (not CREATE) event carrying the incoming values under the
        // existing row's uuid; the incoming entity's own uuid stays untouched so the retry
        // orchestration can still recognize a deterministic update-collision.
        ArgumentCaptor<Salary> payload = ArgumentCaptor.forClass(Salary.class);
        verify(service).sendUpdateEvent(payload.capture());
        assertEquals("existing-uuid", payload.getValue().getUuid());
        assertEquals(45000, payload.getValue().getSalary());
        assertEquals("fresh-uuid", incoming.getUuid());
    }

    @Test
    void resaveWithKnownUuidStillUpdatesThatRow() {
        // The pre-existing upsert contract: the edit flow holds the row's real uuid and updates
        // it without consulting the natural key.
        Salary existing = newSalary("known-uuid", 40000, SalaryType.NORMAL);
        Salary incoming = newSalary("known-uuid", 45000, SalaryType.NORMAL);

        SalaryService service = spy(new SalaryService());
        doReturn(Optional.of(existing)).when(service).findByUuid("known-uuid");
        doNothing().when(service).bulkUpdate(anyString(), any(Salary.class));
        doNothing().when(service).sendUpdateEvent(any(Salary.class));

        service.createInTx(incoming);

        verify(service).bulkUpdate("known-uuid", incoming);
        verify(service, never()).findByUserAndDate(anyString(), any(LocalDate.class));
        verify(service, never()).persistNew(any(Salary.class));
    }

    @Test
    void newUserMonthIsPersistedWithFlush() {
        // HOURLY so the create branch skips the previous-month NORMAL check (a static Panache
        // lookup that would need a live database).
        Salary incoming = spy(newSalary("fresh-uuid", 500, SalaryType.HOURLY));

        SalaryService service = spy(new SalaryService());
        doReturn(Optional.empty()).when(service).findByUuid(anyString());
        doReturn(Optional.empty()).when(service).findByUserAndDate(anyString(), any(LocalDate.class));
        doNothing().when(incoming).persistAndFlush();
        doNothing().when(service).sendCreateEvent(any(Salary.class));

        service.createInTx(incoming);

        verify(service, never()).bulkUpdate(anyString(), any(Salary.class));
        // Flushed (not a deferred commit) so a concurrent-insert race surfaces as a catchable
        // PersistenceException the orchestrator can retry / map to 409 — never a deferred 500.
        verify(incoming).persistAndFlush();
        verify(service).sendCreateEvent(incoming);
        verify(service, never()).sendUpdateEvent(any(Salary.class));
    }

    @Test
    void hourlyToNormalFlipThroughTheNaturalKeyPathStillTriggersDanlonTransition() {
        // Salary-specific semantics: reconciling onto an existing HOURLY row with an incoming
        // NORMAL salary is a type flip and must still fire the Danløn transition hook.
        Salary existing = newSalary("existing-uuid", 500, SalaryType.HOURLY);
        Salary incoming = newSalary("fresh-uuid", 45000, SalaryType.NORMAL);

        SalaryService service = spy(new SalaryService());
        doReturn(Optional.empty()).when(service).findByUuid("fresh-uuid");
        doReturn(Optional.of(existing)).when(service).findByUserAndDate(USER, ACTIVE_FROM);
        doNothing().when(service).bulkUpdate(anyString(), any(Salary.class));
        doNothing().when(service).sendUpdateEvent(any(Salary.class));
        doNothing().when(service).handleSalaryTypeChange(anyString(), any(LocalDate.class));

        service.createInTx(incoming);

        verify(service).bulkUpdate("existing-uuid", incoming);
        verify(service).handleSalaryTypeChange(USER, ACTIVE_FROM);
    }

    @Test
    void missingUuidIsMintedBeforeTheSave() {
        Salary incoming = newSalary(null, 45000, SalaryType.NORMAL);

        SalaryService service = spy(new SalaryService());
        SalaryService selfProxy = mock(SalaryService.class);
        service.self = selfProxy;

        service.create(incoming);

        assertNotNull(incoming.getUuid());
        verify(selfProxy).createInTx(incoming);
    }

    @Test
    void lostInsertRaceIsRetriedOnceThroughTheSelfProxy() {
        Salary incoming = newSalary("fresh-uuid", 45000, SalaryType.NORMAL);

        // self is a SEPARATE mock: the retry must route through the Arc client proxy to get a
        // fresh transaction — a bare this.createInTx(...) would silently run without one.
        SalaryService service = spy(new SalaryService());
        SalaryService selfProxy = mock(SalaryService.class);
        service.self = selfProxy;
        doThrow(duplicateKeyViolation()).doNothing().when(selfProxy).createInTx(incoming);
        doReturn(Optional.empty()).when(service).findByUuid("fresh-uuid");

        // The loser of a concurrent insert race must converge, not throw.
        service.create(incoming);

        verify(selfProxy, times(2)).createInTx(incoming);
        verify(service, never()).createInTx(any(Salary.class));
    }

    @Test
    void unrelatedPersistenceExceptionIsNotRetried() {
        Salary incoming = newSalary("fresh-uuid", 45000, SalaryType.NORMAL);

        SalaryService service = spy(new SalaryService());
        SalaryService selfProxy = mock(SalaryService.class);
        service.self = selfProxy;
        doThrow(new PersistenceException("database unavailable")).when(selfProxy).createInTx(incoming);

        assertThrows(PersistenceException.class, () -> service.create(incoming));

        verify(selfProxy, times(1)).createInTx(incoming);
    }

    @Test
    void secondConsecutiveDuplicateFailurePropagatesFor409Mapping() {
        Salary incoming = newSalary("fresh-uuid", 45000, SalaryType.NORMAL);

        SalaryService service = spy(new SalaryService());
        SalaryService selfProxy = mock(SalaryService.class);
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
        // A caller holding a real row uuid moves its activefrom onto a month already owned by
        // another row of the same user. The by-uuid update path fails identically on every
        // attempt — it must surface as a 409 immediately.
        Salary incoming = newSalary("known-uuid", 45000, SalaryType.NORMAL);
        Salary ownRow = newSalary("known-uuid", 40000, SalaryType.NORMAL);

        SalaryService service = spy(new SalaryService());
        SalaryService selfProxy = mock(SalaryService.class);
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
        Salary incoming = newSalary("fresh-uuid", 45000, SalaryType.NORMAL);

        SalaryService service = spy(new SalaryService());
        SalaryService selfProxy = mock(SalaryService.class);
        service.self = selfProxy;
        doThrow(new PersistenceException(
                "could not execute statement [Duplicate entry 'a268e68b-2026-08-01' for key 'uq_salary_user_date']"))
                .doNothing().when(selfProxy).createInTx(incoming);
        doReturn(Optional.empty()).when(service).findByUuid("fresh-uuid");

        service.create(incoming);

        verify(selfProxy, times(2)).createInTx(incoming);
    }

    @Test
    void lostRaceConvergesOnWinnersRowThroughTheRealUnitOfWork() {
        // Composition test: the retry must RE-RUN the natural-key lookup and land on the winner's
        // row — not re-attempt the insert with a cached lookup result. HOURLY on both sides keeps
        // the type-flip hook and the create branch's previous-month check out of play.
        Salary winner = newSalary("winner-uuid", 500, SalaryType.HOURLY);
        Salary incoming = newSalary("fresh-uuid", 550, SalaryType.HOURLY);

        SalaryService service = spy(new SalaryService());
        service.self = service; // intentionally: this test drives the real createInTx twice
        doReturn(Optional.empty()).when(service).findByUuid("fresh-uuid");
        doReturn(Optional.empty()).doReturn(Optional.of(winner))
                .when(service).findByUserAndDate(USER, ACTIVE_FROM);
        doThrow(duplicateKeyViolation()).when(service).persistNew(incoming);
        doNothing().when(service).bulkUpdate(anyString(), any(Salary.class));
        doNothing().when(service).sendUpdateEvent(any(Salary.class));

        service.create(incoming);

        // First attempt: lookups miss, insert loses the race. Retry: lookup sees the winner and
        // reconciles via update — the insert is attempted exactly once, and no CREATE event is
        // ever emitted for the failed insert.
        verify(service, times(1)).persistNew(incoming);
        verify(service).bulkUpdate("winner-uuid", incoming);
        verify(service, times(2)).createInTx(incoming);
        verify(service, never()).sendCreateEvent(any(Salary.class));
    }

    @Test
    void createForUserRejectsForeignSalaryUuidWithoutWriting() {
        // Ownership guard (audit M1): a caller authorized for one employee must not overwrite
        // another employee's record by passing that record's uuid in the body. 404 (not 403) so
        // the endpoint does not confirm that a guessed salary uuid exists under another user.
        Salary incoming = newSalary("foreign-uuid", 45000, SalaryType.NORMAL);
        Salary foreign = new Salary("foreign-uuid", 50000, ACTIVE_FROM, "some-other-user");

        SalaryService service = spy(new SalaryService());
        SalaryService selfProxy = mock(SalaryService.class);
        service.self = selfProxy;
        doReturn(Optional.of(foreign)).when(service).findByUuid("foreign-uuid");

        assertThrows(NotFoundException.class, () -> service.createForUser(USER, incoming));

        verify(selfProxy, never()).createInTx(any(Salary.class));
    }

    @Test
    void createForUserForcesThePathUserOntoTheRecord() {
        Salary incoming = new Salary(null, 45000, ACTIVE_FROM, "body-supplied-user");

        SalaryService service = spy(new SalaryService());
        SalaryService selfProxy = mock(SalaryService.class);
        service.self = selfProxy;

        service.createForUser(USER, incoming);

        assertEquals(USER, incoming.getUseruuid());
        verify(selfProxy).createInTx(incoming);
    }

    /**
     * The exception shape Hibernate ORM 7 actually raises when {@code persistAndFlush()} trips
     * {@code uq_salary_user_date}: the Hibernate {@link ConstraintViolationException} is thrown
     * <b>directly</b> as the top-level exception (it extends jakarta {@link PersistenceException}
     * via JDBCException/HibernateException) — no wrapper.
     */
    private static PersistenceException duplicateKeyViolation() {
        return new ConstraintViolationException(
                "could not execute statement [Duplicate entry '" + USER + "-2026-08-01' for key 'uq_salary_user_date']",
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry '" + USER + "-2026-08-01' for key 'uq_salary_user_date'", "23000", 1062),
                "uq_salary_user_date");
    }

    private static Salary newSalary(String uuid, int amount, SalaryType type) {
        Salary salary = new Salary(uuid, amount, ACTIVE_FROM, USER);
        salary.setType(type);
        return salary;
    }
}
