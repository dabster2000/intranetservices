package dk.trustworks.intranet.expenseservice.services;

import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static dk.trustworks.intranet.expenseservice.services.ExpenseEconomicRelinkService.Decision;
import static dk.trustworks.intranet.expenseservice.services.ExpenseEconomicRelinkService.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * DB-free truth table for the re-link decision core. The e-conomic lookup is handed in as a
 * supplier so the tests can also prove it is NOT invoked when a cheaper local check already
 * rejects the row (each avoided lookup is a live API call in production).
 */
class ExpenseEconomicRelinkDecisionTest {

    private static final Supplier<EconomicsService.VoucherLookupResult> MUST_NOT_LOOKUP =
            () -> fail("e-conomic lookup must not run when a local check already rejected the row");

    @Test
    void appliesWhenEligibleAndTargetProvenPresent() {
        assertEquals(Decision.APPLY,
                decide("VERIFIED_UNBOOKED", false, false, () -> EconomicsService.VoucherLookupResult.FOUND));
    }

    @Test
    void rejectsAnyStatusOtherThanVerifiedUnbooked() {
        for (String status : new String[]{"VERIFIED_BOOKED", "DELETED", "UPLOADED", "PROCESSING", null}) {
            assertEquals(Decision.INELIGIBLE_STATUS, decide(status, false, false, MUST_NOT_LOOKUP),
                    "status=" + status);
        }
    }

    @Test
    void alreadyLinkedRowIsIdempotentlySkippedWithoutLookup() {
        assertEquals(Decision.ALREADY_LINKED, decide("VERIFIED_UNBOOKED", true, false, MUST_NOT_LOOKUP));
    }

    @Test
    void claimedTargetIsRejectedWithoutLookup() {
        assertEquals(Decision.TARGET_CLAIMED, decide("VERIFIED_UNBOOKED", false, true, MUST_NOT_LOOKUP));
    }

    @Test
    void provenAbsentTargetIsRejected() {
        assertEquals(Decision.TARGET_NOT_FOUND,
                decide("VERIFIED_UNBOOKED", false, false, () -> EconomicsService.VoucherLookupResult.NOT_FOUND));
    }

    @Test
    void inconclusiveLookupFailsClosed() {
        // UNKNOWN is neither presence nor absence — same fail-closed rule that keeps the
        // re-send console from duplicating vouchers during an e-conomic outage.
        assertEquals(Decision.ECONOMIC_UNKNOWN,
                decide("VERIFIED_UNBOOKED", false, false, () -> EconomicsService.VoucherLookupResult.UNKNOWN));
    }
}
