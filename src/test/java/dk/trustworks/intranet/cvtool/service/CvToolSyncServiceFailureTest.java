package dk.trustworks.intranet.cvtool.service;

import dk.trustworks.intranet.cvtool.client.CvToolClient;
import dk.trustworks.intranet.cvtool.dto.CvToolEmployeeResponse;
import dk.trustworks.intranet.cvtool.dto.CvToolEmployeeSkinny;
import dk.trustworks.intranet.cvtool.service.CvToolSyncService.CvToolSyncException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The CV Tool sync silently "succeeded" while doing nothing for over a month:
 * {@code syncAllBaseCvs()} returned a {@code "FAILED: ..."} string, so JBeret
 * recorded exitStatus COMPLETED and the batch metric recorded outcome "success".
 * These tests pin the fail-loudly contract that replaced it.
 *
 * <p>Plain JUnit — a @QuarkusTest that aborts at boot is reported as a green
 * SKIP, which would make these assertions vacuous.
 */
class CvToolSyncServiceFailureTest {

    private static CvToolSyncService service(Optional<String> key, CvToolClient client) {
        CvToolSyncService service = new CvToolSyncService();
        service.subscriptionKey = key;
        service.cvToolClient = client;
        return service;
    }

    private static CvToolEmployeeSkinny employee(int id) {
        return new CvToolEmployeeSkinny(id, "Employee " + id, false, "uuid-" + id);
    }

    /** An employee CV Tool knows about but holds no CV for — counted as unchanged, never a failure. */
    private static CvToolEmployeeResponse employeeWithoutCv(int id) {
        return new CvToolEmployeeResponse(id, "Employee " + id, null, null,
                "uuid-" + id, false, null, null, null);
    }

    @Test
    void throwsWhenSubscriptionKeyIsMissing() {
        CvToolSyncException ex = assertThrows(CvToolSyncException.class,
                () -> service(Optional.empty(), mock(CvToolClient.class)).syncAllBaseCvs());

        assertTrue(ex.getMessage().contains("CVTOOL_SUBSCRIPTION_KEY"),
                "message should name the env var to set, was: " + ex.getMessage());
    }

    @Test
    void throwsWhenSubscriptionKeyIsBlank() {
        assertThrows(CvToolSyncException.class,
                () -> service(Optional.of("   "), mock(CvToolClient.class)).syncAllBaseCvs());
    }

    /**
     * The exact production failure mode: the remote rejects us with 401. It must
     * propagate, not become a returned string.
     */
    @Test
    void throwsWhenEmployeeListCallIsRejected() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenThrow(new WebApplicationException(401));

        CvToolSyncException ex = assertThrows(CvToolSyncException.class,
                () -> service(Optional.of("key"), client).syncAllBaseCvs());

