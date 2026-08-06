package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.domain.user.entity.UserCareerLevel;
import dk.trustworks.intranet.exceptions.InconsistantDataException;
import dk.trustworks.intranet.userservice.model.enums.CareerLevel;
import dk.trustworks.intranet.userservice.model.enums.CareerTrack;
import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * POST /users/{useruuid}/careerlevels.
 *
 * <p>Reproduces the production bug where the endpoint returned HTTP 500 on a
 * {@code uq_career_level_user_date} (useruuid, active_from) collision: the BFF mints a fresh uuid
 * on every save, so a same-day re-save arrived with an unknown uuid, missed the by-uuid upsert
 * branch, and the blind {@code persist()} violated the unique key. Because the flush was deferred
 * to JTA commit, the violation surfaced from {@code TwoPhaseCoordinator.beforeCompletion} as an
 * {@code ArcUndeclaredThrowableException} and fell through to {@code GenericExceptionMapper} as a
 * 500 instead of an update.
 *
 * <p>The Panache lookups, the bulk update, and the entity persist are isolated behind the
 * {@link CareerLevelService#findByUuid}, {@link CareerLevelService#findByUserAndDate},
 * {@link CareerLevelService#bulkUpdate} and {@link CareerLevelService#persistNew} seams, so the
 * reconcile decision is exercised without a live database. Retry tests wire {@code self} to a
 * <b>separate</b> mock instance so a regression from {@code self.createInTx(...)} to a bare
 * {@code createInTx(...)} (which would bypass the Arc interceptor stack and run without a
 * transaction) fails the suite.
 */
class CareerLevelServiceTest {

    private static final String USER = "a268e68b-d539-4480-8fde-579da776c4db";
    private static final LocalDate ACTIVE_FROM = LocalDate.of(2026, 8, 5);

    @Test
    void sameDayResaveWithFreshUuidUpdatesExistingRow_andNothingIsInserted() {
        // The prod incident: a row for (user, 2026-08-05) already exists under another uuid,
        // and the BFF re-save arrives with a freshly minted uuid the DB has never seen.
        UserCareerLevel existing = newLevel("existing-uuid", CareerTrack.ADVISORY, CareerLevel.LEAD_CONSULTANT);
        UserCareerLevel incoming = newLevel("fresh-bff-uuid", CareerTrack.ADVISORY, CareerLevel.MANAGING_CONSULTANT);

        CareerLevelService service = spy(new CareerLevelService());
        doReturn(Optional.empty()).when(service).findByUuid("fresh-bff-uuid");
        doReturn(Optional.of(existing)).when(service).findByUserAndDate(USER, ACTIVE_FROM);
        doNothing().when(service).bulkUpdate(anyString(), any(UserCareerLevel.class));

        // A same-day re-save must complete normally (2xx) — no exception thrown to the mapper.
        service.createInTx(incoming);

        // Crux of the fix: no INSERT is attempted for an already-present (user, date), so
        // uq_career_level_user_date never fires and the request cannot 500 at commit. The update
        // targets the existing row's uuid — the incoming fresh uuid is discarded.
        verify(service, never()).persistNew(any(UserCareerLevel.class));
        verify(service).bulkUpdate("existing-uuid", incoming);
        // The managed entity must stay clean: a mutated managed row would be dirty-check-flushed
        // AGAIN at JTA commit (Hibernate 7 does not auto-flush before JPQL DML), where a lost race
        // with a concurrent delete escapes as an unmappable commit-time 500.
        assertEquals(CareerLevel.LEAD_CONSULTANT, existing.getCareerLevel());
        assertEquals(CareerTrack.ADVISORY, existing.getCareerTrack());
    }

    @Test
    void resaveWithKnownUuidStillUpdatesThatRow() {
        // The pre-existing upsert contract: a caller holding the row's real uuid updates it,
        // without consulting the natural key.
        UserCareerLevel existing = newLevel("known-uuid", CareerTrack.ADVISORY, CareerLevel.LEAD_CONSULTANT);
        UserCareerLevel incoming = newLevel("known-uuid", CareerTrack.ADVISORY, CareerLevel.MANAGING_CONSULTANT);

        CareerLevelService service = spy(new CareerLevelService());
        doReturn(Optional.of(existing)).when(service).findByUuid("known-uuid");
        doNothing().when(service).bulkUpdate(anyString(), any(UserCareerLevel.class));

        service.createInTx(incoming);

        verify(service).bulkUpdate("known-uuid", incoming);
        verify(service, never()).findByUserAndDate(anyString(), any(LocalDate.class));
        verify(service, never()).persistNew(any(UserCareerLevel.class));
    }

    @Test
    void newUserDateIsPersistedWithFlush() {
        UserCareerLevel incoming = spy(newLevel("fresh-uuid", CareerTrack.DELIVERY, CareerLevel.CONSULTANT));

        CareerLevelService service = spy(new CareerLevelService());
        doReturn(Optional.empty()).when(service).findByUuid(anyString());
        doReturn(Optional.empty()).when(service).findByUserAndDate(anyString(), any(LocalDate.class));
        doNothing().when(incoming).persistAndFlush();

        service.createInTx(incoming);

        verify(service, never()).bulkUpdate(anyString(), any(UserCareerLevel.class));
        // Flushed (not a deferred commit) so a concurrent-insert race surfaces as a catchable
        // PersistenceException the orchestrator can retry / map to 409 — never a deferred 500.
        verify(incoming).persistAndFlush();
    }

    @Test
    void lostInsertRaceIsRetriedOnceThroughTheSelfProxy() {
        UserCareerLevel incoming = newLevel("fresh-uuid", CareerTrack.ADVISORY, CareerLevel.MANAGING_CONSULTANT);

        // self is a SEPARATE mock: the retry must route through the Arc client proxy to get a
        // fresh transaction — a bare this.createInTx(...) would silently run without one.
        CareerLevelService service = spy(new CareerLevelService());
        CareerLevelService selfProxy = mock(CareerLevelService.class);
        service.self = selfProxy;
        doThrow(duplicateKeyViolation()).doNothing().when(selfProxy).createInTx(incoming);
        doReturn(Optional.empty()).when(service).findByUuid("fresh-uuid");

        // The loser of a concurrent insert race must converge, not throw.
        service.create(incoming);

        verify(selfProxy, times(2)).createInTx(incoming);
        verify(service, never()).createInTx(any(UserCareerLevel.class));
    }

    @Test
    void unrelatedPersistenceExceptionIsNotRetried() {
        UserCareerLevel incoming = newLevel("fresh-uuid", CareerTrack.ADVISORY, CareerLevel.MANAGING_CONSULTANT);

        CareerLevelService service = spy(new CareerLevelService());
        CareerLevelService selfProxy = mock(CareerLevelService.class);
        service.self = selfProxy;
        doThrow(new PersistenceException("database unavailable")).when(selfProxy).createInTx(incoming);

        assertThrows(PersistenceException.class, () -> service.create(incoming));

        verify(selfProxy, times(1)).createInTx(incoming);
    }

    @Test
    void secondConsecutiveDuplicateFailurePropagatesFor409Mapping() {
        UserCareerLevel incoming = newLevel("fresh-uuid", CareerTrack.ADVISORY, CareerLevel.MANAGING_CONSULTANT);

        CareerLevelService service = spy(new CareerLevelService());
        CareerLevelService selfProxy = mock(CareerLevelService.class);
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
        // A caller holding a real row uuid moves its activeFrom onto a date already owned by
        // another row of the same user. The by-uuid update path fails identically on every
        // attempt — retrying would just re-run a doomed transaction and log a misleading
        // "concurrent insert" WARN. It must surface as a 409 immediately.
        UserCareerLevel incoming = newLevel("known-uuid", CareerTrack.ADVISORY, CareerLevel.MANAGING_CONSULTANT);
        UserCareerLevel ownRow = newLevel("known-uuid", CareerTrack.ADVISORY, CareerLevel.LEAD_CONSULTANT);

        CareerLevelService service = spy(new CareerLevelService());
        CareerLevelService selfProxy = mock(CareerLevelService.class);
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
        UserCareerLevel incoming = newLevel("fresh-uuid", CareerTrack.ADVISORY, CareerLevel.MANAGING_CONSULTANT);

        CareerLevelService service = spy(new CareerLevelService());
        CareerLevelService selfProxy = mock(CareerLevelService.class);
        service.self = selfProxy;
        doThrow(new PersistenceException(
                "could not execute statement [Duplicate entry 'a268e68b-2026-08-05' for key 'uq_career_level_user_date']"))
                .doNothing().when(selfProxy).createInTx(incoming);
        doReturn(Optional.empty()).when(service).findByUuid("fresh-uuid");

        service.create(incoming);

        verify(selfProxy, times(2)).createInTx(incoming);
    }

    @Test
    void lostRaceConvergesOnWinnersRowThroughTheRealUnitOfWork() {
        // Composition test: the retry must RE-RUN the natural-key lookup and land on the winner's
        // row — not re-attempt the insert with a cached lookup result.
        UserCareerLevel winner = newLevel("winner-uuid", CareerTrack.ADVISORY, CareerLevel.LEAD_CONSULTANT);
        UserCareerLevel incoming = newLevel("fresh-uuid", CareerTrack.ADVISORY, CareerLevel.MANAGING_CONSULTANT);

        CareerLevelService service = spy(new CareerLevelService());
        service.self = service; // intentionally: this test drives the real createInTx twice
        doReturn(Optional.empty()).when(service).findByUuid("fresh-uuid");
        doReturn(Optional.empty()).doReturn(Optional.of(winner))
                .when(service).findByUserAndDate(USER, ACTIVE_FROM);
        doThrow(duplicateKeyViolation()).when(service).persistNew(incoming);
        doNothing().when(service).bulkUpdate(anyString(), any(UserCareerLevel.class));

        service.create(incoming);

        // First attempt: lookups miss, insert loses the race. Retry: lookup sees the winner and
        // reconciles via update — the insert is attempted exactly once.
        verify(service, times(1)).persistNew(incoming);
        verify(service).bulkUpdate("winner-uuid", incoming);
        verify(service, times(2)).createInTx(incoming);
    }

    @Test
    void trackMismatchIsRejectedAsInconsistantDataBeforeAnyTransaction() {
        // MANAGING_CONSULTANT belongs to ADVISORY — pairing it with DELIVERY must 400, not 500.
        UserCareerLevel incoming = newLevel("fresh-uuid", CareerTrack.DELIVERY, CareerLevel.MANAGING_CONSULTANT);

        CareerLevelService service = spy(new CareerLevelService());
        CareerLevelService selfProxy = mock(CareerLevelService.class);
        service.self = selfProxy;

        assertThrows(InconsistantDataException.class, () -> service.create(incoming));

        verify(selfProxy, never()).createInTx(any(UserCareerLevel.class));
        verify(service, never()).createInTx(any(UserCareerLevel.class));
    }

    @Test
    void blankUuidIsIgnored() {
        UserCareerLevel incoming = newLevel(null, CareerTrack.DELIVERY, CareerLevel.CONSULTANT);

        CareerLevelService service = spy(new CareerLevelService());
        CareerLevelService selfProxy = mock(CareerLevelService.class);
        service.self = selfProxy;

        service.create(incoming);

        verify(selfProxy, never()).createInTx(any(UserCareerLevel.class));
        verify(service, never()).createInTx(any(UserCareerLevel.class));
    }

    /**
     * The exception shape Hibernate ORM 7 actually raises when {@code persistAndFlush()} trips
     * {@code uq_career_level_user_date}: the Hibernate {@link ConstraintViolationException} is
     * thrown <b>directly</b> as the top-level exception (it extends jakarta
     * {@link PersistenceException} via JDBCException/HibernateException) — no wrapper.
     */
    private static PersistenceException duplicateKeyViolation() {
        return new ConstraintViolationException(
                "could not execute statement [Duplicate entry '" + USER + "-2026-08-05' for key 'uq_career_level_user_date']",
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry '" + USER + "-2026-08-05' for key 'uq_career_level_user_date'", "23000", 1062),
                "uq_career_level_user_date");
    }

    private static UserCareerLevel newLevel(String uuid, CareerTrack track, CareerLevel level) {
        UserCareerLevel careerLevel = new UserCareerLevel(USER, ACTIVE_FROM, track, level);
        careerLevel.setUuid(uuid);
        return careerLevel;
    }
}
