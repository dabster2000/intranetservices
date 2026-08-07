package dk.trustworks.intranet.dao.workservice.services;

import dk.trustworks.intranet.dao.workservice.model.Week;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Plain unit test (no DB / no @QuarkusTest) for {@link WeekService#save(Week)} and
 * {@link WeekService#updateSorting(String, int)}.
 *
 * <p>Reproduces the production bug where POST /weeks returned HTTP 500 on a {@code unique_task}
 * (taskuuid, useruuid, weeknumber, year) collision: the frontend mints a fresh uuid on every
 * "add task to week" and does not dedupe, so a double-click / concurrent tab / add-delete-readd
 * re-submitted an already-existing tuple, the blind {@code persist()} violated the unique key, and
 * — because the flush was deferred to commit — it surfaced as a 500 instead of an idempotent success.
 *
 * <p>The Panache tuple lookup is isolated behind the {@link WeekService#findExistingWeek(Week)} seam
 * and the entity persist is stubbed, so the idempotency decision is exercised without a live database.
 */
class WeekServiceTest {

    @Test
    void duplicateTupleIsReconciledToExistingRow_andNothingIsInserted() {
        // An identical (taskuuid, useruuid, weeknumber, year) already lives on the user's week,
        // with a meaningful display order set previously via the reorder (PUT) flow.
        Week existing = newWeek("existing-uuid", "task-1", "user-1", 25, 2026);
        existing.setSorting(7);

        // The re-add the frontend sends: a brand-new client uuid, sorting omitted (defaults to 0).
        Week incoming = spy(newWeek("fresh-client-uuid", "task-1", "user-1", 25, 2026));
        incoming.setSorting(0);

        WeekService service = spy(new WeekService());
        doReturn(Optional.of(existing)).when(service).findExistingWeek(any(Week.class));

        Week result = service.save(incoming);

        assertSame(existing, result, "a duplicate save must return the pre-existing row, not insert a new one");
        assertEquals("existing-uuid", result.getUuid(), "must reconcile to the existing uuid, not the client's fresh uuid");
        assertEquals(7, existing.getSorting(), "an idempotent re-add must not clobber the existing display order");
        // The crux of the fix: no INSERT is attempted for an already-present tuple, so unique_task never fires.
        verify(incoming, never()).persistAndFlush();
        verify(incoming, never()).persist();
    }

    @Test
    void newTupleIsPersisted() {
        // A genuinely new task on the week: no existing row for the tuple.
        Week incoming = spy(newWeek("fresh-uuid", "task-2", "user-2", 26, 2026));
        doNothing().when(incoming).persistAndFlush();

        WeekService service = spy(new WeekService());
        doReturn(Optional.empty()).when(service).findExistingWeek(any(Week.class));

        Week result = service.save(incoming);

        assertSame(incoming, result);
        // Flushed (not a deferred commit) so a concurrent-insert race surfaces as a catchable 409.
        verify(incoming).persistAndFlush();
    }

    @Test
    void updateSortingChangesOnlyTheSortingField() {
        // The reorder flow (PUT /weeks/{uuid}) must update display order without being able
        // to touch the row's identity tuple — that is the whole point of the narrow endpoint.
        Week existing = newWeek("existing-uuid", "task-1", "user-1", 25, 2026);
        existing.setSorting(0);
        existing.setWorkas("someone-else");

        WeekService service = spy(new WeekService());
        doReturn(existing).when(service).findWeek("existing-uuid");

        service.updateSorting("existing-uuid", 3);

        assertEquals(3, existing.getSorting(), "the new display order must be applied to the loaded row");
        assertEquals("task-1", existing.getTaskuuid());
        assertEquals("user-1", existing.getUseruuid());
        assertEquals(25, existing.getWeeknumber());
        assertEquals(2026, existing.getYear());
        assertEquals("someone-else", existing.getWorkas());
    }

    @Test
    void updateSortingOnUnknownWeekIsA404NotASilentSuccess() {
        // A vanished row (deleted in another tab) must surface as 404 so the frontend can
        // report the partial failure and refetch — not pretend the order was saved.
        WeekService service = spy(new WeekService());
        doReturn(null).when(service).findWeek("missing-uuid");

        assertThrows(NotFoundException.class, () -> service.updateSorting("missing-uuid", 1));
    }

    private static Week newWeek(String uuid, String taskuuid, String useruuid, int weeknumber, int year) {
        Week week = new Week();
        week.setUuid(uuid);
        week.setTaskuuid(taskuuid);
        week.setUseruuid(useruuid);
        week.setWeeknumber(weeknumber);
        week.setYear(year);
        return week;
    }
}
