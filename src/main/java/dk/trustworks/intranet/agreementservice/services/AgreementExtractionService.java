package dk.trustworks.intranet.agreementservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.agreementservice.model.enums.BackfillItemStatus;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One document → zero-or-more agreement proposals (template-clauses spec
 * §10.2): PDFBox text extraction with a page-1 vision fallback for
 * scans, then a single structured-output model call.
 *
 * <p>Privacy posture (spec §9, the AI-brief PII lessons): every call
 * passes {@code store=false}, prompts carry only the document content —
 * never the employee's identity from our records — and no document text
 * is ever logged. Raw PDF bytes NEVER go to the vision API (the PR #178
 * HTTP-400 posture); only a rendered PNG of page 1 does.</p>
 */
@JBossLog
@ApplicationScoped
public class AgreementExtractionService {

    /** Contracts run long (24 sections); cap keeps the token bill bounded. */
    static final int MAX_TEXT_CHARS = 30_000;
    /** Under this many extracted chars the PDF is treated as image-only (scan). */
    static final int MIN_TEXT_CHARS = 200;
    /** Page-1 render resolution for the vision fallback. */
    static final int RENDER_DPI = 150;

    private static final String SCHEMA_NAME = "agreement_backfill_extraction";
    private static final String EMPTY_FALLBACK = "{\"proposals\":[]}";

    @Inject
    ObjectMapper objectMapper;

    @Inject
    OpenAIService openAIService;

    /** Text-path model (spec: {@code dk.trustworks.agreements.ai.extraction-model}). */
    @ConfigProperty(name = "dk.trustworks.agreements.ai.extraction-model", defaultValue = "gpt-5.6-terra")
    String extractionModel;

    /**
     * Reasoning effort for the text path. MUST be empty when
     * {@code extraction-model} points at a non-reasoning (gpt-4o-family)
     * model, which rejects the node with HTTP 400 — hence
     * {@code Optional} (the SRCFG00040 empty-String trap).
     */
    @ConfigProperty(name = "dk.trustworks.agreements.ai.extraction-reasoning-effort", defaultValue = "low")
    Optional<String> extractionReasoningEffort;

    /**
     * Output budget for the text path. Reasoning-class models spend this
     * budget on hidden reasoning FIRST — raise it together with the
     * effort, or a 2xx response arrives with no output text (the
     * documented {@code openai.vision-model} trap).
     */
    @ConfigProperty(name = "dk.trustworks.agreements.ai.extraction-max-output-tokens", defaultValue = "6000")
    int extractionMaxOutputTokens;

    /**
     * Vision-path model for scanned page-1 fallbacks. Deliberately a
     * proven non-reasoning vision model (the receipt-OCR posture): no
     * {@code reasoning} node is sent on this path.
     */
    @ConfigProperty(name = "dk.trustworks.agreements.ai.vision-model", defaultValue = "gpt-4o-mini")
    String visionModel;

    @ConfigProperty(name = "dk.trustworks.agreements.ai.vision-max-output-tokens", defaultValue = "4096")
    int visionMaxOutputTokens;

    /** One proposed registry record; dates ISO, amount already parsed. */
    public record Proposal(String agreementType, String title, String summary,
                           BigDecimal amount, String currency,
                           LocalDate validFrom, LocalDate validTo, LocalDate effectiveDate,
                           String verbatimQuote, double confidence) {
    }

    /**
     * Outcome of one document: a review status
     * ({@code PROPOSED}/{@code NO_PROPOSALS}/{@code FAILED}), the
     * proposals and a short diagnostic note (never document text).
     */
    public record ExtractionResult(BackfillItemStatus status, List<Proposal> proposals, String note) {
        public static ExtractionResult failed(String note) {
            return new ExtractionResult(BackfillItemStatus.FAILED, List.of(), note);
        }
    }

