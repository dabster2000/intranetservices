package dk.trustworks.intranet.aggregates.finance.services.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CareerBandMapperTest {

    @Test
    void toBand_mapsJuniorCorrectly() {
        assertEquals("Junior", CareerBandMapper.toBand("JUNIOR_CONSULTANT"));
    }

    @Test
    void toBand_mapsConsultantLevels() {
        assertEquals("Consultant", CareerBandMapper.toBand("CONSULTANT"));
        assertEquals("Consultant", CareerBandMapper.toBand("PROFESSIONAL_CONSULTANT"));
    }

    @Test
    void toBand_mapsSeniorLeadLevels() {
        assertEquals("Senior/Lead", CareerBandMapper.toBand("SENIOR_CONSULTANT"));
        assertEquals("Senior/Lead", CareerBandMapper.toBand("LEAD_CONSULTANT"));
        assertEquals("Senior/Lead", CareerBandMapper.toBand("MANAGING_CONSULTANT"));
        assertEquals("Senior/Lead", CareerBandMapper.toBand("PRINCIPAL_CONSULTANT"));
    }

    @Test
    void toBand_mapsManagerLevels() {
        assertEquals("Manager", CareerBandMapper.toBand("ASSOCIATE_MANAGER"));
        assertEquals("Manager", CareerBandMapper.toBand("MANAGER"));
        assertEquals("Manager", CareerBandMapper.toBand("SENIOR_MANAGER"));
    }

    @Test
    void toBand_mapsPartnerLevels() {
        assertEquals("Partner", CareerBandMapper.toBand("ASSOCIATE_PARTNER"));
        assertEquals("Partner", CareerBandMapper.toBand("PARTNER"));
        assertEquals("Partner", CareerBandMapper.toBand("THOUGHT_LEADER_PARTNER"));
        assertEquals("Partner", CareerBandMapper.toBand("PRACTICE_LEADER"));
        assertEquals("Partner", CareerBandMapper.toBand("ENGAGEMENT_MANAGER"));
        assertEquals("Partner", CareerBandMapper.toBand("SENIOR_ENGAGEMENT_MANAGER"));
        assertEquals("Partner", CareerBandMapper.toBand("ENGAGEMENT_DIRECTOR"));
    }

    @Test
    void toBand_mapsCLevelLevels() {
        assertEquals("C-Level", CareerBandMapper.toBand("MANAGING_PARTNER"));
        assertEquals("C-Level", CareerBandMapper.toBand("MANAGING_DIRECTOR"));
        assertEquals("C-Level", CareerBandMapper.toBand("DIRECTOR"));
    }

    @Test
    void toBand_returnsUnknownForNull() {
        assertEquals("Unknown", CareerBandMapper.toBand(null));
    }

    @Test
    void toBand_returnsUnknownForUnrecognized() {
        assertEquals("Unknown", CareerBandMapper.toBand("NONEXISTENT_LEVEL"));
    }

    @Test
    void knownLevels_contains20Entries() {
        assertEquals(20, CareerBandMapper.knownLevels().size());
    }

    @Test
    void bandOrder_contains6Bands() {
        assertEquals(6, CareerBandMapper.BAND_ORDER.size());
        assertEquals("Junior", CareerBandMapper.BAND_ORDER.getFirst());
        assertEquals("C-Level", CareerBandMapper.BAND_ORDER.getLast());
    }

    @Test
    void toSqlCase_generatesValidSql() {
        String sql = CareerBandMapper.toSqlCase("ucl.career_level");
        assertTrue(sql.startsWith("CASE "));
        assertTrue(sql.endsWith(" END"));
        assertTrue(sql.contains("'Junior'"));
        assertTrue(sql.contains("'C-Level'"));
        assertTrue(sql.contains("'Unknown'"));
    }
}
