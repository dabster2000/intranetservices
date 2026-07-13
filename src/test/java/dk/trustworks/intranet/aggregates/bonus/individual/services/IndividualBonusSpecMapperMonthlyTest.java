package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.model.Basis;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Cadence;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Schedule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Tier;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Yearly;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndividualBonusSpecMapperMonthlyTest {

    @Test
    void legacyRoundTripDoesNotInjectMonthlyProperties() {
        Spec legacy = new Spec(Basis.BILLABLE_HOURS, "FISCAL_YEAR_SUM",
                List.of(new Tier(BigDecimal.ZERO, null, BigDecimal.ZERO)), null, null,
                false, null, new Schedule(Cadence.YEARLY, new Yearly(1), null, null), null);
        IndividualBonusSpecMapper mapper = new IndividualBonusSpecMapper();

        String json = mapper.serialize(legacy);
        Spec roundTrip = mapper.parse(json);

        assertFalse(json.contains("stepTable"), json);
        assertFalse(json.contains("expectedBaseSalary"), json);
        assertFalse(json.contains("monthly"), json);
        assertNull(roundTrip.stepTable());
        assertNull(roundTrip.expectedBaseSalary());
        assertNull(roundTrip.schedule().monthly());
    }

    @Test
    void monthlyRoundTripKeepsExactVocabulary() {
        IndividualBonusSpecMapper mapper = new IndividualBonusSpecMapper();
        String json = mapper.serialize(IndividualBonusMonthlySpecValidatorTest.monthlySpec(
                IndividualBonusMonthlySpecValidatorTest.authoritativeBands()));
        assertTrue(json.contains("\"aggregation\":\"CALENDAR_MONTH\""), json);
        assertTrue(json.contains("\"vehicle\":\"MONTHLY_LUMP_SUM\""), json);
        assertTrue(json.contains("\"expectedBaseSalary\":58000"), json);
    }
}
