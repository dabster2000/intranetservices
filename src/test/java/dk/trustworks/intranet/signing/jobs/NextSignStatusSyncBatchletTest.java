package dk.trustworks.intranet.signing.jobs;

import dk.trustworks.intranet.agreementservice.services.AgreementRecorder;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.recruitmentservice.services.S3EmployeePromotionService;
import dk.trustworks.intranet.signing.services.EmployeeSigningArchivalService;
import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.signing.repository.SigningCaseRepository;
import dk.trustworks.intranet.utils.dto.signing.SigningCaseStatus;
import dk.trustworks.intranet.utils.services.SigningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Plain JUnit and Mockito coverage for terminal-case skipping and retry exhaustion.
 */
class NextSignStatusSyncBatchletTest {

    private SigningCaseRepository signingCaseRepository;
    private SigningService signingService;
    private EmployeeSigningArchivalService employeeSigningArchivalService;
    private S3EmployeePromotionService s3EmployeePromotionService;
    private SlackService slackService;
    private AgreementRecorder agreementRecorder;
    private NextSignStatusSyncBatchlet batchlet;

    @BeforeEach
    void setUp() {
        signingCaseRepository = mock(SigningCaseRepository.class);
        signingService = mock(SigningService.class);
        employeeSigningArchivalService = mock(EmployeeSigningArchivalService.class);
        s3EmployeePromotionService = mock(S3EmployeePromotionService.class);
        slackService = mock(SlackService.class);
        // The catch-up sweeps read the repository / a static Panache finder;
        // the mocked repository returns an empty page, and no case in these
        // tests reaches completion, so neither writer is ever invoked.
        when(signingCaseRepository.find(anyString())).thenReturn(mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class, RETURNS_DEEP_STUBS));

        // Mockito's int default (0) makes both recorder paths no-ops.
        agreementRecorder = mock(AgreementRecorder.class);

