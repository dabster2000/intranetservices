package dk.trustworks.intranet.documentservice.maintenance;

import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deterministic display-name builder (V476) — the AI-off path.
 *
 * <p>Pattern: {@code {YYYY-MM-DD}_{CATEGORY}_{subject}.{ext}}, with the
 * date segment omitted entirely when no real date is derivable. The
 * builder must never invent a date: for migrated rows {@code created_at}
 * carries the legacy store's upload timestamp, so a 2019 contract
 * re-filed in 2021 would otherwise be stamped 2021.</p>
 */
class EmployeeDocumentDisplayNameBuilderTest {

    private static String build(EmployeeDocumentCategory category, String filename) {
        return EmployeeDocumentCategorizerRules.buildDisplayName(category, filename, "", null);
    }

    // ── Date formats ───────────────────────────────────────────────────────

    @Test
    void yearOnlyRendersAsTheYear() {
        assertEquals("2021_SALARY_loenreg.pdf",
                build(EmployeeDocumentCategory.SALARY, "loenreg_2021_final(2).pdf"));
    }

    @Test
    void isoDateInTheFilename() {
        assertEquals("2019-03-04_IDENTITY_sundhedskort.pdf",
                build(EmployeeDocumentCategory.IDENTITY, "sundhedskort 2019-03-04.pdf"));
    }

    @Test
    void compactYyyymmddIsReadAsAFullDateNotAsAYear() {
        assertEquals("2019-03-04_IDENTITY_scan.pdf",
                build(EmployeeDocumentCategory.IDENTITY, "Scan_20190304.pdf"));
    }

    @Test
    void danishDayFirstDate() {
        assertEquals("2019-03-04_TERMINATION_opsigelse.pdf",
                build(EmployeeDocumentCategory.TERMINATION, "opsigelse 04-03-2019.pdf"));
        assertEquals("2019-03-04_TERMINATION_opsigelse.pdf",
                build(EmployeeDocumentCategory.TERMINATION, "opsigelse 04.03.2019.pdf"));
    }

    @Test
    void impossibleDatesAreRejectedRatherThanCoerced() {
        // 2019-02-31 is not a date; the year is still a real signal.
        assertEquals("2019_OTHER_notat.pdf",
                build(EmployeeDocumentCategory.OTHER, "notat 2019-02-31.pdf"));
    }

    @Test
    void noDateMeansTheSegmentIsOmittedEntirely() {
        assertEquals("CONTRACT_kontrakt-anders.docx",
                EmployeeDocumentCategorizerRules.buildDisplayName(EmployeeDocumentCategory.CONTRACT,
                        "Kontrakt Anders FINAL FINAL.docx", "Arkiv", null));
    }

    @Test
    void neverInventsADateFromCreatedAt() {
        String name = build(EmployeeDocumentCategory.IDENTITY, "IMG_4471.jpg");
        assertEquals("IDENTITY_img.jpg", name);
        assertFalse(name.matches(".*\\d{4}.*"), "no year may appear from nowhere: " + name);
    }

    @Test
    void aCallerSuppliedDateWins() {
        assertEquals("2019-03-04_IDENTITY_img.jpg",
                EmployeeDocumentCategorizerRules.buildDisplayName(EmployeeDocumentCategory.IDENTITY,
                        "IMG_4471.jpg", "", LocalDate.of(2019, 3, 4)));
    }

    // ── Subject derivation ─────────────────────────────────────────────────

    @Test
    void noiseTokensAreStripped() {
        assertEquals("2021_SALARY_loenreg.pdf",
                build(EmployeeDocumentCategory.SALARY, "loenreg_2021_final(2).pdf"));
        assertEquals("CONTRACT_kontrakt.pdf",
                build(EmployeeDocumentCategory.CONTRACT, "kontrakt ny kopi v2.pdf"));
    }

    @Test
    void whitespaceUnderscoresAndDotsCollapseToHyphens() {
        assertEquals("DECLARATION_tro-og-love-erklaering.pdf",
                build(EmployeeDocumentCategory.DECLARATION, "Tro_og love  erklaering.pdf"));
    }

    @Test
    void danishCharactersSurvive() {
        assertEquals("2019_SALARY_lønregulering.pdf",
                build(EmployeeDocumentCategory.SALARY, "Lønregulering 2019.pdf"));
        assertEquals("SICKNESS_mulighedserklæring.pdf",
                build(EmployeeDocumentCategory.SICKNESS, "mulighedserklæring.pdf"));
    }

    @Test
    void aFilenameWithNoWordsFallsBackToTheFolderNameForItsSubject() {
        assertEquals("2019_VACATION_ferieaftaler.pdf",
                EmployeeDocumentCategorizerRules.buildDisplayName(EmployeeDocumentCategory.VACATION,
                        "2019.pdf", "HR/Ferieaftaler", null));
    }

    @Test
    void trailingDigitRunsAreStrippedFromASubjectWord() {
        assertEquals("OTHER_scan.pdf", build(EmployeeDocumentCategory.OTHER, "scan0001.pdf"));
    }

    @Test
    void aDocumentWithNothingToSayIsStillNamedByItsCategory() {
        assertEquals("OTHER.pdf",
                EmployeeDocumentCategorizerRules.buildDisplayName(EmployeeDocumentCategory.OTHER,
                        "123.pdf", "", null));
    }

    // ── The same safety rules as the AI path ───────────────────────────────

    @Test
    void theExtensionAlwaysComesFromTheOriginalFile() {
        assertTrue(build(EmployeeDocumentCategory.OTHER, "brev.eml").endsWith(".eml"));
        assertTrue(build(EmployeeDocumentCategory.OTHER, "ark.xlsx").endsWith(".xlsx"));
        assertTrue(build(EmployeeDocumentCategory.IDENTITY, "pas.JPG").endsWith(".JPG"));
        // No extension on the original ⇒ none invented.
        assertEquals("OTHER_dokument", build(EmployeeDocumentCategory.OTHER, "dokument"));
    }

    @Test
    void aVeryLongFilenameIsTruncatedToTheColumnWidthKeepingTheExtension() {
        String name = build(EmployeeDocumentCategory.CONTRACT, "a".repeat(400) + ".pdf");
        assertTrue(name.length() <= 255, "must fit display_name: " + name.length());
        assertTrue(name.endsWith(".pdf"));
    }

    @Test
    void pathSeparatorsInAFilenameCannotSurvive() {
        String name = build(EmployeeDocumentCategory.OTHER, "../../etc/passwd.pdf");
        assertFalse(name.contains("/"));
        assertFalse(name.contains(".."));
    }

    @Test
    void aNullCategoryYieldsNoName() {
        assertNull(EmployeeDocumentCategorizerRules.buildDisplayName(null, "x.pdf", "", null));
    }
}
