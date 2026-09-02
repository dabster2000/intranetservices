package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.documentservice.maintenance.EmployeeDocumentCategorizerRules;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.model.enums.TemplateCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The names a converted candidate's documents land under.
 *
 * <p>Before {@link S3EmployeePromotionService} named its output, promotion
 * left {@code display_name} null and put {@code "signed"}/{@code "unsigned"}
 * in {@code label} — which the profile Documents tab uses as its title
 * fallback. Every promoted contract therefore appeared to the employee as
 * a row titled "Signed". These cases are the real filenames from the
 * production dossier revisions.</p>
 */
class PromotedDocumentNamingTest {

    private static String promoted(EmployeeDocumentCategory category, String filename) {
        return EmployeeDocumentCategorizerRules.buildDisplayName(category, filename, null, null);
    }

    @Test
    void theSignedEmploymentContract() {
        assertEquals("CONTRACT_ansættelseskontrakt.pdf",
                promoted(EmployeeDocumentCategory.CONTRACT, "Ansættelseskontrakt_signed.pdf"));
    }

    @Test
    void signedMarkersDoNotSurviveIntoTheSubject() {
        // Only signed documents promote at all, so "signed" carries no
        // information in the title — it is the only kind of contract in the
        // file.
        assertEquals(promoted(EmployeeDocumentCategory.CONTRACT, "Ansættelseskontrakt.pdf"),
                promoted(EmployeeDocumentCategory.CONTRACT, "Ansættelseskontrakt_signed.pdf"));
        assertEquals("CONTRACT_ansættelseskontrakt.pdf",
                promoted(EmployeeDocumentCategory.CONTRACT, "Ansættelseskontrakt underskrevet.pdf"));
    }

    /**
     * The naming builder is deliberately version-blind — it drops "v2",
     * "signed" and trailing digit runs — which is why five renders of one
     * contract collapsed to a single title and the employee's file read as
     * the same document six times. That is only safe because the selection
     * rule now files one row per signed document: these are the six real
     * filenames from the production hire that exposed the defect, and they
     * must produce six distinct names with no dedupe layer involved.
     */
    @Test
    void theSignedSetOfAHireGetsOneDistinctNamePerDocument() {
        List<String> names = Stream.of(
                        "Tillaeg_garantibonus_Henrik_Falch_Midtgaard_signed.pdf",
                        "100-dages-plan_signed.pdf",
                        "Ansættelseskontrakt_signed.pdf",
                        "Tillæg - Ansættelsesaftale Associate Partner v2_signed.pdf",
                        "Tillæg - Konkurrence- og Kundeklausul_signed.pdf",
                        "Bilag til partner ansættelseskontrakt  TW_SalgsBonusModel_v2_signed.pdf")
                .map(f -> promoted(EmployeeDocumentCategory.CONTRACT, f))
                .toList();

        assertEquals(6, Set.copyOf(names).size(), "collides in the employee's file: " + names);
    }

    @Test
    void theLoyaltyProgrammeAppendix() {
        assertEquals("CONTRACT_din-del-af-trustworks-loyalitetsprogram.pdf",
                promoted(EmployeeDocumentCategory.CONTRACT,
                        "Din del af Trustworks - Loyalitetsprogram_signed.pdf"));
    }

    @Test
    void theGuaranteeBonusAddendumKeepsItsSubject() {
        assertEquals("CONTRACT_tillaeg-garantibonus-henrik-falch-midtgaard.pdf",
                promoted(EmployeeDocumentCategory.CONTRACT,
                        "Tillaeg_garantibonus_Henrik_Falch_Midtgaard_signed.pdf"));
    }

    @Test
    void anOnboardingIdentityPhoto() {
        assertEquals("IDENTITY_img.jpeg",
                promoted(EmployeeDocumentCategory.IDENTITY, "IMG_3317.jpeg"));
    }

    @Test
    void aSigningArchivalNameCarriesItsOwnDate() {
        assertEquals("2026-08-09_CONTRACT_studenteransættelseskontrakt-tw-frederikke.pdf",
                promoted(EmployeeDocumentCategory.CONTRACT,
                        "Studenteransættelseskontrakt_TW - Frederikke_signed_2026-08-09_203311.pdf"));
    }

    // ── Post-hire signing archival ─────────────────────────────────────────

    /**
     * {@code EmployeeSigningArchivalService} stores
     * {@code {name}_signed_{yyyy-MM-dd_HHmmss}.pdf} as the original
     * filename — that suffix is what keeps repeat sends of the same
     * document distinct — but builds the display name from the clean base
     * name, so the archival timestamp never reaches the employee's title.
     */
    private static String archived(EmployeeDocumentCategory category, String documentName) {
        return EmployeeDocumentCategorizerRules.buildDisplayName(category, documentName + ".pdf", null, null);
    }

    @Test
    void aPayRaiseFilesUnderItsOwnName() {
        assertEquals("SALARY_lønregulering.pdf",
                archived(EmployeeDocumentCategory.SALARY, "Lønregulering"));
    }

    @Test
    void theArchivalTimestampNeverReachesTheTitle() {
        String name = archived(EmployeeDocumentCategory.SALARY, "Lønregulering Anders Boier");
        assertEquals("SALARY_lønregulering-anders-boier.pdf", name);
        assertFalse(name.contains("signed"), "archival marker leaked into the name: " + name);
        assertFalse(name.matches(".*\\d{6}.*"), "archival timestamp leaked into the name: " + name);
    }

    @Test
    void salaryTemplatesCanNowReachTheSalaryCategory() {
        // Before this, TemplateCategory had no SALARY at all, so a pay-raise
        // template could only ever map to ADDENDUM or OTHER.
        assertEquals(EmployeeDocumentCategory.SALARY,
                EmployeeDocumentCategory.fromTemplateCategory(TemplateCategory.SALARY));
    }
}
