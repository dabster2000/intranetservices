package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Settle revalidation (Phase 1 review amendment): a voucher whose live net no longer
 * matches the Σ of its assignment shares (e.g. e-conomic booked a reversing correction
 * AFTER the human assignment) must block settle until re-confirmed in the workbench.
 * Amounts signed as posted (revenue negative); tolerance |diff| <= 1 kr.
 */
class SelfBilledSettleRevalidationTest {

    @Test
    void changed_voucher_is_stale() {
        // reversing correction moved the net; assignment shares still target the OLD net
        List<String> stale = SelfBilledSettlementService.staleVoucherNumbers(
                Map.of("5600:2069", new BigDecimal("-100000.00")),
                Map.of("5600:2069", new BigDecimal("-153525.00")));
        assertEquals(List.of("5600:2069"), stale);
    }

    @Test
    void within_one_krone_passes() {
        List<String> stale = SelfBilledSettlementService.staleVoucherNumbers(
                Map.of("5600:2069", new BigDecimal("-1000.60")),
                Map.of("5600:2069", new BigDecimal("-1000.00")));
        assertTrue(stale.isEmpty());
    }

    @Test
    void untouched_voucher_passes() {
        List<String> stale = SelfBilledSettlementService.staleVoucherNumbers(
                Map.of("5600:2069", new BigDecimal("-153525.00")),
                Map.of("5600:2069", new BigDecimal("-153525.00")));
        assertTrue(stale.isEmpty());
    }
}
