package dk.trustworks.intranet.aggregates.executive.people;

import dk.trustworks.intranet.userservice.model.enums.CareerLevel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class HrCareerBandMapperTest {

    @Test
    void mapsEveryCanonicalLevel() {
        long unassigned = Arrays.stream(CareerLevel.values())
                .map(HrCareerBandMapper::toBand)
                .filter(HrCareerBandMapper.UNASSIGNED::equals)
                .count();
        assertEquals(0, unassigned);
        assertEquals(20, CareerLevel.values().length);
    }

    @Test
    void correctsFinanceOrientedEngagementAndDirectorGrouping() {
        assertEquals(HrCareerBandMapper.LEADERSHIP_ENGAGEMENT,
                HrCareerBandMapper.toBand(CareerLevel.ENGAGEMENT_MANAGER));
        assertEquals(HrCareerBandMapper.PARTNER_DIRECTOR,
                HrCareerBandMapper.toBand(CareerLevel.DIRECTOR));
        assertNotEquals(HrCareerBandMapper.EXECUTIVE,
                HrCareerBandMapper.toBand(CareerLevel.DIRECTOR));
    }

    @Test
    void missingCareerIsUnassignedAndBandOrderIsBusinessDefined() {
        assertEquals(HrCareerBandMapper.UNASSIGNED, HrCareerBandMapper.toBand(null));
        assertEquals(0, HrCareerBandMapper.sortOrder(HrCareerBandMapper.ENTRY));
        assertEquals(5, HrCareerBandMapper.sortOrder(HrCareerBandMapper.EXECUTIVE));
        assertEquals(6, HrCareerBandMapper.sortOrder(HrCareerBandMapper.UNASSIGNED));
    }
}
