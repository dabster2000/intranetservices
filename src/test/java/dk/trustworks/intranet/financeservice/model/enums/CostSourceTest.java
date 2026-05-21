package dk.trustworks.intranet.financeservice.model.enums;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CostSourceTest {

    @Test
    void fromQueryParam_defaultsToBookedForMissingOrInvalidValues() {
        assertEquals(CostSource.BOOKED, CostSource.fromQueryParam(null));
        assertEquals(CostSource.BOOKED, CostSource.fromQueryParam(""));
        assertEquals(CostSource.BOOKED, CostSource.fromQueryParam("bad"));
    }

    @Test
    void bookedPlusDraftMapsToBothPostingStatuses() {
        assertEquals(CostSource.BOOKED_PLUS_DRAFT, CostSource.fromQueryParam("booked_plus_draft"));
        assertEquals(List.of("BOOKED", "DRAFT"), CostSource.BOOKED_PLUS_DRAFT.postingStatusNames());
    }
}