    /**
     * Extract agreement proposals from one PDF. Never throws — an
     * unreadable document degrades to {@code FAILED} (retried by the
     * next run).
     *
     * @param pdfBytes the document
     * @param typeKeys the active {@code agreement_types} keys the model may propose
     */
    public ExtractionResult extract(byte[] pdfBytes, List<String> typeKeys) {
        String text;
        String pageImageBase64 = null;
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            String raw = new PDFTextStripper().getText(document);
            text = raw == null ? "" : raw.strip();
            if (text.length() < MIN_TEXT_CHARS) {
                // Image-only PDF (scan): render page 1 as PNG for the
                // vision path. NEVER the raw PDF bytes (PR #178 posture).
                if (document.getNumberOfPages() == 0) {
                    return ExtractionResult.failed("PDF has no pages");
                }
                BufferedImage page = new PDFRenderer(document).renderImageWithDPI(0, RENDER_DPI);
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(page, "png", png);
                pageImageBase64 = Base64.getEncoder().encodeToString(png.toByteArray());
            }
        } catch (Exception e) {
            return ExtractionResult.failed("Unreadable PDF: " + e.getClass().getSimpleName());
        }

        ObjectNode schema = buildSchema(typeKeys);
        String response;
        boolean usedVision = pageImageBase64 != null;
        if (usedVision) {
            response = openAIService.askWithSchemaAndImage(
                    systemPrompt(), visionInstruction(), pageImageBase64, "image/png",
                    schema, SCHEMA_NAME, EMPTY_FALLBACK,
                    visionModel, visionMaxOutputTokens, false);
        } else {
            String capped = text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
            response = openAIService.askQuestionWithSchema(
                    systemPrompt(), userMessage(capped),
                    schema, SCHEMA_NAME, EMPTY_FALLBACK,
                    extractionModel, extractionMaxOutputTokens, false,
                    extractionReasoningEffort.filter(effort -> !effort.isBlank()).orElse(null));
        }

