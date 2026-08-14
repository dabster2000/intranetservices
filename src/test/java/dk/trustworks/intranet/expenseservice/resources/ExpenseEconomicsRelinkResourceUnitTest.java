package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.ExpenseRelinkRequestDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseRelinkRequestDTO.Row;
import dk.trustworks.intranet.expenseservice.dto.ExpenseRelinkRequestDTO.Target;
import dk.trustworks.intranet.expenseservice.dto.ExpenseRelinkResultDTO;
import dk.trustworks.intranet.expenseservice.services.ExpenseEconomicRelinkService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExpenseEconomicsRelinkResourceUnitTest {

    private static final String UUID_A = "11111111-2222-3333-4444-555555555555";
    private static final String UUID_B = "66666666-7777-8888-9999-000000000000";

    @Mock ExpenseEconomicRelinkService relinkService;
    @Mock RequestHeaderHolder header;

    ExpenseEconomicsResendResource resource;

    @BeforeEach
    void setUp() {
        resource = new ExpenseEconomicsResendResource();
        resource.relink = relinkService;
        resource.header = header;
    }

    private static Row draftRow(String uuid, int journal, int voucher) {
        return new Row(uuid, journal, voucher, "2026_6_2027", Target.DRAFT);
    }

    @Test
    void aggregatesAppliedSkippedAndFailedIndependently() {
        when(header.getUserUuid()).thenReturn("accountant");
        var ok = new ExpenseRelinkResultDTO.Applied(UUID_A, "j9/2026_6_2027/v7092925", "j24/2026_6_2027/v15020", "VERIFIED_UNBOOKED");
        when(relinkService.relinkOne(any(), eq("accountant"), eq(false)))
                .thenReturn(ok)
                .thenThrow(new BadRequestException("status is DELETED, expected VERIFIED_UNBOOKED"));

        var result = resource.relink(new ExpenseRelinkRequestDTO(
                List.of(draftRow(UUID_A, 24, 15020), draftRow(UUID_B, 24, 15021)), false));

        assertEquals(1, result.applied());
        assertEquals(ok, result.appliedRows().getFirst());
        assertEquals(1, result.skipped().size());
        assertEquals("status is DELETED, expected VERIFIED_UNBOOKED", result.skipped().getFirst().reason());
        assertTrue(result.failed().isEmpty());
        assertFalse(result.dryRun());
    }

    @Test
    void unexpectedErrorsDoNotExposeProviderDetails() {
        when(header.getUserUuid()).thenReturn("accountant");
        when(relinkService.relinkOne(any(), eq("accountant"), eq(false)))
                .thenThrow(new IllegalStateException("agreementGrantToken abc123 rejected by apis.e-conomic.com"));

        var result = resource.relink(new ExpenseRelinkRequestDTO(List.of(draftRow(UUID_A, 24, 15020)), false));

        assertEquals(1, result.failed().size());
        assertEquals("re-link failed", result.failed().getFirst().error());
    }

    @Test
    void dryRunIsPassedThroughToTheService() {
        when(header.getUserUuid()).thenReturn("accountant");
        when(relinkService.relinkOne(any(), eq("accountant"), eq(true)))
                .thenReturn(new ExpenseRelinkResultDTO.Applied(UUID_A, "from", "to", "VERIFIED_UNBOOKED"));

        var result = resource.relink(new ExpenseRelinkRequestDTO(List.of(draftRow(UUID_A, 24, 15020)), true));

        assertTrue(result.dryRun());
        verify(relinkService).relinkOne(any(), eq("accountant"), eq(true));
    }

    @Test
    void duplicateExpenseUuidRejectsTheWholeRequest() {
        var body = new ExpenseRelinkRequestDTO(
                List.of(draftRow(UUID_A, 24, 15020), draftRow(UUID_A, 24, 15021)), false);
        assertThrows(BadRequestException.class, () -> resource.relink(body));
        verifyNoInteractions(relinkService);
    }

    @Test
    void duplicateTargetVoucherRejectsTheWholeRequest() {
        var body = new ExpenseRelinkRequestDTO(
                List.of(draftRow(UUID_A, 24, 15020), draftRow(UUID_B, 24, 15020)), false);
        assertThrows(BadRequestException.class, () -> resource.relink(body));
        verifyNoInteractions(relinkService);
    }

    @Test
    void sameVoucherNumberInDifferentJournalsIsNotADuplicateTarget() {
        // Voucher numbering restarts per journal — j24 v15020 and j8 v15020 are different vouchers.
        when(header.getUserUuid()).thenReturn("accountant");
        when(relinkService.relinkOne(any(), eq("accountant"), eq(false)))
                .thenReturn(new ExpenseRelinkResultDTO.Applied(UUID_A, "from", "to", "VERIFIED_UNBOOKED"));

        var result = resource.relink(new ExpenseRelinkRequestDTO(
                List.of(draftRow(UUID_A, 24, 15020), draftRow(UUID_B, 8, 15020)), false));

        assertEquals(2, result.applied());
    }
}
