package dk.trustworks.intranet.agreementservice.jobs;

import dk.trustworks.intranet.agreementservice.jobs.AgreementExpiryBatchlet.AlertThreshold;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static dk.trustworks.intranet.agreementservice.jobs.AgreementExpiryBatchlet.decideAlert;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DB-free tests for the Phase 3 expiry-alert decision (template-clauses
 * spec §8): each threshold fires once and only once, the 14-day alert
 * covers the 60-day one for rows first seen inside its window, and past
 * dates belong to the expiry flip, never to an alert.
 */
class AgreementExpiryBatchletCoreTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);

    @Test
    void beyondSixtyDays_noAlert() {
        assertEquals(AlertThreshold.NONE, decideAlert(TODAY.plusDays(61), TODAY, false, false));
    }

    @Test
    void insideSixtyDays_firesOnceThenStamps() {
        assertEquals(AlertThreshold.SIXTY_DAYS, decideAlert(TODAY.plusDays(60), TODAY, false, false));
        assertEquals(AlertThreshold.SIXTY_DAYS, decideAlert(TODAY.plusDays(15), TODAY, false, false));
        assertEquals(AlertThreshold.NONE, decideAlert(TODAY.plusDays(45), TODAY, true, false));
    }

    @Test
    void insideFourteenDays_fourteenWinsAndCoversSixty() {
        // A row first seen inside 14 days must not get a late 60-day alert.
        assertEquals(AlertThreshold.FOURTEEN_DAYS, decideAlert(TODAY.plusDays(14), TODAY, false, false));
        assertEquals(AlertThreshold.FOURTEEN_DAYS, decideAlert(TODAY.plusDays(14), TODAY, true, false));
        assertEquals(AlertThreshold.FOURTEEN_DAYS, decideAlert(TODAY, TODAY, true, false));
        assertEquals(AlertThreshold.NONE, decideAlert(TODAY.plusDays(3), TODAY, true, true));
    }

    @Test
    void pastValidTo_neverAlerts() {
        // The ACTIVE -> EXPIRED flip owns past dates.
        assertEquals(AlertThreshold.NONE, decideAlert(TODAY.minusDays(1), TODAY, false, false));
    }
}
