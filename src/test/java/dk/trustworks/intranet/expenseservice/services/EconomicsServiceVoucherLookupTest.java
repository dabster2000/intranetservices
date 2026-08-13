package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import dk.trustworks.intranet.expenseservice.services.EconomicsService.VoucherLookupResult;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Classification tests for the voucher lookups (2026-08-12 incident): a failed
 * e-conomic call (5xx / network error) must classify as UNKNOWN — never as
 * NOT_FOUND. Treating transient errors as absence is what marked booked vouchers
 * as orphaned and precheck'ed them as MISSING during the e-conomic 503 outage.
 */
@ExtendWith(MockitoExtension.class)
class EconomicsServiceVoucherLookupTest {

    @Mock
    EconomicsAPI api;

    EconomicsService service;

    @BeforeEach
    void setUp() {
        service = new EconomicsService();
        service.verifyRetrySleepMs = 0L; // no eventual-consistency delays in tests
    }

    private Expense expense() {
        Expense e = new Expense();
        e.setUuid("a6187648-dd3c-48b2-8918-34b01896c0a8");
        e.setVouchernumber(4000978);
        e.setJournalnumber(8);
        e.setAccountingyear("2026_6_2027");
        return e;
    }

    // --- checkVoucherExists (draft journal) ---

    @Test
    void draft_2xx_is_found() {
        when(api.getVoucher(8, "2026_6_2027", 4000978)).thenReturn(Response.status(200).build());
        assertEquals(VoucherLookupResult.FOUND, service.checkVoucherExists(expense(), api));
    }

    @Test
    void draft_consistent_404_is_proven_not_found() {
        when(api.getVoucher(8, "2026_6_2027", 4000978)).thenReturn(
                Response.status(404).build(),
                Response.status(404).build(),
                Response.status(404).build());
        assertEquals(VoucherLookupResult.NOT_FOUND, service.checkVoucherExists(expense(), api));
        verify(api, times(3)).getVoucher(8, "2026_6_2027", 4000978);
    }

    @Test
    void draft_404_then_200_is_found_after_retry() {
        when(api.getVoucher(8, "2026_6_2027", 4000978)).thenReturn(
                Response.status(404).build(),
                Response.status(200).build());
        assertEquals(VoucherLookupResult.FOUND, service.checkVoucherExists(expense(), api));
    }

    @Test
    void draft_5xx_is_unknown_not_absence() {
        when(api.getVoucher(8, "2026_6_2027", 4000978)).thenReturn(Response.status(503).build());
        assertEquals(VoucherLookupResult.UNKNOWN, service.checkVoucherExists(expense(), api));
        verify(api, times(1)).getVoucher(anyInt(), anyString(), anyInt());
    }

    @Test
    void draft_transport_error_is_unknown_not_absence() {
        when(api.getVoucher(8, "2026_6_2027", 4000978)).thenThrow(new RuntimeException(
                "HTTP 503 from Economics: upstream connect error or disconnect/reset before headers"));
        assertEquals(VoucherLookupResult.UNKNOWN, service.checkVoucherExists(expense(), api));
    }

    @Test
    void draft_exception_mapped_404_is_proven_not_found() {
        when(api.getVoucher(8, "2026_6_2027", 4000978))
                .thenThrow(new RuntimeException("HTTP 404 from Economics"));
        assertEquals(VoucherLookupResult.NOT_FOUND, service.checkVoucherExists(expense(), api));
    }

    @Test
    void draft_missing_triple_is_not_found_without_any_call() {
        Expense e = new Expense();
        e.setUuid("no-voucher");
        e.setVouchernumber(0);
        assertEquals(VoucherLookupResult.NOT_FOUND, service.checkVoucherExists(e));
    }

    // --- checkVoucherBooked (accounting-year ledger) ---

    @Test
    void booked_entries_present_is_found() {
        when(api.getYearEntries("2026_6_2027", "voucherNumber$eq:4000978", 1000, 0))
                .thenReturn(Response.status(200).entity("{\"collection\":[{\"voucherNumber\":4000978}]}").build());
        assertEquals(VoucherLookupResult.FOUND, service.checkVoucherBooked(expense(), api));
    }

    @Test
    void booked_empty_collection_is_proven_not_found() {
        when(api.getYearEntries("2026_6_2027", "voucherNumber$eq:4000978", 1000, 0))
                .thenReturn(Response.status(200).entity("{\"collection\":[]}").build());
        assertEquals(VoucherLookupResult.NOT_FOUND, service.checkVoucherBooked(expense(), api));
    }

    @Test
    void booked_404_year_not_addressable_is_not_found() {
        when(api.getYearEntries("2026_6_2027", "voucherNumber$eq:4000978", 1000, 0))
                .thenReturn(Response.status(404).entity("no such year").build());
        assertEquals(VoucherLookupResult.NOT_FOUND, service.checkVoucherBooked(expense(), api));
    }

    @Test
    void booked_5xx_is_unknown_not_absence() {
        when(api.getYearEntries("2026_6_2027", "voucherNumber$eq:4000978", 1000, 0))
                .thenReturn(Response.status(500).entity("boom").build());
        assertEquals(VoucherLookupResult.UNKNOWN, service.checkVoucherBooked(expense(), api));
    }

    @Test
    void booked_transport_error_is_unknown_not_absence() {
        when(api.getYearEntries("2026_6_2027", "voucherNumber$eq:4000978", 1000, 0))
                .thenThrow(new RuntimeException("connection reset"));
        assertEquals(VoucherLookupResult.UNKNOWN, service.checkVoucherBooked(expense(), api));
    }
}
