package dk.trustworks.intranet.vacationservice.engine;

import dk.trustworks.intranet.vacationservice.engine.DanlonCsvParser.ParsedCsv;
import dk.trustworks.intranet.vacationservice.engine.DanlonCsvParser.ParsedRow;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests for the Danløn feriepengeforpligtelse CSV parser: dynamic
 * year headers, Danish number format, the trailing all-empty row and both
 * encodings Danløn has shipped.
 */
class DanlonCsvParserTest {

    private static final String HEADER =
            "Navn;Bogføringsgruppe;Optjent dage 2024;Afholdt dage 2024;Optjent 2024;Hensættes 2024;"
                    + "Optjent dage 2025;Afholdt dage 2025;Optjent 2025;Hensættes 2025";

    @Test
    void parsesTheRealExportShape() {
        String csv = HEADER + "\n"
                + "Elvi Rohde Nissen;Standard;33,00;33,00;118.530,44;0,00;27,50;7,00;101.675,67;75.794,59\n"
                + "Camilla  Alm;Standard;17,50;17,50;122.857,92;0,00;10,00;10,00;74.468,49;0,00\n"
                + ";;;;;;;;;\n";

        ParsedCsv parsed = DanlonCsvParser.parse(csv);

        assertEquals(List.of(2024, 2025), parsed.ferieaar());
        assertEquals(2, parsed.rows().size(), "the trailing empty row must be dropped");

        ParsedRow elvi = parsed.rows().get(0);
        assertEquals("Elvi Rohde Nissen", elvi.name());
        assertEquals(33.0, elvi.years().get(2024).earnedDays(), 0.001);
        assertEquals(7.0, elvi.years().get(2025).usedDays(), 0.001);
        assertEquals("101.675,67", elvi.years().get(2025).earnedKrRaw(), "DKK stays verbatim");

        // The double space survives into the raw name — normalization happens at match time.
        assertEquals("Camilla  Alm", parsed.rows().get(1).name());
        assertEquals(17.5, parsed.rows().get(1).years().get(2024).earnedDays(), 0.001);
    }

    @Test
    void yearColumnsAreDetectedDynamically() {
        String csv = "Navn;Bogføringsgruppe;Optjent dage 2026;Afholdt dage 2026;Optjent 2026;Hensættes 2026\n"
                + "Test Person;Standard;12,50;3,00;10.000,00;7.500,00\n";
        ParsedCsv parsed = DanlonCsvParser.parse(csv);
        assertEquals(List.of(2026), parsed.ferieaar());
        assertEquals(12.5, parsed.rows().get(0).years().get(2026).earnedDays(), 0.001);
    }

    @Test
    void danishNumbersParse() {
        assertEquals(118530.44, DanlonCsvParser.parseDanishNumber("118.530,44", 1, "x"), 0.001);
        assertEquals(27.5, DanlonCsvParser.parseDanishNumber("27,50", 1, "x"), 0.001);
        assertEquals(-1144.34, DanlonCsvParser.parseDanishNumber("-1.144,34", 1, "x"), 0.001);
        assertEquals(0.0, DanlonCsvParser.parseDanishNumber("", 1, "x"), 0.001);
    }

    @Test
    void rejectsForeignFilesWithAClearMessage() {
        assertThrows(IllegalArgumentException.class, () -> DanlonCsvParser.parse(""));
        assertThrows(IllegalArgumentException.class,
                () -> DanlonCsvParser.parse("id,name,amount\n1,Test,100\n"));
        IllegalArgumentException noYears = assertThrows(IllegalArgumentException.class,
                () -> DanlonCsvParser.parse("Navn;Bogføringsgruppe\nTest;Standard\n"));
        assertTrue(noYears.getMessage().contains("Optjent dage"));
    }

    @Test
    void badNumberNamesLineAndColumn() {
        String csv = HEADER + "\nTest Person;Standard;abc;0,00;0,00;0,00;0,00;0,00;0,00;0,00\n";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DanlonCsvParser.parse(csv));
        assertTrue(e.getMessage().contains("Line 2"));
        assertTrue(e.getMessage().contains("Optjent dage 2024"));
    }

    @Test
    void decodesUtf8WithBomAndLatin1Fallback() {
        String csv = HEADER + "\nSøren Ærø;Standard;1,00;0,00;0,00;0,00;0,00;0,00;0,00;0,00\n";

        byte[] utf8 = csv.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[utf8.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(utf8, 0, withBom, 3, utf8.length);
        assertEquals("Søren Ærø", DanlonCsvParser.parse(withBom).rows().get(0).name());

        byte[] latin1 = csv.getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("Søren Ærø", DanlonCsvParser.parse(latin1).rows().get(0).name());

        // The string path too: a browser's file.text() keeps the BOM as U+FEFF.
        assertEquals("Søren Ærø", DanlonCsvParser.parse("﻿" + csv).rows().get(0).name());
    }
}
