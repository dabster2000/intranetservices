package dk.trustworks.intranet.documentservice.services;

import com.deepoove.poi.data.DocxRenderData;
import dk.trustworks.intranet.documentservice.model.TemplateClauseLinkEntity;
import dk.trustworks.intranet.documentservice.model.TemplateClausePlaceholderEntity;
import dk.trustworks.intranet.documentservice.model.enums.ClauseRenderMode;
import dk.trustworks.intranet.documentservice.services.ClauseCompositionService.ResolvedClauseItem;
import dk.trustworks.intranet.utils.NumberUtils;
import dk.trustworks.intranet.utils.dto.signing.SelectedClauseDTO;
import dk.trustworks.intranet.documentservice.model.enums.FieldType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free tests for the composition core (template-clauses spec §5):
 * anchor detection, required-link auto-inclusion, parameter resolution
 * with type-aware formatting, and — most load-bearing — a real poi-tl
 * round-trip proving a {@code {{+ITEMn}}} sub-document merge renders the
 * fragment's tags with the merged value map instead of discarding them.
 */
class ClauseCompositionCoreTest {

    // ---- Anchor detection ------------------------------------------------------

    @Test
    void anchorDetection_matchesPlainAndMergeSyntax() {
        assertTrue(ClauseCompositionService.hasClausesAnchorText("Vilkår\n{{CLAUSES}}\nUnderskrift"));
        assertTrue(ClauseCompositionService.hasClausesAnchorText("{{+CLAUSES}}"));
        assertFalse(ClauseCompositionService.hasClausesAnchorText("{{CLAUSE}} {{EMPLOYEE_NAME}}"));
        assertFalse(ClauseCompositionService.hasClausesAnchorText(null));
    }

    // ---- Required links --------------------------------------------------------

    @Test
    void withRequiredLinks_appendsUnselectedRequiredClause() {
        TemplateClauseLinkEntity required = link("clause-req", true);
        TemplateClauseLinkEntity optional = link("clause-opt", false);
        List<SelectedClauseDTO> effective = ClauseCompositionService.withRequiredLinks(
                List.of(new SelectedClauseDTO("clause-opt", Map.of(), null, null, 0)),
                List.of(required, optional));
        assertEquals(2, effective.size());
        assertEquals("clause-req", effective.get(1).clauseUuid());
        assertTrue(effective.get(1).displayOrder() > effective.get(0).displayOrder());
    }

    @Test
    void withRequiredLinks_alreadySelectedRequiredClauseNotDuplicated() {
        List<SelectedClauseDTO> effective = ClauseCompositionService.withRequiredLinks(
                List.of(new SelectedClauseDTO("clause-req", Map.of("K", "v"), null, null, 0)),
                List.of(link("clause-req", true)));
        assertEquals(1, effective.size());
        assertEquals(Map.of("K", "v"), effective.get(0).parameterValues());
    }

    @Test
    void withRequiredLinks_noSelectionAndNoRequiredLinksStaysEmpty() {
        assertTrue(ClauseCompositionService.withRequiredLinks(List.of(), List.of(link("x", false))).isEmpty());
    }

    // ---- Parameter resolution --------------------------------------------------

    @Test
    void resolveParameterValues_appliesDefaultsAndFormatsCurrency() {
        Map<String, String> resolved = ClauseCompositionService.resolveParameterValues(
                "Garantibonus",
                List.of(
                        parameter("GB_AMOUNT", FieldType.CURRENCY, true, null),
                        parameter("GB_NOTE", FieldType.TEXT, false, "Standard")),
                Map.of("GB_AMOUNT", "60000"));
        assertEquals(NumberUtils.formatCurrency(60000d), resolved.get("GB_AMOUNT"));
        assertEquals("Standard", resolved.get("GB_NOTE"));
    }

