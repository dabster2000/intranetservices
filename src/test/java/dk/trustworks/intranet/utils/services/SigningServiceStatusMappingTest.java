package dk.trustworks.intranet.utils.services;

import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.signing.repository.SigningCaseRepository;
import dk.trustworks.intranet.utils.NextsignSigningService;
import dk.trustworks.intranet.utils.dto.nextsign.GetCaseStatusResponse;
import dk.trustworks.intranet.utils.dto.signing.SigningCaseStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for NextSign status derivation and terminal persistence.
 * No Quarkus runtime or database is required.
 */
class SigningServiceStatusMappingTest {

    private static final String CASE_KEY = "69aac8f810e59e0f97ea2254";

    private NextsignSigningService nextsignService;
    private SigningCaseRepository signingCaseRepository;
    private SigningService signingService;

    @BeforeEach
    void setUp() {
        nextsignService = mock(NextsignSigningService.class);
        signingCaseRepository = mock(SigningCaseRepository.class);

        signingService = new SigningService();
        signingService.nextsignService = nextsignService;
        signingService.signingCaseRepository = signingCaseRepository;
    }

    @Test
    void expiredAvailabilityWithPendingSigner_mapsToExpired() {
        when(nextsignService.getCaseStatus(CASE_KEY)).thenReturn(expiredResponse("pending"));
        when(signingCaseRepository.findByCaseKey(CASE_KEY)).thenReturn(Optional.empty());

        SigningCaseStatus status = signingService.getStatus(CASE_KEY);

        assertEquals("expired", status.status());
        assertEquals(0, status.completedSigners());
        assertEquals(1, status.totalSigners());
    }

    @Test
    void completedSigningTakesPrecedenceOverExpiredAvailability() {
        when(nextsignService.getCaseStatus(CASE_KEY)).thenReturn(expiredResponse("signed", "expired"));
        when(signingCaseRepository.findByCaseKey(CASE_KEY)).thenReturn(Optional.empty());

        SigningCaseStatus status = signingService.getStatus(CASE_KEY);

        assertEquals("completed", status.status());
        assertEquals(1, status.completedSigners());
    }

    @Test
    void terminalFetchedStatus_marksSkippedAndPreservesRetryEvidence() {
        SigningCase entity = SigningCase.builder()
            .caseKey(CASE_KEY)
            .userUuid("11111111-1111-1111-1111-111111111111")
            .documentName("Expired contract")
            .status("pending")
            .processingStatus("FAILED")
            .retryCount(4)
            .build();
        SigningCaseStatus status = new SigningCaseStatus(
            CASE_KEY,
            "expired",
            "Expired contract",
            null,
            List.of(),
            1,
            0,
            null,
            null,
            null,
            null
        );

        boolean skipped = signingService.updateCaseWithFetchedStatus(entity, status);

        assertTrue(skipped);
        assertEquals("expired", entity.getStatus());
        assertEquals("SKIPPED", entity.getProcessingStatus());
        assertEquals(4, entity.getRetryCount());
        assertEquals(
            "Status sync skipped: terminal NextSign status 'expired'",
            entity.getStatusFetchError()
        );
        verify(signingCaseRepository).persist(entity);
    }

    @Test
    void alreadyTerminalLocalStatus_isPersistedAsSkipped() {
        SigningCase entity = SigningCase.builder()
            .caseKey(CASE_KEY)
            .userUuid("11111111-1111-1111-1111-111111111111")
            .documentName("Expired contract")
            .status("EXPIRED")
            .processingStatus("COMPLETED")
            .retryCount(3)
            .build();

        boolean skipped = signingService.markCaseSkippedIfTerminal(entity);

        assertTrue(skipped);
        assertEquals("SKIPPED", entity.getProcessingStatus());
        assertEquals(3, entity.getRetryCount());
        assertEquals(
            "Status sync skipped: terminal NextSign status 'expired'",
            entity.getStatusFetchError()
        );
        verify(signingCaseRepository).persist(entity);
    }

    private static GetCaseStatusResponse expiredResponse(String signerStatus) {
        return expiredResponse(signerStatus, null);
    }

    private static GetCaseStatusResponse expiredResponse(String signerStatus, String caseStatus) {
        GetCaseStatusResponse.AvailabilitySettings availability =
            new GetCaseStatusResponse.AvailabilitySettings(false, 10, true);
        GetCaseStatusResponse.CaseSettings settings =
            new GetCaseStatusResponse.CaseSettings(null, null, availability, true, List.of());
        GetCaseStatusResponse.RecipientStatus recipient =
            new GetCaseStatusResponse.RecipientStatus(
                "Test Signer",
                "signer@example.invalid",
                0,
                0,
                true,
                signerStatus,
                null,
                false,
                null,
                List.of()
            );
        GetCaseStatusResponse.CaseDetails details =
            new GetCaseStatusResponse.CaseDetails(
                CASE_KEY,
                "display-key",
                "Expired contract",
                null,
                caseStatus,
                "open",
                "Default",
                "owner@example.invalid",
                settings,
                List.of(),
                "2026-03-06T12:30:48",
                "2026-03-06T12:30:48",
                List.of(recipient),
                List.of(new GetCaseStatusResponse.DocumentInfo("contract.pdf", true, "document-id")),
                List.of()
            );
        return new GetCaseStatusResponse("case_found", null, details);
    }
}
