package dk.trustworks.intranet.documentservice.migration.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.documentservice.migration.services.SharePointMigrationCategorizerService.AiVerdict;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Categorizer + linker pure logic (runbook 2a-5 verify): hard validation
 * of AI output, image exclusion from the excerpt pass, and the exact
 * deterministic signing-linkage matching (decision A4).
 */
class SharePointMigrationCategorizerLogicTest {

    private final SharePointMigrationCategorizerService service = newService();

    private static SharePointMigrationCategorizerService newService() {
        SharePointMigrationCategorizerService service = new SharePointMigrationCategorizerService();
        service.objectMapper = new ObjectMapper();
        return service;
    }

    // ── AI verdict validation ──────────────────────────────────────────────

    @Test
    void invalidCategoryNameIsRejectedAsInconclusive() throws Exception {
        AiVerdict verdict = service.parseVerdict(new ObjectMapper().readTree(
                "{\"category\":\"PAYROLL\",\"archived\":false,\"label\":null,\"confidence\":\"HIGH\"}"),
                "x.pdf");
        assertTrue(verdict.inconclusive());
    }

    @Test
    void inconclusiveCategoryIsInconclusive() throws Exception {
        AiVerdict verdict = service.parseVerdict(new ObjectMapper().readTree(
                "{\"category\":\"INCONCLUSIVE\",\"archived\":false,\"label\":null,\"confidence\":\"HIGH\"}"),
                "x.pdf");
        assertTrue(verdict.inconclusive());
    }

    @Test
    void validHighVerdictParses() throws Exception {
        AiVerdict verdict = service.parseVerdict(new ObjectMapper().readTree(
                "{\"category\":\"salary\",\"archived\":true,\"label\":\"Lønregulering 2021\",\"confidence\":\"HIGH\"}"),
                "loenreg.pdf");
        assertFalse(verdict.inconclusive());
        assertEquals(EmployeeDocumentCategory.SALARY, verdict.category());
        assertTrue(verdict.archived());
        assertEquals("HIGH", verdict.confidence());
    }

    @Test
    void garbageConfidenceBecomesLow() throws Exception {
        AiVerdict verdict = service.parseVerdict(new ObjectMapper().readTree(
                "{\"category\":\"CONTRACT\",\"archived\":false,\"label\":null,\"confidence\":\"very sure\"}"),
                "x.pdf");
        assertEquals("LOW", verdict.confidence());
    }

    @Test
    void batchResponseMapsByIndexAndDefaultsToInconclusive() {
        List<AiVerdict> verdicts = service.parseBatchVerdicts("""
                {"results":[
                  {"index":1,"category":"CONTRACT","archived":false,"label":null,"confidence":"HIGH"},
                  {"index":7,"category":"SALARY","archived":false,"label":null,"confidence":"HIGH"}
                ]}""", List.of("a.pdf", "b.pdf", "c.pdf"));
        assertEquals(3, verdicts.size());
        assertTrue(verdicts.get(0).inconclusive());
        assertEquals(EmployeeDocumentCategory.CONTRACT, verdicts.get(1).category());
        assertTrue(verdicts.get(2).inconclusive());
    }

    @Test
    void unparseableResponseIsAllInconclusive() {
        List<AiVerdict> verdicts = service.parseBatchVerdicts("not json at all", List.of("a.pdf", "b.pdf"));
        assertEquals(2, verdicts.size());
        assertTrue(verdicts.get(0).inconclusive());
        assertTrue(verdicts.get(1).inconclusive());
    }

    // ── suggested_name hard validation (V476) ──────────────────────────────
    // The model's proposal ends up in a Content-Disposition header and on
    // the user's disk. Nothing about it is trusted.

    @Test
    void suggestedNameAlwaysGetsTheOriginalFileExtension() throws Exception {
        AiVerdict verdict = service.parseVerdict(new ObjectMapper().readTree(
                "{\"category\":\"SALARY\",\"archived\":false,\"label\":null,\"confidence\":\"HIGH\","
                        + "\"suggested_name\":\"2021_SALARY_loenregulering.docx\"}"),
                "loenreg_2021_final(2).pdf");
        assertEquals("2021_SALARY_loenregulering.pdf", verdict.suggestedName(),
                "the model's extension is discarded — a wrong one makes the download unopenable");
    }