        assertNotNull(ex.getCause(), "the underlying transport failure must be preserved");
    }

    @Test
    void throwsWhenEveryEmployeeFails() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenReturn(List.of(employee(1), employee(2)));
        when(client.getEmployee(anyInt())).thenThrow(new WebApplicationException(500));

        CvToolSyncException ex = assertThrows(CvToolSyncException.class,
                () -> service(Optional.of("key"), client).syncAllBaseCvs());

        assertTrue(ex.getMessage().contains("2 failed"),
                "message should report how many failed, was: " + ex.getMessage());
    }

    /**
     * A partial failure must fail the job too. The real run that exposed this
     * synced 127 employees, failed 9 on a uk_useruuid constraint violation, and
     * still reported COMPLETED with "0 failed" — because a persist failure
     * returned false, which the loop counted as "unchanged".
     */
    @Test
    void throwsWhenOnlySomeEmployeesFail() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenReturn(List.of(employee(1), employee(2)));
        when(client.getEmployee(1)).thenReturn(employeeWithoutCv(1));
        when(client.getEmployee(2)).thenThrow(new WebApplicationException(500));

        CvToolSyncException ex = assertThrows(CvToolSyncException.class,
                () -> service(Optional.of("key"), client).syncAllBaseCvs());

        assertTrue(ex.getMessage().contains("1 failed"),
                "a partial failure must still surface the count, was: " + ex.getMessage());
    }

    /**
     * A failure must never be bucketed as "unchanged" — that mis-count is what
     * made "0 failed" possible alongside 9 genuine failures.
     */
    @Test
    void failuresAreNotCountedAsUnchanged() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenReturn(List.of(employee(1)));
        when(client.getEmployee(1)).thenThrow(new WebApplicationException(500));

        CvToolSyncException ex = assertThrows(CvToolSyncException.class,
                () -> service(Optional.of("key"), client).syncAllBaseCvs());

        assertTrue(ex.getMessage().contains("0 unchanged"),
                "the failure must not inflate the unchanged count, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("1 failed"), ex.getMessage());
    }

    /**
     * A run with nothing to do is a legitimate success — deleted employees and
     * employees with no intra UUID are skipped, not failures.
     */
    @Test
    void returnsCompletedWhenEveryEmployeeIsSkipped() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenReturn(List.of(
                new CvToolEmployeeSkinny(1, "Deleted Person", true, "uuid-1"),
                new CvToolEmployeeSkinny(2, "Unlinked Person", false, null),
                new CvToolEmployeeSkinny(3, "Blank UUID Person", false, "  ")));

        String result = service(Optional.of("key"), client).syncAllBaseCvs();

        assertTrue(result.startsWith("COMPLETED:"), "was: " + result);
        assertTrue(result.contains("3 skipped"), "was: " + result);
    }

    /**
     * An employee with no CV is a skip, not an "unchanged" — and a run made
     * entirely of skips still succeeds. This is the bucket an unresolvable
     * Employee_UUID lands in: employee 118 (Thomas Mountford) points at a UUID
     * absent from the intra user table, which is bad data upstream rather than
     * a sync fault, so it must not take the whole nightly job down with it.
     */
    @Test
    void employeesWithNothingToSyncAreSkippedNotCountedAsUnchanged() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenReturn(List.of(employee(1), employee(2)));
        when(client.getEmployee(anyInt())).thenReturn(employeeWithoutCv(1));

        String result = service(Optional.of("key"), client).syncAllBaseCvs();

        assertTrue(result.startsWith("COMPLETED:"), "skips must not fail the job, was: " + result);
        assertTrue(result.contains("2 skipped"), "was: " + result);
        assertTrue(result.contains("0 unchanged"), "a skip is not an unchanged, was: " + result);
        assertTrue(result.contains("0 failed"), "was: " + result);
    }

    /**
     * The mixed case that matters in production: some employees sync, one is
     * unsyncable data. The job must stay green and still report the skip.
     */
    @Test
    void oneUnsyncableEmployeeDoesNotFailAnOtherwiseHealthyRun() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenReturn(List.of(
                employee(1), employee(2), new CvToolEmployeeSkinny(3, "Deleted", true, "uuid-3")));
        when(client.getEmployee(anyInt())).thenReturn(employeeWithoutCv(1));

        String result = service(Optional.of("key"), client).syncAllBaseCvs();

        assertTrue(result.startsWith("COMPLETED:"), "was: " + result);
        assertTrue(result.contains("3 skipped"), "deleted + unsyncable both skip, was: " + result);
        assertTrue(result.contains("0 failed"), "was: " + result);
    }

    @Test
    void emptyEmployeeListIsNotTreatedAsFailure() {
        CvToolClient client = mock(CvToolClient.class);
        when(client.getAllEmployees()).thenReturn(List.of());

        String result = service(Optional.of("key"), client).syncAllBaseCvs();

        assertEquals("COMPLETED: CV Tool sync completed: 0 synced, 0 unchanged, 0 skipped, 0 failed (out of 0 total)",
                result);
    }
}
