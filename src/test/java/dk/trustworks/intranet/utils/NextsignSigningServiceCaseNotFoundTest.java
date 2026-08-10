package dk.trustworks.intranet.utils;

import dk.trustworks.intranet.utils.client.NextsignClient;
import dk.trustworks.intranet.utils.dto.nextsign.GetCaseStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for NextSign's documented "case gone" response shape:
 * HTTP 200 with body {@code {"message": "Case not found"}} — no status field,
 * no case field (docs/finalized/external-apis/nextsign-api.md). Before the
 * typed classification this deserialized to status=null and was thrown as the
 * generic "Nextsign API error status: null", which the status-sync batchlet
 * retried forever (prod incident starting 2026-08-09 19:12Z).
 */
class NextsignSigningServiceCaseNotFoundTest {

    private static final String CASE_KEY = "6940495ab842c78395f96557";

    private NextsignClient nextsignClient;
    private NextsignSigningService service;

    @BeforeEach
    void setUp() {
        nextsignClient = mock(NextsignClient.class);
        service = new NextsignSigningService();
        service.nextsignClient = nextsignClient;
        service.company = "test-company";
        service.bearerToken = "test-token";
    }

    @Test
    void notFoundBody_throwsTypedCaseNotFound() {
        when(nextsignClient.getCaseStatus(anyString(), anyString(), anyString()))
            .thenReturn(new GetCaseStatusResponse(null, "Case not found", null));

        assertThrows(NextsignSigningService.NextsignCaseNotFoundException.class,
            () -> service.getCaseStatus(CASE_KEY));
    }

    @Test
    void unknownErrorShape_staysGenericNextsignException() {
        // An unmodeled response without the not-found message must NOT be
        // classified as "case gone" — it stays a retryable API error.
        when(nextsignClient.getCaseStatus(anyString(), anyString(), anyString()))
            .thenReturn(new GetCaseStatusResponse(null, null, null));

        NextsignSigningService.NextsignException thrown = assertThrows(
            NextsignSigningService.NextsignException.class,
            () -> service.getCaseStatus(CASE_KEY));
        assertFalse(thrown instanceof NextsignSigningService.NextsignCaseNotFoundException);
    }

    @Test
    void isCaseNotFound_requiresMissingCaseData() {
        assertTrue(new GetCaseStatusResponse(null, "Case not found", null).isCaseNotFound());
        assertFalse(new GetCaseStatusResponse(null, null, null).isCaseNotFound());
        assertFalse(new GetCaseStatusResponse("error", "Invalid company id", null).isCaseNotFound());
    }
}
