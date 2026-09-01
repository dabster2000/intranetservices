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
    void terminalStatusSets_agreeAcrossDomainAndService() {
        // The repository poll-set query stops polling on TERMINAL_STATUSES;
        // the service's skip logic uses TERMINAL_NON_UPLOADABLE_STATUSES.
        // They must differ by exactly "completed" or cases either poll
        // forever or freeze mid-flight.
        var expected = new java.util.HashSet<>(SigningCase.TERMINAL_NON_UPLOADABLE_STATUSES);
        expected.add("completed");
        assertEquals(expected, SigningCase.TERMINAL_STATUSES);
    }

    @Test
    void getCaseDetail_refreshesStaleLocalRow() {
        // Row frozen at its first-fetch snapshot (the pre-fix recruitment
        // situation): signing completed in NextSign long ago, cache says pending.
        SigningCase staleRow = SigningCase.builder()
            .caseKey(CASE_KEY)
            .userUuid("11111111-1111-1111-1111-111111111111")
            .documentName("Contract")
            .status("pending")
            .processingStatus("COMPLETED")
            .totalSigners(1)
            .completedSigners(0)
            .build();
        when(nextsignService.getCaseStatus(CASE_KEY)).thenReturn(expiredResponse("signed"));
        when(signingCaseRepository.findByCaseKey(CASE_KEY)).thenReturn(Optional.of(staleRow));

        var detail = signingService.getCaseDetail(CASE_KEY);

        assertEquals(CASE_KEY, detail.id());
        assertEquals("completed", staleRow.getStatus());
        assertEquals(1, staleRow.getCompletedSigners());
        verify(signingCaseRepository).persist(staleRow);
    }

    @Test
    void clientCaseNotFound_translatesToTypedSigningException() {
        when(nextsignService.getCaseStatus(CASE_KEY)).thenThrow(
            new NextsignSigningService.NextsignCaseNotFoundException(
                "Case not found in NextSign: " + CASE_KEY));

        org.junit.jupiter.api.Assertions.assertThrows(
            SigningService.CaseNotFoundInNextsignException.class,
            () -> signingService.getStatus(CASE_KEY));
    }

    @Test
    void getCaseDetail_caseNotFound_surfacesAsSigningException() {
        // Resource layers catch SigningException and translate it to 404 —
        // the typed not-found must be a SigningException subtype, never a raw
        // runtime exception that would surface as a 500.
        when(nextsignService.getCaseStatus(CASE_KEY)).thenThrow(
            new NextsignSigningService.NextsignCaseNotFoundException(
                "Case not found in NextSign: " + CASE_KEY));

        org.junit.jupiter.api.Assertions.assertThrows(
            SigningService.CaseNotFoundInNextsignException.class,
            () -> signingService.getCaseDetail(CASE_KEY));
    }

    @Test
    void getCaseDetail_cacheRefreshFailure_doesNotBreakDetailResponse() {
        when(nextsignService.getCaseStatus(CASE_KEY)).thenReturn(expiredResponse("signed"));
        when(signingCaseRepository.findByCaseKey(CASE_KEY))
            .thenThrow(new RuntimeException("db unavailable"));

        var detail = signingService.getCaseDetail(CASE_KEY);

        assertEquals(CASE_KEY, detail.id());
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