    @Test
    void suggestedNameWithoutAnExtensionGetsTheOriginalOne() throws Exception {
        AiVerdict verdict = service.parseVerdict(new ObjectMapper().readTree(
                "{\"category\":\"IDENTITY\",\"archived\":false,\"label\":null,\"confidence\":\"HIGH\","
                        + "\"suggested_name\":\"2019-03-04_IDENTITY_sundhedskort\"}"),
                "Scan_20190304.pdf");
        assertEquals("2019-03-04_IDENTITY_sundhedskort.pdf", verdict.suggestedName());
    }

    @Test
    void blankOrNullSuggestedNameMeansNoNameProposedNotAnError() throws Exception {
        AiVerdict blank = service.parseVerdict(new ObjectMapper().readTree(
                "{\"category\":\"OTHER\",\"archived\":false,\"label\":null,\"confidence\":\"HIGH\","
                        + "\"suggested_name\":\"   \"}"), "x.pdf");
        assertNull(blank.suggestedName());
        assertFalse(blank.inconclusive(), "a missing name never invalidates the category verdict");

        AiVerdict nullName = service.parseVerdict(new ObjectMapper().readTree(
                "{\"category\":\"OTHER\",\"archived\":false,\"label\":null,\"confidence\":\"HIGH\","
                        + "\"suggested_name\":null}"), "x.pdf");
        assertNull(nullName.suggestedName());

        AiVerdict absent = service.parseVerdict(new ObjectMapper().readTree(
                "{\"category\":\"OTHER\",\"archived\":false,\"label\":null,\"confidence\":\"HIGH\"}"),
                "x.pdf");
        assertNull(absent.suggestedName(), "an absent property must not throw");
    }

    @Test
    void pathTraversalAndSlashesAreSanitizedAway() throws Exception {
        AiVerdict verdict = service.parseVerdict(new ObjectMapper().readTree(
                "{\"category\":\"CONTRACT\",\"archived\":false,\"label\":null,\"confidence\":\"HIGH\","
                        + "\"suggested_name\":\"../../etc/passwd\"}"), "kontrakt.pdf");
        String name = verdict.suggestedName();
        assertFalse(name.contains("/"), "no slashes survive: " + name);
        assertFalse(name.contains(".."), "no parent-directory hops survive: " + name);
        assertTrue(name.endsWith(".pdf"));
    }

    @Test
    void headerInjectionCharactersAreStripped() throws Exception {
        AiVerdict verdict = service.parseVerdict(new ObjectMapper().readTree(
                "{\"category\":\"CONTRACT\",\"archived\":false,\"label\":null,\"confidence\":\"HIGH\","
                        + "\"suggested_name\":\"evil\\r\\nSet-Cookie: a=b\\\";x=\\\"1\"}"), "k.pdf");
        String name = verdict.suggestedName();
        assertFalse(name.contains("\r") || name.contains("\n"),
                "CR/LF would split the Content-Disposition header: " + name);
        assertFalse(name.contains("\""), "quotes would break out of a quoted filename: " + name);
    }

    @Test
    void overlongSuggestedNameIsTruncatedButKeepsItsExtension() throws Exception {
        String longName = "a".repeat(400) + ".pdf";
        AiVerdict verdict = service.parseVerdict(new ObjectMapper().readTree(
                "{\"category\":\"OTHER\",\"archived\":false,\"label\":null,\"confidence\":\"HIGH\","
                        + "\"suggested_name\":\"" + longName + "\"}"), "orig.pdf");
        String name = verdict.suggestedName();
        assertEquals(255, name.length(), "must fit the display_name column");
        assertTrue(name.endsWith(".pdf"), "truncation must not eat the extension: " + name);
    }

