package dk.trustworks.intranet.aggregates.invoice.selfbilled.parse;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SelfBilledTextParserTest {

    @Test void standard() {
        ParsedLine p = SelfBilledTextParser.parse("Faktura: 58484650180 - 08-2025 MC").orElseThrow();
        assertEquals("58484650180", p.faktura());
        assertEquals(2025, p.workYear());
        assertEquals(8, p.workMonth());
        assertEquals("MC", p.code());
    }

    @Test void typo_Faktrua() {
        assertEquals("DV", SelfBilledTextParser.parse("Faktrua: 58484650161 - 05-2025 DV").orElseThrow().code());
    }

    @Test void lowercase_and_leadingSpace() {
        assertEquals("TM", SelfBilledTextParser.parse("faktura: 58484650163 - 05-2025 TM").orElseThrow().code());
        assertEquals("CNE", SelfBilledTextParser.parse(" Faktura: 58484650227 - 12-2025 CNE").orElseThrow().code());
    }

    @Test void noColon_and_variableLengthFaktura() {
        assertEquals("MHB", SelfBilledTextParser.parse("Faktura 5105733453 - 09-2025 MHB").orElseThrow().code());
        assertEquals("510761717", SelfBilledTextParser.parse("Faktura: 510761717 - 02-2026 JK").orElseThrow().faktura());
        assertEquals("51057566880", SelfBilledTextParser.parse("Faktura: 51057566880 - 01-2026 MHB").orElseThrow().faktura());
    }

    @Test void corrections_and_blank_are_empty() {
        assertTrue(SelfBilledTextParser.parse("Forkert kt ").isEmpty());
        assertTrue(SelfBilledTextParser.parse("Forkert beløb ").isEmpty());
        assertTrue(SelfBilledTextParser.parse("Bogført 2 x ").isEmpty());
        assertTrue(SelfBilledTextParser.parse("").isEmpty());
        assertEquals(Optional.empty(), SelfBilledTextParser.parse(null));
    }

    @Test void outOfWindow_period_still_parses() {
        ParsedLine p = SelfBilledTextParser.parse("Faktura: 58484650002 - 05-2024 MDS").orElseThrow();
        assertEquals(2024, p.workYear());
        assertEquals(5, p.workMonth());
    }

    @Test void hyphenated_faktura_number() {
        ParsedLine p = SelfBilledTextParser.parse("Faktura: 17-869 - 07-2025 TD").orElseThrow();
        assertEquals("17-869", p.faktura());
        assertEquals(2025, p.workYear());
        assertEquals(7, p.workMonth());
        assertEquals("TD", p.code());
    }

    @Test void code_is_normalized_to_uppercase() {
        assertEquals("MHB", SelfBilledTextParser.parse("Faktura: 123 - 09-2025 mhb").orElseThrow().code());
    }

    @Test void invalid_month_is_empty() {
        assertTrue(SelfBilledTextParser.parse("Faktura: 123 - 00-2025 TD").isEmpty());
        assertTrue(SelfBilledTextParser.parse("Faktura: 123 - 13-2025 TD").isEmpty());
    }
}
