package dk.trustworks.intranet.dao.workservice.services;

import dk.trustworks.intranet.dao.workservice.model.Work;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Plain unit test (no DB / no @QuarkusTest) for the idempotent unit of work
 * {@link WorkService#persistOrUpdateInTx(Work)} behind POST /work.
 *
 * <p>Reproduces the production bug where POST /work returned HTTP 500 on a {@code uq_work_user_date_task}
 * (useruuid, registered, taskuuid) collision: the frontend re-submits the same (user, date, task) row
 * on double-click / retry / concurrent tabs, and the blind {@code Work.persist(...)} in the insert
 * branch violated the unique key. Because the flush was deferred to JTA commit, the violation surfaced
 * from {@code TwoPhaseCoordinator.beforeCompletion} as an {@code ArcUndeclaredThrowableException} and
 * fell through to {@code GenericExceptionMapper} as a 500 instead of an idempotent success.
 *
 * <p>The Panache tuple lookup and bulk update are isolated behind the {@link WorkService#findExistingWork}
 * and {@link WorkService#updateExistingWork} seams, and the entity persist is stubbed, so the
 * idempotency decision is exercised without a live database.
 */
class WorkServiceTest {

    private static final LocalDate REGISTERED = LocalDate.of(2026, 7, 6);
    private static final String USER = "dae02077-5419-4a28-aacb-1f7d64e21f6b";
    private static final String TASK = "a7314f77-5e03-4f56-8b1c-0562e601f22f";

    @Test
    void duplicateSubmitIsReconciledToExistingRow_andNothingIsInserted() {
        // An identical (registered, useruuid, taskuuid) row already exists (not paid out).
        Work existing = newWork("existing-uuid", 4.0);

        // The re-submit the frontend sends: a brand-new payload for the same tuple.
        Work incoming = spy(newWork("fresh-client-uuid", 4.0));

        WorkService service = spy(new WorkService());
        doReturn(List.of(existing)).when(service).findExistingWork(any(LocalDate.class), anyString(), anyString());
        doNothing().when(service).updateExistingWork(any(Work.class));

        // A duplicate submit must complete normally (2xx) — no exception thrown to the mapper.
        service.persistOrUpdateInTx(incoming);

        // Crux of the fix: no INSERT is attempted for an already-present tuple, so uq_work_user_date_task
        // never fires and the request cannot 500 at commit.
        verify(incoming, never()).persistAndFlush();
        verify(incoming, never()).persist();
        // It reconciles to the existing row and updates in place.
        verify(service).updateExistingWork(incoming);
        assertEquals("existing-uuid", incoming.getUuid(),
                "must reconcile to the existing row's uuid, not insert under the client's fresh uuid");
    }

    @Test
    void duplicateSubmitForPaidOutRowIsSkipped_andNothingIsInserted() {
        // The existing row was already paid out — it must not be touched.
        Work existing = newWork("paid-uuid", 4.0);
        existing.setPaidOut(LocalDateTime.of(2026, 7, 1, 0, 0));

        Work incoming = spy(newWork("fresh-client-uuid", 8.0));

        WorkService service = spy(new WorkService());
        doReturn(List.of(existing)).when(service).findExistingWork(any(LocalDate.class), anyString(), anyString());

        service.persistOrUpdateInTx(incoming);

        verify(incoming, never()).persistAndFlush();
        verify(incoming, never()).persist();
        verify(service, never()).updateExistingWork(any(Work.class));
    }

    @Test
    void newTupleIsPersistedWithFlush() {
        // A genuinely new (registered, useruuid, taskuuid): no existing row.
        Work incoming = spy(newWork(null, 4.0));
        doNothing().when(incoming).persistAndFlush();

        WorkService service = spy(new WorkService());
        doReturn(Collections.emptyList()).when(service).findExistingWork(any(LocalDate.class), anyString(), anyString());

        service.persistOrUpdateInTx(incoming);

        // Flushed (not a deferred commit) so a concurrent-insert race surfaces as a catchable
        // PersistenceException the caller can retry / map to 409 — never a deferred 500.
        verify(incoming).persistAndFlush();
        verify(service, never()).updateExistingWork(any(Work.class));
        assertNotNull(incoming.getUuid(), "a new row must be assigned a uuid before insert");
    }

    private static Work newWork(String uuid, double workduration) {
        Work work = new Work();
        work.setUuid(uuid);
        work.setRegistered(REGISTERED);
        work.setUseruuid(USER);
        work.setTaskuuid(TASK);
        work.setWorkduration(workduration);
        return work;
    }
}
