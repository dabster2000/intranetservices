package dk.trustworks.intranet.documentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.documentservice.dto.TemplatePlaceholderDTO;
import dk.trustworks.intranet.documentservice.model.enums.FieldType;
import dk.trustworks.intranet.utils.services.WordPlaceholderExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * The AI enrichment must never own the ORDER — displayOrder always comes
 * from first occurrence in the document, and any AI failure degrades to
 * prettified document-order definitions. These are the deterministic
 * guarantees the offer form's field ordering rests on.
 */
class TemplatePlaceholderAiServiceTest {

    private TemplatePlaceholderAiService service;
    private OpenAIService openAIService;

    @BeforeEach
    void setUp() {
        service = new TemplatePlaceholderAiService();
        openAIService = Mockito.mock(OpenAIService.class);
        service.openAIService = openAIService;
        service.placeholderExtractor = new WordPlaceholderExtractor();
        service.objectMapper = new ObjectMapper();
        service.enrichmentModel = "test-model";
        service.enrichmentReasoningEffort = Optional.of("low");
    }

    /** A minimal in-memory DOCX whose paragraphs carry the given texts, in order. */
    private static byte[] docx(String... paragraphs) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : paragraphs) {
                document.createParagraph().createRun().setText(text);
            }
            document.write(out);
            return out.toByteArray();
        }
    }

    private void aiReturns(String json) {
        Mockito.when(openAIService.askQuestionWithSchema(anyString(), anyString(), any(), anyString(),
                        Mockito.isNull(), anyString(), anyInt(), anyBoolean(), anyString()))
                .thenReturn(json);
    }

    @Test
    void aiFailure_fallsBackToDocumentOrderWithPrettifiedLabels() throws Exception {
        aiReturns("{}");
        byte[] bytes = docx(
                "Ansættelseskontrakt for {{EMPLOYEE_NAME}}",
                "Startdato: {{START_DATE}} med løn {{BASE_SALARY}}");

        List<TemplatePlaceholderDTO> suggestions = service.suggestPlaceholders(bytes);

        assertEquals(List.of("EMPLOYEE_NAME", "START_DATE", "BASE_SALARY"),
                suggestions.stream().map(TemplatePlaceholderDTO::getPlaceholderKey).toList(),
                "order = first occurrence in the document");
        assertEquals(List.of(1, 2, 3),
                suggestions.stream().map(TemplatePlaceholderDTO::getDisplayOrder).toList());
        assertEquals("Employee Name", suggestions.get(0).getLabel());
        assertEquals(FieldType.DATE, suggestions.get(1).getFieldType(),
                "*_DATE keys guess the DATE type");
        assertEquals(FieldType.STRING, suggestions.get(2).getFieldType());
        assertNull(suggestions.get(0).getFieldGroup(), "no groups without AI");
    }

    @Test
    void aiFields_overlayLabelsGroupsAndTypes_butNeverTheOrder() throws Exception {
        // AI answers in the WRONG order and with an unknown extra key — the
        // parsed document order and key set must win.
        aiReturns("""
                {"fields":[
                  {"key":"START_DATE","label":"Start Date","helpText":"First working day.",
                   "fieldGroup":"Dates","fieldType":"DATE"},
                  {"key":"EMPLOYEE_NAME","label":"Employee Name","helpText":"",
                   "fieldGroup":"Person","fieldType":"STRING"},
                  {"key":"NOT_IN_DOCUMENT","label":"Ghost","helpText":"x",
                   "fieldGroup":"Ghost","fieldType":"STRING"}
                ]}""");
        byte[] bytes = docx("{{EMPLOYEE_NAME}} starter {{START_DATE}}");

        List<TemplatePlaceholderDTO> suggestions = service.suggestPlaceholders(bytes);

        assertEquals(List.of("EMPLOYEE_NAME", "START_DATE"),
                suggestions.stream().map(TemplatePlaceholderDTO::getPlaceholderKey).toList(),
                "document order wins; unknown AI keys are dropped");
        assertEquals("Person", suggestions.get(0).getFieldGroup());
        assertEquals("Dates", suggestions.get(1).getFieldGroup());
        assertEquals("First working day.", suggestions.get(1).getHelpText());
        assertNull(suggestions.get(0).getHelpText(), "empty helpText normalises to null");
        assertEquals(FieldType.DATE, suggestions.get(1).getFieldType());
    }

    @Test
    void documentWithoutPlaceholders_returnsEmptyList_withoutCallingTheModel() throws Exception {
        byte[] bytes = docx("Ingen pladsholdere her.");

        assertTrue(service.suggestPlaceholders(bytes).isEmpty());
        Mockito.verifyNoInteractions(openAIService);
    }
}
