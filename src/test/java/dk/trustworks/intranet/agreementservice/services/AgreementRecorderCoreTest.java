package dk.trustworks.intranet.agreementservice.services;

import dk.trustworks.intranet.agreementservice.services.AgreementRecorder.RegistryFields;
import dk.trustworks.intranet.documentservice.model.TemplateClausePlaceholderEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * DB-free tests for the Phase 3 registry-field mapping (template-clauses
 * spec §4.3/§8): parameter values entered by humans in Danish or ISO
 * shapes must land correctly in the first-class registry columns — and
 * anything unparseable must drop to {@code parameters_json} silently
 * rather than fail the completion recording.
 */
class AgreementRecorderCoreTest {

    // ---- Amount parsing --------------------------------------------------------

    @Test
    void parseAmount_danishGroupingDots() {
        assertEquals(new BigDecimal("60000"), AgreementRecorder.parseAmount("60.000"));
        assertEquals(new BigDecimal("1250000"), AgreementRecorder.parseAmount("1.250.000"));
    }

    @Test
    void parseAmount_danishDecimalComma() {
        assertEquals(new BigDecimal("60000.50"), AgreementRecorder.parseAmount("60.000,50"));
        assertEquals(new BigDecimal("99.95"), AgreementRecorder.parseAmount("99,95"));
    }

    @Test
    void parseAmount_isoDecimalDot() {
        assertEquals(new BigDecimal("60000.50"), AgreementRecorder.parseAmount("60000.50"));
        assertEquals(new BigDecimal("60000.5"), AgreementRecorder.parseAmount("60000.5"));
    }

    @Test
    void parseAmount_currencySuffixAndSpacesStripped() {
        assertEquals(new BigDecimal("60000"), AgreementRecorder.parseAmount("60 000 kr."));
        assertEquals(new BigDecimal("60000"), AgreementRecorder.parseAmount("DKK 60.000"));
    }

    @Test
    void parseAmount_plainAndGarbage() {
        assertEquals(new BigDecimal("60000"), AgreementRecorder.parseAmount("60000"));
        assertNull(AgreementRecorder.parseAmount("efter aftale"));
        assertNull(AgreementRecorder.parseAmount("-"));
    }

    // ---- Date parsing ----------------------------------------------------------

    @Test
    void parseDate_isoAndDanishShapes() {
        assertEquals(LocalDate.of(2026, 12, 31), AgreementRecorder.parseDate("2026-12-31"));
        assertEquals(LocalDate.of(2026, 12, 31), AgreementRecorder.parseDate("31-12-2026"));
        assertEquals(LocalDate.of(2026, 12, 31), AgreementRecorder.parseDate("31.12.2026"));
        assertEquals(LocalDate.of(2026, 12, 31), AgreementRecorder.parseDate("31/12/2026"));
    }

    @Test
    void parseDate_unparseableIsNull() {
        assertNull(AgreementRecorder.parseDate("ved udgangen af FY26"));
    }

    // ---- Currency --------------------------------------------------------------

    @Test
    void normalizeCurrency_danishAliases() {
        assertEquals("DKK", AgreementRecorder.normalizeCurrency("kr"));
        assertEquals("DKK", AgreementRecorder.normalizeCurrency("kr."));
        assertEquals("DKK", AgreementRecorder.normalizeCurrency("dkk"));
        assertEquals("EUR", AgreementRecorder.normalizeCurrency("eur"));
        assertNull(AgreementRecorder.normalizeCurrency("kroner"));
    }

    // ---- registry_field mapping ------------------------------------------------

    private static TemplateClausePlaceholderEntity placeholder(String key, String registryField) {
        TemplateClausePlaceholderEntity entity = new TemplateClausePlaceholderEntity();
        entity.setPlaceholderKey(key);
        entity.setRegistryField(registryField);
        return entity;
    }

    @Test
    void mapRegistryFields_mapsDeclaredFieldsAndIgnoresTheRest() {
        RegistryFields fields = AgreementRecorder.mapRegistryFields(
                Map.of("GB_AMOUNT", "60.000",
                        "GB_CURRENCY", "kr.",
                        "GB_PERIOD_START", "2026-07-01",
                        "GB_PERIOD_END", "31.12.2026",
                        "GB_NOTE", "aftalt med CEO"),
                List.of(placeholder("GB_AMOUNT", "AMOUNT"),
                        placeholder("GB_CURRENCY", "CURRENCY"),
                        placeholder("GB_PERIOD_START", "VALID_FROM"),
                        placeholder("GB_PERIOD_END", "VALID_TO"),
                        placeholder("GB_NOTE", null)));
        assertEquals(new BigDecimal("60000"), fields.amount());
        assertEquals("DKK", fields.currency());
        assertEquals(LocalDate.of(2026, 7, 1), fields.validFrom());
        assertEquals(LocalDate.of(2026, 12, 31), fields.validTo());
        assertNull(fields.effectiveDate());
    }

    @Test
    void mapRegistryFields_missingValueAndEmptyInputsAreSafe() {
        RegistryFields fields = AgreementRecorder.mapRegistryFields(
                Map.of("OTHER_KEY", "x"),
                List.of(placeholder("GB_AMOUNT", "AMOUNT")));
        assertNull(fields.amount());
        assertEquals(RegistryFields.EMPTY, AgreementRecorder.mapRegistryFields(Map.of(), List.of()));
    }

    @Test
    void mapRegistryFields_unknownRegistryFieldIsIgnored() {
        RegistryFields fields = AgreementRecorder.mapRegistryFields(
                Map.of("GB_X", "42"),
                List.of(placeholder("GB_X", "SOMETHING_NEW")));
        assertEquals(RegistryFields.EMPTY, fields);
    }
}