    @Test
    void danishCharactersInASuggestedNameSurvive() throws Exception {
        AiVerdict verdict = service.parseVerdict(new ObjectMapper().readTree(
                "{\"category\":\"SALARY\",\"archived\":false,\"label\":null,\"confidence\":\"HIGH\","
                        + "\"suggested_name\":\"2021_SALARY_lønregulering.pdf\"}"), "loenreg.pdf");
        assertEquals("2021_SALARY_lønregulering.pdf", verdict.suggestedName());
    }

    // ── Excerpt pass eligibility: images NEVER get pass 2 (decision A3) ────

    @Test
    void onlyPdfAndDocxAreExcerptEligible() {
        assertTrue(SharePointMigrationCategorizerService.excerptEligible("application/pdf"));
        assertTrue(SharePointMigrationCategorizerService.excerptEligible(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertFalse(SharePointMigrationCategorizerService.excerptEligible("image/jpeg"));
        assertFalse(SharePointMigrationCategorizerService.excerptEligible("image/png"));
        assertFalse(SharePointMigrationCategorizerService.excerptEligible("message/rfc822"));
        assertFalse(SharePointMigrationCategorizerService.excerptEligible("application/octet-stream"));
        assertFalse(SharePointMigrationCategorizerService.excerptEligible(null));
    }

    // ── Signing linkage: exact matching only (decision A4) ─────────────────

    @Test
    void filenameFromUrlDecodesAndSanitizes() {
        assertEquals("Ansaettelseskontrakt_signed_2025-12-11.pdf",
                SharePointMigrationCategorizerService.filenameFromUrl(
                        "https://trustworks.sharepoint.com/sites/hr/Docs/Ansaettelseskontrakt_signed_2025-12-11.pdf?web=1"));
        assertEquals("Kontrakt med mellemrum_signed_x.pdf",
                SharePointMigrationCategorizerService.filenameFromUrl(
                        "https://x/y/Kontrakt%20med%20mellemrum_signed_x.pdf"));
        assertNull(SharePointMigrationCategorizerService.filenameFromUrl(null));
        assertNull(SharePointMigrationCategorizerService.filenameFromUrl(""));
    }

    @Test
    void signedPatternMatchesExactBaseOnly() {
        Pattern pattern = SharePointMigrationCategorizerService.signedPattern("Ansættelseskontrakt.pdf");
        assertTrue(pattern.matcher("Ansættelseskontrakt_signed_2025-12-11.pdf").matches());
        assertFalse(pattern.matcher("Anden kontrakt_signed_2025-12-11.pdf").matches());
        assertFalse(pattern.matcher("Ansættelseskontrakt.pdf").matches());
    }

    @Test
    void matchesExactlyRequiresUrlFilenameOrPattern() {
        Pattern pattern = SharePointMigrationCategorizerService.signedPattern("Kontrakt.pdf");

        // URL filename match (and it must look like a signed artifact).
        assertTrue(SharePointMigrationCategorizerService.matchesExactly(
                "Kontrakt_signed_2024.pdf", "Kontrakt_signed_2024.pdf", pattern));
        // A same-name match that is NOT a signed artifact never links.
        assertFalse(SharePointMigrationCategorizerService.matchesExactly(
                "Kontrakt.pdf", "Kontrakt.pdf", null));
        // Pattern match without URL filename.
        assertTrue(SharePointMigrationCategorizerService.matchesExactly(
                "Kontrakt_signed_2024-01-01.pdf", null, pattern));
        // No signals ⇒ no link.
        assertFalse(SharePointMigrationCategorizerService.matchesExactly(
                "Kontrakt_signed_2024-01-01.pdf", null, null));
        assertFalse(SharePointMigrationCategorizerService.matchesExactly(null, "x.pdf", pattern));
    }

    // ── DOCX text extraction helper ────────────────────────────────────────

    @Test
    void docxTextReturnsNullOnGarbage() {
        assertNull(SharePointMigrationCategorizerService.docxText(new byte[]{1, 2, 3}));
    }
}
