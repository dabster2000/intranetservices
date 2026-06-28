package dk.trustworks.intranet.financeservice.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RevenueBasisTest {

    @Test
    void fromQueryParam_defaultsToInvoicedForMissingOrInvalidValues() {
        assertEquals(RevenueBasis.INVOICED, RevenueBasis.fromQueryParam(null));
        assertEquals(RevenueBasis.INVOICED, RevenueBasis.fromQueryParam(""));
        assertEquals(RevenueBasis.INVOICED, RevenueBasis.fromQueryParam("   "));
        assertEquals(RevenueBasis.INVOICED, RevenueBasis.fromQueryParam("bad"));
    }

    @Test
    void fromQueryParam_parsesWorkPeriodCaseInsensitively() {
        assertEquals(RevenueBasis.WORK_PERIOD, RevenueBasis.fromQueryParam("WORK_PERIOD"));
        assertEquals(RevenueBasis.WORK_PERIOD, RevenueBasis.fromQueryParam("work_period"));
        assertEquals(RevenueBasis.WORK_PERIOD, RevenueBasis.fromQueryParam(" Work_Period "));
        assertEquals(RevenueBasis.INVOICED, RevenueBasis.fromQueryParam("invoiced"));
    }
}