        batchlet = new NextSignStatusSyncBatchlet();
        batchlet.signingCaseRepository = signingCaseRepository;
        batchlet.signingService = signingService;
        batchlet.employeeSigningArchivalService = employeeSigningArchivalService;
        batchlet.s3EmployeePromotionService = s3EmployeePromotionService;
        batchlet.slackService = slackService;
        batchlet.agreementRecorder = agreementRecorder;
    }

    @Test
    void alreadyTerminalCase_isSkippedWithoutCallingNextSign() throws Exception {
        SigningCase signingCase = signingCase("expired", "COMPLETED", 4);
        when(signingCaseRepository.findCasesNeedingStatusFetch(5, 15))
            .thenReturn(List.of(signingCase));
        when(signingService.markCaseSkippedIfTerminal(signingCase)).thenAnswer(invocation -> {
            signingCase.setProcessingStatus("SKIPPED");
            return true;
        });

        String result = batchlet.doProcess();

        assertEquals("COMPLETED: total=1, successful=0, failed=0, skipped=1, archived=0, promotionsRedriven=0, agreementsRecorded=0", result);
        assertEquals("SKIPPED", signingCase.getProcessingStatus());
        verify(signingService, never()).getStatus(anyString());
        verifyNoInteractions(employeeSigningArchivalService, slackService);
    }

    @Test
    void remoteTerminalStatus_isCountedAsSkippedAndBypassesArchival() throws Exception {
        SigningCase signingCase = signingCase("pending", "COMPLETED", 4);
        SigningCaseStatus expired = signingStatus("expired");
        when(signingCaseRepository.findCasesNeedingStatusFetch(5, 15))
            .thenReturn(List.of(signingCase));
        when(signingService.getStatus(signingCase.getCaseKey())).thenReturn(expired);
        when(signingService.updateCaseWithFetchedStatus(signingCase, expired)).thenAnswer(invocation -> {
            signingCase.setStatus("expired");
            signingCase.setProcessingStatus("SKIPPED");
            return true;
        });

        String result = batchlet.doProcess();

        assertEquals("COMPLETED: total=1, successful=0, failed=0, skipped=1, archived=0, promotionsRedriven=0, agreementsRecorded=0", result);
        verify(signingService).getStatus(signingCase.getCaseKey());
        verifyNoInteractions(employeeSigningArchivalService, slackService);
    }

    @Test
    void fifthTimeoutFailure_reachesExistingTerminalRetryGuard() throws Exception {
        SigningCase signingCase = signingCase("pending", "FAILED", 4);
        when(signingCaseRepository.findCasesNeedingStatusFetch(5, 15))
            .thenReturn(List.of(signingCase));
        when(signingService.getStatus(signingCase.getCaseKey())).thenThrow(
            new SigningService.SigningException(
                "Read timed out",
                new SocketTimeoutException("Read timed out")
            )
        );
        doAnswer(invocation -> {
            SigningCase failedCase = invocation.getArgument(0);
            failedCase.setProcessingStatus("FAILED");
            failedCase.setRetryCount(failedCase.getRetryCount() + 1);
            return null;
        }).when(signingService).markCaseFetchFailed(signingCase, "Read timed out");

        String result = batchlet.doProcess();

        assertEquals("COMPLETED: total=1, successful=0, failed=1, skipped=1, archived=0, promotionsRedriven=0, agreementsRecorded=0", result);
        assertEquals(5, signingCase.getRetryCount());
        verify(signingService).markCaseFetchFailed(signingCase, "Read timed out");
        // Transient errors must NOT be abandoned — the repository's slow
        // retry lane re-drives them after the backoff.
        verify(signingService, never()).markCaseMissingInNextsign(signingCase);
        verifyNoInteractions(employeeSigningArchivalService, slackService);
    }

    @Test
    void fifthConsecutive404_isAbandonedAsMissingFromNextsign() throws Exception {
        SigningCase signingCase = signingCase("pending", "FAILED", 4);
        when(signingCaseRepository.findCasesNeedingStatusFetch(5, 15))
            .thenReturn(List.of(signingCase));
        when(signingService.getStatus(signingCase.getCaseKey())).thenThrow(
            new SigningService.SigningException("Nextsign API error: 404 Not Found", null)
        );
        doAnswer(invocation -> {
            SigningCase failedCase = invocation.getArgument(0);
            failedCase.setProcessingStatus("FAILED");
            failedCase.setRetryCount(failedCase.getRetryCount() + 1);
            return null;
        }).when(signingService).markCaseFetchFailed(signingCase, "Case not available in NextSign");

        String result = batchlet.doProcess();

        assertEquals("COMPLETED: total=1, successful=0, failed=1, skipped=1, archived=0, promotionsRedriven=0, agreementsRecorded=0", result);
        assertEquals(5, signingCase.getRetryCount());
        // A case that 404s across the whole fast-retry window no longer
        // exists in NextSign — it must be permanently abandoned, not left
        // to drip through the slow retry lane forever.
        verify(signingService).markCaseMissingInNextsign(signingCase);
        verifyNoInteractions(employeeSigningArchivalService, slackService);
    }

    @Test
    void typedCaseNotFound_pastRetryBudget_isAbandonedNotSlowLaned() throws Exception {
        // NextSign's documented "case gone" shape is HTTP 200 with
        // {"message":"Case not found"} — surfaced as the typed exception,
        // whose message contains no "404". Before the typed classification
        // this fell into the generic-error path and dripped through the
        // 6-hour slow lane forever (prod incident 2026-08-09 19:12Z).
        SigningCase signingCase = signingCase("pending", "FAILED", 6);
        when(signingCaseRepository.findCasesNeedingStatusFetch(5, 15))
            .thenReturn(List.of(signingCase));
        when(signingService.getStatus(signingCase.getCaseKey())).thenThrow(
            new SigningService.CaseNotFoundInNextsignException(
                "Case not found in NextSign: " + signingCase.getCaseKey(), null)
        );
        doAnswer(invocation -> {
            SigningCase failedCase = invocation.getArgument(0);
            failedCase.setProcessingStatus("FAILED");
            failedCase.setRetryCount(failedCase.getRetryCount() + 1);
            return null;
        }).when(signingService).markCaseFetchFailed(signingCase, "Case not available in NextSign");

        String result = batchlet.doProcess();

        assertEquals("COMPLETED: total=1, successful=0, failed=1, skipped=1, archived=0, promotionsRedriven=0, agreementsRecorded=0", result);
        verify(signingService).markCaseMissingInNextsign(signingCase);
        verifyNoInteractions(employeeSigningArchivalService, slackService);
    }

    private static SigningCase signingCase(String status, String processingStatus, int retryCount) {
        return SigningCase.builder()
            .caseKey("69aac8f810e59e0f97ea2254")
            .userUuid("11111111-1111-1111-1111-111111111111")
            .documentName("Expired contract")
            .status(status)
            .processingStatus(processingStatus)
            .retryCount(retryCount)
            .build();
    }

    private static SigningCaseStatus signingStatus(String status) {
        return new SigningCaseStatus(
            "69aac8f810e59e0f97ea2254",
            status,
            "Expired contract",
            null,
            List.of(),
            1,
            0,
            "PENDING"
        );
    }
}