    @Test
    void resolveParameterValues_missingRequiredParameterFails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                ClauseCompositionService.resolveParameterValues(
                        "Garantibonus",
                        List.of(parameter("GB_AMOUNT", FieldType.CURRENCY, true, null)),
                        Map.of()));
        assertTrue(e.getMessage().contains("GB_AMOUNT"));
    }

    @Test
    void resolveParameterValues_unknownKeyFails() {
        // A typo'd key would render a blank merge field via DiscardHandler.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                ClauseCompositionService.resolveParameterValues(
                        "Garantibonus",
                        List.of(parameter("GB_AMOUNT", FieldType.CURRENCY, false, null)),
                        Map.of("GB_AMONT", "60000")));
        assertTrue(e.getMessage().contains("GB_AMONT"));
    }

    // ---- poi-tl sub-document merge round-trip ----------------------------------

    @Test
    void renderDocx_mergesFragmentWithValuesAtItemAnchor() throws Exception {
        // Fragment: one paragraph with two tags.
        byte[] fragment = docxOf("Der ydes en garantibonus på {{GB_AMOUNT}} frem til {{GB_PERIOD_END}}.");

        ResolvedClauseItem item = new ResolvedClauseItem(
                "clause-uuid", "version-uuid", "GARANTIBONUS", "Garantibonus",
                ClauseRenderMode.ADDENDUM, ClauseRenderMode.ADDENDUM,
                Map.of(), null, null, 0, "file-uuid");

        byte[] container = ClauseCompositionService.buildBlockContainer(List.of(item), true);

        Map<String, Object> values = new HashMap<>();
        values.put("ITEM0", new DocxRenderData(fragment,
                List.of(Map.of("GB_AMOUNT", "kr. 60.000,00", "GB_PERIOD_END", "31.12.2026"))));
        byte[] rendered = ClauseCompositionService.renderDocx(container, values);

        String text = textOf(rendered);
        assertTrue(text.contains("1. Garantibonus"), "numbered heading missing: " + text);
        assertTrue(text.contains("kr. 60.000,00"), "fragment value not substituted: " + text);
        assertTrue(text.contains("31.12.2026"), "fragment value not substituted: " + text);
        assertFalse(text.contains("{{"), "unresolved tag survived the merge: " + text);
    }

    @Test
    void renderDocx_customTextBecomesParagraphs() throws Exception {
        byte[] custom = ClauseCompositionService.buildTextDocx("Første linje.\n\nAnden linje.");
        ResolvedClauseItem item = new ResolvedClauseItem(
                null, null, null, "Individuel aftale",
                ClauseRenderMode.ADDENDUM, ClauseRenderMode.ADDENDUM,
                Map.of(), "Individuel aftale", "Første linje.\n\nAnden linje.", 0, null);
        byte[] container = ClauseCompositionService.buildBlockContainer(List.of(item), true);

        Map<String, Object> values = new HashMap<>();
        values.put("ITEM0", new DocxRenderData(custom, null));
        String text = textOf(ClauseCompositionService.renderDocx(container, values));
        assertTrue(text.contains("1. Individuel aftale"));
        assertTrue(text.contains("Første linje."));
        assertTrue(text.contains("Anden linje."));
    }

    @Test
    void defaultShell_carriesTheClausesAnchor() throws Exception {
        String text = textOf(ClauseCompositionService.buildDefaultShell());
        assertTrue(text.contains(ClauseCompositionService.ADDENDUM_DOCUMENT_NAME));
        assertTrue(ClauseCompositionService.hasClausesAnchorText(text));
    }

    @Test
    void renderDocx_mergesBlockIntoShellAtClausesAnchor() throws Exception {
        // The full ADDENDUM chain: rendered block → shell {{CLAUSES}} anchor,
        // using the same bind-by-key configuration as the PDF converter.
        byte[] block = ClauseCompositionService.buildTextDocx("Punktindhold her.");
        Map<String, Object> values = new HashMap<>();
        values.put(ClauseService.CLAUSES_ANCHOR_KEY, new DocxRenderData(block, null));
        String text = textOf(ClauseCompositionService.renderDocx(
                ClauseCompositionService.buildDefaultShell(), values));
        assertTrue(text.contains("Punktindhold her."), "block not merged into shell: " + text);
        assertFalse(text.contains("{{CLAUSES}}"), "anchor tag survived: " + text);
    }

    // ---- Company fact tokens ---------------------------------------------------

    @Test
    void renderValuesForBaseDocument_mergesClauseValuesAndCompanyFactTokens() {
        // Clause fragments may reference {{COMPANY_*}} tags the base template
        // does not declare — the plan's fact tokens are what resolves them.
        ResolvedClauseItem item = new ResolvedClauseItem(
                "clause-uuid", "version-uuid", "GARANTIBONUS", "Garantibonus",
                ClauseRenderMode.ADDENDUM, ClauseRenderMode.ADDENDUM,
                Map.of("GB_AMOUNT", "kr. 60.000,00"), null, null, 0, "file-uuid");
        ClauseCompositionService.CompositionPlan plan =
                new ClauseCompositionService.CompositionPlan(List.of(item), List.of(), null)
                        .withCompanyFactTokens(Map.of("COMPANY_SHORT_NAME", "Trustworks"));

        Map<String, Object> values = new ClauseCompositionService()
                .renderValuesForBaseDocument(plan, "base-file", Map.of("EMPLOYEE_NAME", "Jane"));

        assertEquals("Jane", values.get("EMPLOYEE_NAME"));
        assertEquals("kr. 60.000,00", values.get("GB_AMOUNT"));
        assertEquals("Trustworks", values.get("COMPANY_SHORT_NAME"));
    }

    // ---- helpers ---------------------------------------------------------------

    private static TemplateClauseLinkEntity link(String clauseUuid, boolean required) {
        TemplateClauseLinkEntity link = new TemplateClauseLinkEntity();
        link.setClauseUuid(clauseUuid);
        link.setRequired(required);
        return link;
    }

    private static TemplateClausePlaceholderEntity parameter(
            String key, FieldType type, boolean required, String defaultValue) {
        TemplateClausePlaceholderEntity parameter = new TemplateClausePlaceholderEntity();
        parameter.setPlaceholderKey(key);
        parameter.setLabel(key);
        parameter.setFieldType(type);
        parameter.setRequired(required);
        parameter.setDefaultValue(defaultValue);
        return parameter;
    }

    private static byte[] docxOf(String paragraphText) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(paragraphText);
            document.write(out);
            return out.toByteArray();
        }
    }

    private static String textOf(byte[] docx) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                text.append(paragraph.getText()).append('\n');
            }
            return text.toString();
        }
    }
}