        List<Proposal> proposals = parseProposals(response, typeKeys);
        if (proposals == null) {
            // "{}" is OpenAIService's error/empty sentinel — treat as a
            // retryable failure, not as "no agreements in this document".
            return ExtractionResult.failed(usedVision
                    ? "Vision extraction returned no output"
                    : "Extraction returned no output");
        }
        String note = usedVision ? "Scanned document — vision fallback on page 1" : null;
        return proposals.isEmpty()
                ? new ExtractionResult(BackfillItemStatus.NO_PROPOSALS, List.of(), note)
                : new ExtractionResult(BackfillItemStatus.PROPOSED, proposals, note);
    }

    // ---- Prompting -------------------------------------------------------------

    private static String systemPrompt() {
        return """
                You analyse Danish employment documents for Trustworks (contracts, addendums \
                ("tillæg"), declarations). Identify INDIVIDUALLY NEGOTIATED agreement terms that \
                deviate from or extend standard employment terms, and report them as structured \
                proposals. Typical terms and their type keys:
                - GARANTIBONUS: a guaranteed bonus for a defined period.
                - PROEVETID_FRAVIGET: probation period waived or shortened.
                - ANCIENNITET: seniority counted from earlier employment.
                - OPSIGELSESVARSEL: notice period beyond funktionærloven.
                - LOYALITETSPROGRAM: participation in "Din del af Trustworks" (note the wording \
                version in the summary).
                - SAERLIGE_VILKAAR: other individually negotiated terms.
                - INDIVIDUEL: bespoke agreements that fit no other type.
                Rules: propose ONLY genuinely individual terms — a standard contract, CV, \
                certificate, policy or letter yields an empty list. Quote the exact sentence(s) \
                the proposal rests on verbatim, in the original language. Dates are ISO \
                yyyy-MM-dd or null; amounts are plain numbers (60000, not "60.000 kr."). \
                Confidence is 0..1 and conservative. Write title and summary in Danish.""";
    }

    private static String userMessage(String documentText) {
        return "Document text:\n\n" + documentText;
    }

    private static String visionInstruction() {
        return "This is page 1 of a scanned Danish employment document. Read it and report "
                + "individually negotiated agreement terms per the rules.";
    }

    // ---- Schema ----------------------------------------------------------------

    /**
     * OpenAI strict schema: {@code additionalProperties=false} means
     * every property must also be required — nullable fields use a
     * {@code ["type","null"]} union (the categorizer convention).
     */
    ObjectNode buildSchema(List<String> typeKeys) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("proposals");
        ObjectNode proposals = schema.putObject("properties").putObject("proposals");
        proposals.put("type", "array");
        ObjectNode item = proposals.putObject("items");
        item.put("type", "object");
        item.put("additionalProperties", false);
        item.putArray("required")
                .add("agreement_type").add("title").add("summary").add("amount").add("currency")
                .add("valid_from").add("valid_to").add("effective_date")
                .add("verbatim_quote").add("confidence");
        ObjectNode props = item.putObject("properties");
        var typeEnum = props.putObject("agreement_type").put("type", "string").putArray("enum");
        typeKeys.forEach(typeEnum::add);
        props.putObject("title").put("type", "string");
        props.putObject("summary").putArray("type").add("string").add("null");
        props.putObject("amount").putArray("type").add("number").add("null");
        props.putObject("currency").putArray("type").add("string").add("null");
        props.putObject("valid_from").putArray("type").add("string").add("null");
        props.putObject("valid_to").putArray("type").add("string").add("null");
        props.putObject("effective_date").putArray("type").add("string").add("null");
        props.putObject("verbatim_quote").put("type", "string");
        props.putObject("confidence").put("type", "number");
        return schema;
    }

    // ---- Response parsing ------------------------------------------------------

    /**
     * Parse the model response. Returns {@code null} when the response
     * carries no {@code proposals} array at all (the "{}" error
     * sentinel), an empty list for a genuine zero-proposal answer.
     * Unknown types and unparseable values degrade defensively rather
     * than fail the document.
     */
    List<Proposal> parseProposals(String response, List<String> typeKeys) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response == null ? "{}" : response);
        } catch (Exception e) {
            return null;
        }
        JsonNode array = root.get("proposals");
        if (array == null || !array.isArray()) {
            return null;
        }
        List<Proposal> proposals = new ArrayList<>();
        for (JsonNode node : array) {
            String type = textOrNull(node, "agreement_type");
            String title = textOrNull(node, "title");
            if (title == null || title.isBlank()) {
                continue;
            }
            // Schema enum should guarantee this; belt-and-braces against
            // a drifted vocabulary: unknown types fold to INDIVIDUEL.
            if (type == null || !typeKeys.contains(type)) {
                type = "INDIVIDUEL";
            }
            proposals.add(new Proposal(
                    type,
                    title.trim(),
                    textOrNull(node, "summary"),
                    amountOrNull(node.get("amount")),
                    currencyOrNull(textOrNull(node, "currency")),
                    dateOrNull(textOrNull(node, "valid_from")),
                    dateOrNull(textOrNull(node, "valid_to")),
                    dateOrNull(textOrNull(node, "effective_date")),
                    textOrNull(node, "verbatim_quote"),
                    confidence(node.get("confidence"))));
        }
        return proposals;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static BigDecimal amountOrNull(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        // A string amount should not pass the schema, but if one does,
        // reuse the recorder's Danish/ISO parsing rather than dropping it.
        return AgreementRecorder.parseAmount(value.asText());
    }

    private static String currencyOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim().toUpperCase(Locale.ROOT);
        return cleaned.length() == 3 ? cleaned : null;
    }

    private static LocalDate dateOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return AgreementRecorder.parseDate(raw);
        }
    }

    private static double confidence(JsonNode value) {
        double raw = value != null && value.isNumber() ? value.asDouble() : 0.0;
        return Math.max(0.0, Math.min(1.0, raw));
    }
}
