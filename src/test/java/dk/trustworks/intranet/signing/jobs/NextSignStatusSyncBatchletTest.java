package dk.trustworks.intranet.signing.jobs;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
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
    private SharePointService sharePointService;
    private SlackService slackService;
    private NextSignStatusSyncBatchlet batchlet;

    @BeforeEach
    void setUp() {
        signingCaseRepository = mock(SigningCaseRepository.class);
        signingService = mock(SigningService.class);
        sharePointService = mock(SharePointService.class);
        slackService = mock(SlackService.class);

        batchlet = new NextSignStatusSyncBatchlet();
        batchlet.signingCaseRepository = signingCaseRepository;
        batchlet.signingService = signingService;
        batchlet.sharePointService = sharePointService;
        batchlet.slackService = slackService;
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

        assertEquals("COMPLETED: total=1, successful=0, failed=0, skipped=1", result);
        assertEquals("SKIPPED", signingCase.getProcessingStatus());
        verify(signingService, never()).getStatus(anyString());
        verifyNoInteractions(sharePointService, slackService);
    }

    @Test
    void remoteTerminalStatus_isCountedAsSkippedAndBypassesSharePoint() throws Exception {
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

        assertEquals("COMPLETED: total=1, successful=0, failed=0, skipped=1", result);
        verify(signingService).getStatus(signingCase.getCaseKey());
        verifyNoInteractions(sharePointService, slackService);
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

        assertEquals("COMPLETED: total=1, successful=0, failed=1, skipped=1", result);
        assertEquals(5, signingCase.getRetryCount());
        verify(signingService).markCaseFetchFailed(signingCase, "Read timed out");
        verifyNoInteractions(sharePointService, slackService);
    }

    private static SigningCase signingCase(String status, String processingStatus, int retryCount) {
        return SigningCase.builder()
            .caseKey("69aac8f810e59e0f97ea2254")
            .userUuid("11111111-1111-1111-1111-111111111111")
            .documentName("Expired contract")
            .status(status)
            .processingStatus(processingStatus)
            .retryCount(retryCount)
            .sharepointLocationUuid("22222222-2222-2222-2222-222222222222")
            .sharepointUploadStatus("PENDING")
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
            "22222222-2222-2222-2222-222222222222",
            "PENDING",
            null,
            null
        );
    }
}
