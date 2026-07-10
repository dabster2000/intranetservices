package dk.trustworks.intranet.contracts.model.enums;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static dk.trustworks.intranet.contracts.model.enums.LifecycleStatus.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Boundary-day tests for the backend-computed status model (framework-agreements
 * redesign spec §4). Derivation order: !active → ARCHIVED/DISABLED; end date
 * (EXCLUSIVE) reached → EXPIRED; validFrom in the future → SCHEDULED; else ACTIVE.
 *
 * Plain JUnit — no Quarkus boot required (the derivation is pure).
 */
class LifecycleStatusTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 10);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);

    // --- Agreement status ---

    @Test
    void agreement_active_noDates_isActive() {
        assertEquals(ACTIVE, forAgreement(true, null, null, TODAY));
    }

    @Test
    void agreement_validFromToday_isActive_startInclusive() {
        assertEquals(ACTIVE, forAgreement(true, TODAY, null, TODAY));
    }

    @Test
    void agreement_validFromTomorrow_isScheduled() {
        assertEquals(SCHEDULED, forAgreement(true, TOMORROW, null, TODAY));
    }

    @Test
    void agreement_validUntilToday_isExpired_endExclusive() {
        // validUntil is EXCLUSIVE: the day the end date is reached, the agreement is expired.
        assertEquals(EXPIRED, forAgreement(true, YESTERDAY.minusDays(30), TODAY, TODAY));
    }

    @Test
    void agreement_validUntilTomorrow_isActive_lastValidDay() {
        assertEquals(ACTIVE, forAgreement(true, null, TOMORROW, TODAY));
    }

    @Test
    void agreement_validUntilYesterday_isExpired() {
        assertEquals(EXPIRED, forAgreement(true, null, YESTERDAY, TODAY));
    }

    @Test
    void agreement_inactive_isArchived_evenWhenDateExpired() {
        // !active beats date-derived EXPIRED (derivation order rule 1).
        assertEquals(ARCHIVED, forAgreement(false, null, YESTERDAY, TODAY));
    }

    @Test
    void agreement_inactive_isArchived_evenWhenDatesSayActive() {
        assertEquals(ARCHIVED, forAgreement(false, YESTERDAY, TOMORROW, TODAY));
    }

    @Test
    void agreement_inactive_isArchived_evenWhenScheduled() {
        assertEquals(ARCHIVED, forAgreement(false, TOMORROW, null, TODAY));
    }

    @Test
    void agreement_expiredCheckedBeforeScheduled() {
        // Documents derivation order rule 2 before rule 3 (such rows are blocked by
        // the DB CHECK valid_until > valid_from, but the order is part of the contract).
        assertEquals(EXPIRED, forAgreement(true, TOMORROW, YESTERDAY, TODAY));
    }

    @Test
    void agreement_windowEntirelyInPast_isExpired() {
        assertEquals(EXPIRED, forAgreement(true, TODAY.minusYears(2), TODAY.minusYears(1), TODAY));
    }

    @Test
    void agreement_windowSpanningToday_isActive() {
        assertEquals(ACTIVE, forAgreement(true, YESTERDAY, TOMORROW, TODAY));
    }

    // --- Pricing rule status ---

    @Test
    void pricingRule_active_noDates_isActive() {
        assertEquals(ACTIVE, forPricingRule(true, null, null, TODAY));
    }

    @Test
    void pricingRule_validFromToday_isActive_startInclusive() {
        assertEquals(ACTIVE, forPricingRule(true, TODAY, TOMORROW, TODAY));
    }

    @Test
    void pricingRule_validFromTomorrow_isScheduled() {
        assertEquals(SCHEDULED, forPricingRule(true, TOMORROW, null, TODAY));
    }

    @Test
    void pricingRule_validToToday_isExpired_endExclusive() {
        // validTo EXCLUSIVE: the rule stops applying ON its validTo date.
        assertEquals(EXPIRED, forPricingRule(true, null, TODAY, TODAY));
    }

    @Test
    void pricingRule_validToTomorrow_isActive_lastValidDay() {
        assertEquals(ACTIVE, forPricingRule(true, null, TOMORROW, TODAY));
    }

    @Test
    void pricingRule_inactive_isDisabled_evenWhenDateExpired() {
        // !active beats date-derived EXPIRED.
        assertEquals(DISABLED, forPricingRule(false, null, YESTERDAY, TODAY));
    }

    @Test
    void pricingRule_inactive_isDisabled_evenWhenDatesSayActive() {
        assertEquals(DISABLED, forPricingRule(false, YESTERDAY, TOMORROW, TODAY));
    }

    @Test
    void pricingRule_inactive_isDisabled_notArchived() {
        // Rules use DISABLED for the inactive state, never ARCHIVED.
        assertEquals(DISABLED, forPricingRule(false, null, null, TODAY));
    }

    // --- Validation rule status (no dates) ---

    @Test
    void validationRule_active_isActive() {
        assertEquals(ACTIVE, forValidationRule(true));
    }

    @Test
    void validationRule_inactive_isDisabled() {
        assertEquals(DISABLED, forValidationRule(false));
    }
}
