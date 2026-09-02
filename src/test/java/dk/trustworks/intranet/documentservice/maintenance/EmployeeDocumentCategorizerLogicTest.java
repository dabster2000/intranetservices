package dk.trustworks.intranet.documentservice.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.documentservice.maintenance.EmployeeDocumentCategorizerService.AiVerdict;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Categorizer + linker pure logic (runbook 2a-5 verify): hard validation
 * of AI output, image exclusion from the excerpt pass, and the exact
 * deterministic signing-linkage matching (decision A4).
 */
class EmployeeDocumentCategorizerLogicTest {

    private final EmployeeDocumentCategorizerService service = newService();

    private static EmployeeDocumentCategorizerService newService() {
        EmployeeDocumentCategorizerService service = new EmployeeDocumentCategorizerService();
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
        assertTrue(EmployeeDocumentCategorizerService.excerptEligible("application/pdf"));
        assertTrue(EmployeeDocumentCategorizerService.excerptEligible(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertFalse(EmployeeDocumentCategorizerService.excerptEligible("image/jpeg"));
        assertFalse(EmployeeDocumentCategorizerService.excerptEligible("image/png"));
        assertFalse(EmployeeDocumentCategorizerService.excerptEligible("message/rfc822"));
        assertFalse(EmployeeDocumentCategorizerService.excerptEligible("application/octet-stream"));
        assertFalse(EmployeeDocumentCategorizerService.excerptEligible(null));
    }

    // ── Signing linkage: exact matching only (decision A4) ─────────────────

    @Test
    void signedPatternMatchesExactBaseOnly() {
        Pattern pattern = EmployeeDocumentCategorizerService.signedPattern("Ansættelseskontrakt.pdf");
        assertTrue(pattern.matcher("Ansættelseskontrakt_signed_2025-12-11.pdf").matches());
        assertFalse(pattern.matcher("Anden kontrakt_signed_2025-12-11.pdf").matches());
        assertFalse(pattern.matcher("Ansættelseskontrakt.pdf").matches());
    }

    @Test
    void matchesExactlyRequiresThePattern() {
        Pattern pattern = EmployeeDocumentCategorizerService.signedPattern("Kontrakt.pdf");

        // Pattern match.
        assertTrue(EmployeeDocumentCategorizerService.matchesExactly(
                "Kontrakt_signed_2024-01-01.pdf", pattern));
        // A same-name file that is NOT a signed artifact never links.
        assertFalse(EmployeeDocumentCategorizerService.matchesExactly(
                "Kontrakt.pdf", pattern));
        // No signals ⇒ no link.
        assertFalse(EmployeeDocumentCategorizerService.matchesExactly(
                "Kontrakt_signed_2024-01-01.pdf", null));
        assertFalse(EmployeeDocumentCategorizerService.matchesExactly(null, pattern));
    }

    @Test
    void signedPatternTreatsSpacesAndUnderscoresAsInterchangeable() {
        // The legacy (pre-migration) upload wrote spaces as underscores, so a
        // document name carrying a space produced a stored file its own
        // pattern could never match. Production case 6a16b54e:
        //   document_name = "Timelønskontrakt_Joao Almeida"
        //   stored file   = "Timelønskontrakt_Joao_Almeida_signed_2026-05-28_115036.pdf"
        Pattern pattern = EmployeeDocumentCategorizerService.signedPattern("Timelønskontrakt_Joao Almeida");
        assertTrue(pattern.matcher("Timelønskontrakt_Joao_Almeida_signed_2026-05-28_115036.pdf").matches());
        assertTrue(pattern.matcher("Timelønskontrakt Joao Almeida_signed_2026-05-28_115036.pdf").matches());

        // Interchangeability must not soften the base: a different name
        // still never matches.
        assertFalse(pattern.matcher("Timelønskontrakt_Joao_Hansen_signed_2026-05-28_115036.pdf").matches());
        assertFalse(pattern.matcher("Timelønskontrakt_Joao_Almeida.pdf").matches());
    }

    @Test
    void signedPatternStillRequiresTheWholeName() {
        Pattern pattern = EmployeeDocumentCategorizerService.signedPattern("Kontrakt.pdf");
        assertFalse(pattern.matcher("Tillæg Kontrakt_signed_2026-01-01.pdf").matches());
        assertFalse(pattern.matcher("Kontrakt udvidet_signed_2026-01-01.pdf").matches());
        assertNull(EmployeeDocumentCategorizerService.signedPattern("   "));
        assertNull(EmployeeDocumentCategorizerService.signedPattern(null));
    }

    @Test
    void signedTimestampIsolatesTheBatchStamp() {
        // Every document of one signing envelope carries the same stamp —
        // that is what tells a multi-document case apart from a re-filing.
        assertEquals("2026-03-06_172620", EmployeeDocumentCategorizerService.signedTimestamp(
                "Ansættelseskontrakt_signed_2026-03-06_172620.pdf"));
        assertEquals("2026-03-06_172620", EmployeeDocumentCategorizerService.signedTimestamp(
                "Din_del_af_Trustworks_-_Loyalitetsprogram_signed_2026-03-06_172620.pdf"));
        // Different stamps must NOT group: a document filed twice is not a batch.
        assertNotEquals(
                EmployeeDocumentCategorizerService.signedTimestamp("Fratrædelseserklæring_signed_2026-06-27_103029.pdf"),
                EmployeeDocumentCategorizerService.signedTimestamp("Fratrædelseserklæring_signed_2026-06-27_103527.pdf"));
    }

    @Test
    void signedTimestampFallsBackToTheWholeNameWhenUnmarked() {
        // No marker ⇒ the filename itself is the key, so two unmarked files
        // group separately instead of being mistaken for one envelope.
        assertEquals("Kontrakt.pdf", EmployeeDocumentCategorizerService.signedTimestamp("Kontrakt.pdf"));
        assertEquals("", EmployeeDocumentCategorizerService.signedTimestamp(null));
        assertNotEquals(
                EmployeeDocumentCategorizerService.signedTimestamp("A.pdf"),
                EmployeeDocumentCategorizerService.signedTimestamp("B.pdf"));
    }

    // ── DOCX text extraction helper ────────────────────────────────────────

    @Test
    void docxTextReturnsNullOnGarbage() {
        assertNull(EmployeeDocumentCategorizerService.docxText(new byte[]{1, 2, 3}));
    }

    // ── Re-run guard ───────────────────────────────────────────────────────

    /**
     * A categorized document is a decision and is never overwritten —
     * this is what makes the job safely re-runnable, and it holds no
     * matter what the caller asked for.
     */
    @Test
    void aRealCategoryIsNeverReconsidered() {
        for (EmployeeDocumentCategory placed : EmployeeDocumentCategory.values()) {
            if (placed == EmployeeDocumentCategory.OTHER) continue;
            assertTrue(EmployeeDocumentCategorizerService.skip(placed, false, false));
            assertTrue(EmployeeDocumentCategorizerService.skip(placed, false, true));
            assertTrue(EmployeeDocumentCategorizerService.skip(placed, true, true));
        }
    }

    @Test
    void anUnflaggedOtherIsAlwaysACandidate() {
        assertFalse(EmployeeDocumentCategorizerService.skip(
                EmployeeDocumentCategory.OTHER, false, false));
        assertFalse(EmployeeDocumentCategorizerService.skip(
                EmployeeDocumentCategory.OTHER, false, true));
    }

    /**
     * The regression this parameter exists for: 126 production rows sat
     * at OTHER *and* flagged for review, and every subsequent run skipped
     * them because the flag was read as a human decision. It is not one —
     * it records that the AI could not place the document — so a caller
     * must be able to ask for them back.
     */
    @Test
    void aFlaggedOtherIsStrandedUntilTheCallerAsksForIt() {
        assertTrue(EmployeeDocumentCategorizerService.skip(
                EmployeeDocumentCategory.OTHER, true, false));
        assertFalse(EmployeeDocumentCategorizerService.skip(
                EmployeeDocumentCategory.OTHER, true, true));
    }
}
