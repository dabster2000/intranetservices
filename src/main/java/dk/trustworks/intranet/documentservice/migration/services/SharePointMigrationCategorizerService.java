package dk.trustworks.intranet.documentservice.migration.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.documentservice.migration.services.MigrationCategorizerRules.RuleResult;
import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentStorageAdapter;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentsFeatureFlag;
import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.signing.repository.SigningCaseRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * M4 — categorizer &amp; signing linker (runbook 2a-5 / spec §9.5,
 * decisions A3/A4).
 *
 * <p><b>Categorization</b> is AI-first with a deterministic floor: pass 1
 * sends batches of ~50 {relative path, filename} pairs (never document
 * content); pass 2 sends a capped first-page text excerpt for the
 * PDF/DOCX tail pass 1 couldn't place — images never get pass 2. HIGH
 * confidence applies directly; anything less falls back to the
 * {@link MigrationCategorizerRules §9.5 rule table} <b>and</b> sets
 * {@code needs_review=1}. With the kill switch OFF, or on any OpenAI
 * error, the rule table categorizes (per document — an OpenAI failure
 * never aborts the run). Re-runnable: only rows still at their defaults
 * (category OTHER, not flagged for review) are touched — HR's manual
 * edits are never overwritten.</p>
 *
 * <p><b>Signing linkage stays 100% deterministic</b> (decision A4): for
 * each UPLOADED case, the migrated file must match the exact legacy
 * filename (parsed from {@code sharepoint_file_url}) or the exact
 * {@code {sanitizedDocName}_signed_{timestamp}.pdf} pattern — one
 * unambiguous hit or nothing. Ambiguous/unmatched cases go in the report
 * for human eyes.</p>
 */
@JBossLog
@ApplicationScoped
public class SharePointMigrationCategorizerService {

    static final int BATCH_SIZE = 50;
    static final int EXCERPT_CHAR_CAP = 1500;

    @Inject
    EmployeeDocumentsFeatureFlag featureFlag;

    @Inject
    OpenAIService openAIService;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Read-only excerpt access for AI pass 2. Deliberately bypasses
     * {@link EmployeeDocumentService#download} — that path writes a
     * DOWNLOAD audit row per call, which would flood the art. 30 trail
     * with machine reads during a migration pass.
     */
    @Inject
    EmployeeDocumentStorageAdapter storage;

    @ConfigProperty(name = "dk.trustworks.employee-documents.migration.ai-model",
            defaultValue = "gpt-5-nano")
    String aiModel;

    @Inject
    SigningCaseRepository signingCaseRepository;

    public record CategorizeSummary(
            int candidates,
            int aiHighApplied,
            int tableFallback,
            int needsReviewFlagged,
            int excerptCalls,
            boolean aiUsed,
            LinkSummary linkage,
            List<String> errors) { }

    public record LinkSummary(
            int casesExamined,
            int linked,
            int alreadyLinked,
            int unmatched,
            int ambiguous,
            List<String> unmatchedCaseKeys) { }

    /**
     * Outcome of the AI passes for one document. {@code suggestedName}
     * rides along on the same verdict — no extra call, no extra cost
     * (V476); it is null whenever the model proposed nothing usable.
     */
    record AiVerdict(EmployeeDocumentCategory category, Boolean archived, String label,
                     String confidence, String suggestedName, boolean inconclusive) {
        static AiVerdict inconclusiveVerdict() {
            return new AiVerdict(null, null, null, null, null, true);
        }
    }

    // ── The run ────────────────────────────────────────────────────────────

    public CategorizeSummary categorize() {
        List<String> docUuids = QuarkusTransaction.requiringNew().call(() ->
                EmployeeDocument.<EmployeeDocument>list(
                                "source = ?1 AND category = ?2 AND needsReview = false",
                                dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource.MIGRATION,
                                EmployeeDocumentCategory.OTHER).stream()
                        .map(EmployeeDocument::getUuid)
                        .toList());

        // Job-runner worker thread: no request context — the settings read
        // needs its own transaction like every other DB access in this run.
        boolean aiEnabled = QuarkusTransaction.requiringNew().call(featureFlag::isMigrationAiEnabled);
        List<String> errors = new ArrayList<>();
        int aiHigh = 0;
        int fallback = 0;
        int flagged = 0;
        int excerptCalls = 0;

        for (int start = 0; start < docUuids.size(); start += BATCH_SIZE) {
            List<String> batch = docUuids.subList(start, Math.min(start + BATCH_SIZE, docUuids.size()));
            record DocFacts(String uuid, String path, String filename, String contentType) { }
            List<DocFacts> facts = QuarkusTransaction.requiringNew().call(() ->
                    batch.stream()
                            .map(uuid -> (EmployeeDocument) EmployeeDocument.findById(uuid))
                            .filter(java.util.Objects::nonNull)
                            .map(d -> new DocFacts(d.getUuid(),
                                    d.getLabel() == null ? "" : d.getLabel(),
                                    d.getOriginalFilename(), d.getContentType()))
                            .toList());

            List<AiVerdict> verdicts = new ArrayList<>();
            if (aiEnabled) {
                try {
                    List<String[]> pairs = facts.stream()
                            .map(f -> new String[]{f.path(), f.filename()})
                            .toList();
                    verdicts = namePassVerdicts(pairs);
                } catch (Exception e) {
                    log.errorf(e, "AI name-pass failed for batch — falling back to the rule table");
                    errors.add("AI name-pass: " + e.getMessage());
                }
            }

            for (int i = 0; i < facts.size(); i++) {
                DocFacts doc = facts.get(i);
                AiVerdict verdict = i < verdicts.size() ? verdicts.get(i) : null;

                // Pass 2: excerpt for the inconclusive PDF/DOCX tail only —
                // images stay name-only by decision A3.
                if (aiEnabled && verdict != null && verdict.inconclusive()
                        && excerptEligible(doc.contentType())) {
                    try {
                        String excerpt = extractExcerpt(doc.uuid(), doc.contentType());
                        if (excerpt != null && !excerpt.isBlank()) {
                            excerptCalls++;
                            verdict = excerptPassVerdict(doc.path(), doc.filename(), excerpt);
                        }
                    } catch (Exception e) {
                        log.warnf("Excerpt pass failed for %s: %s", doc.uuid(), e.getMessage());
                    }
                }

                boolean applied = applyVerdict(doc.uuid(), doc.path(), doc.filename(), verdict, aiEnabled);
                if (applied) {
                    aiHigh++;
                } else {
                    fallback++;
                    if (aiEnabled) flagged++;
                }
            }
        }

        LinkSummary linkage = linkSigningCases(errors);
        log.infof("Categorize done: %d candidates, %d AI-HIGH, %d table-fallback (%d flagged), %d excerpt calls; "
                        + "linkage %d/%d linked",
                docUuids.size(), aiHigh, fallback, flagged, excerptCalls,
                linkage.linked(), linkage.casesExamined());
        return new CategorizeSummary(docUuids.size(), aiHigh, fallback, flagged, excerptCalls,
                aiEnabled, linkage, errors);
    }

    /**
     * HIGH ⇒ apply the AI's category/archived/label; anything else ⇒ the
     * §9.5 rule table, plus {@code needs_review=1} when the AI was asked
     * and couldn't place it confidently (with AI off, the table result is
     * the pre-AI behavior and carries no review flag).
     *
     * @return true when the AI verdict was applied (AI-HIGH), false on fallback
     */
    boolean applyVerdict(String docUuid, String path, String filename, AiVerdict verdict, boolean aiEnabled) {
        boolean useAi = verdict != null && !verdict.inconclusive()
                && "HIGH".equals(verdict.confidence()) && verdict.category() != null;
        RuleResult rule = MigrationCategorizerRules.categorize(path, filename);

        QuarkusTransaction.requiringNew().run(() -> {
            EmployeeDocument doc = EmployeeDocument.findById(docUuid);
            if (doc == null) return;
            // Idempotency guard: someone (HR) may have edited since we listed.
            if (doc.getCategory() != EmployeeDocumentCategory.OTHER || doc.isNeedsReview()) return;

            if (useAi) {
                doc.setCategory(verdict.category());
                if (verdict.archived() != null) doc.setArchived(verdict.archived());
                if (verdict.label() != null && !verdict.label().isBlank()) {
                    doc.setLabel(verdict.label().length() > 255
                            ? verdict.label().substring(0, 255) : verdict.label());
                }
                // Standardized name off the same verdict (V476) — already
                // normalized in parseVerdict. If the model proposed
                // nothing usable, the table builder names it instead;
                // an existing name (HR edit, earlier rename pass) is
                // never overwritten.
                applyDisplayName(doc, verdict.suggestedName() != null
                        ? verdict.suggestedName()
                        : MigrationCategorizerRules.buildDisplayName(
                                verdict.category(), doc.getOriginalFilename(), path, null));
            } else {
                doc.setCategory(rule.category());
                if (rule.archived()) doc.setArchived(true);
                if (rule.label() != null && (doc.getLabel() == null || doc.getLabel().isBlank())) {
                    doc.setLabel(rule.label());
                }
                applyDisplayName(doc, MigrationCategorizerRules.buildDisplayName(
                        rule.category(), doc.getOriginalFilename(), path, null));
                if (aiEnabled) doc.setNeedsReview(true);
            }
            doc.persist();
        });
        return useAi;
    }

    /**
     * Set the standardized display name only when the document does not
     * already have one — HR's manual edits and an earlier rename pass
     * are both sticky (V476). {@code original_filename} is never
     * touched: the signing linkage matches on it (decision A4).
     */
    static void applyDisplayName(EmployeeDocument doc, String displayName) {
        if (displayName == null || displayName.isBlank()) return;
        if (doc.getDisplayName() != null && !doc.getDisplayName().isBlank()) return;
        doc.setDisplayName(displayName);
    }

    static boolean excerptEligible(String contentType) {
        if (contentType == null) return false;
        return contentType.equals("application/pdf")
                || contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    // ── AI passes ──────────────────────────────────────────────────────────

    /** Package-private: the rename pass (M6) reuses this exact call. */
    List<AiVerdict> namePassVerdicts(List<String[]> pathFilenamePairs) {
        StringBuilder userMsg = new StringBuilder();
        userMsg.append("DOCUMENTS (index | folder path | filename):\n");
        for (int i = 0; i < pathFilenamePairs.size(); i++) {
            userMsg.append(i).append(" | ")
                    .append(pathFilenamePairs.get(i)[0]).append(" | ")
                    .append(pathFilenamePairs.get(i)[1]).append('\n');
        }
        // gpt-5-family models spend max_output_tokens on hidden reasoning
        // before emitting JSON — 8192 proved too tight for a 44-entry batch
        // in the matcher (empty output). The cap costs nothing unless used.
        String response = openAIService.askQuestionWithSchema(
                categorizerSystemPrompt(), userMsg.toString(),
                batchSchema(), "employee_document_categories",
                null, aiModel, 32768, false);
        return parseBatchVerdicts(response, pathFilenamePairs.stream().map(pair -> pair[1]).toList());
    }

    private AiVerdict excerptPassVerdict(String path, String filename, String excerpt) {
        String userMsg = "Folder path: " + path + "\nFilename: " + filename
                + "\nFirst-page text excerpt:\n" + excerpt;
        String response = openAIService.askQuestionWithSchema(
                categorizerSystemPrompt(), userMsg,
                singleSchema(), "employee_document_category",
                null, aiModel, 8192, false);
        try {
            return parseVerdict(objectMapper.readTree(response), filename);
        } catch (Exception e) {
            log.warnf("Excerpt-pass response unparseable — inconclusive");
            return AiVerdict.inconclusiveVerdict();
        }
    }

    /**
     * Hard server-side validation (house rule): unknown category ⇒
     * inconclusive.
     *
     * @param originalFilenames the batch's stored filenames, index-aligned
     *                          with the request — the extension source for
     *                          each suggested name
     */
    List<AiVerdict> parseBatchVerdicts(String responseJson, List<String> originalFilenames) {
        int expected = originalFilenames.size();
        List<AiVerdict> verdicts = new ArrayList<>();
        for (int i = 0; i < expected; i++) verdicts.add(AiVerdict.inconclusiveVerdict());
        try {
            JsonNode results = objectMapper.readTree(responseJson).path("results");
            if (!results.isArray()) {
                // "{}" here means the model emitted nothing (token budget
                // exhausted by reasoning) — the whole batch degrades to the
                // rule table + needs_review, which the run summary shows.
                log.warnf("AI categorizer response had no results array — whole batch falls back to the rule table");
                return verdicts;
            }
            for (JsonNode node : results) {
                int index = node.path("index").asInt(-1);
                if (index < 0 || index >= expected) continue;
                verdicts.set(index, parseVerdict(node, originalFilenames.get(index)));
            }
        } catch (Exception e) {
            log.warnf("AI categorizer response unparseable — all inconclusive");
        }
        return verdicts;
    }

    /**
     * @param originalFilename the document's stored filename — the only
     *                         source of the extension for a suggested name
     */
    AiVerdict parseVerdict(JsonNode node, String originalFilename) {
        String rawCategory = node.path("category").asText("");
        if (rawCategory.isBlank() || rawCategory.equalsIgnoreCase("INCONCLUSIVE")) {
            return AiVerdict.inconclusiveVerdict();
        }
        EmployeeDocumentCategory category;
        try {
            category = EmployeeDocumentCategory.valueOf(rawCategory.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Invalid category name from the model ⇒ inconclusive ⇒ rule table.
            return AiVerdict.inconclusiveVerdict();
        }
        String confidence = node.path("confidence").asText("LOW").trim().toUpperCase(Locale.ROOT);
        if (!confidence.equals("HIGH") && !confidence.equals("MEDIUM") && !confidence.equals("LOW")) {
            confidence = "LOW";
        }
        JsonNode labelNode = node.path("label");
        String label = labelNode.isNull() ? null : labelNode.asText(null);

        // The suggested name is untrusted model output that ends up in a
        // Content-Disposition header and on the user's disk: sanitize,
        // force the ORIGINAL file's extension (a wrong one makes the
        // download unopenable), truncate. Nothing usable left ⇒ null,
        // which means "no name proposed", not an error.
        JsonNode nameNode = node.path("suggested_name");
        String suggestedName = nameNode.isNull() ? null
                : EmployeeDocumentService.normalizeDisplayName(nameNode.asText(null), originalFilename);

        return new AiVerdict(category, node.path("archived").asBoolean(false), label,
                confidence, suggestedName, false);
    }

    private String categorizerSystemPrompt() {
        return """
                You categorize a Danish consultancy's employee HR documents from folder paths and
                filenames (Danish and English, often misspelled or abbreviated). Categories:
                CONTRACT (ansættelseskontrakt, aftale, contract), ADDENDUM (tillæg, klausul, addendum),
                SALARY (lønregulering, lønseddel, salary), IDENTITY (pas, sundhedskort, kørekort, ID,
                straffeattest), DECLARATION (tro- og love-erklæring, loyalitetsprogram, din del af
                Trustworks), VACATION (ferie), SICKNESS (sygdom, mulighedserklæring),
                TERMINATION (opsigelse, fratrædelse), OTHER (everything else, incl. emails).
                A path containing 'Arkiv' means the document is archived (archived=true).
                Set label only when a short human-readable note adds value (e.g. an email subject).
                Use category INCONCLUSIVE when path+filename genuinely aren't enough.
                confidence is HIGH only when the category is unmistakable.

                Also return suggested_name: a standardized, human-readable filename in the form
                  {YYYY-MM-DD}_{CATEGORY}_{subject}.{ext}
                Rules for suggested_name:
                - The date segment is OPTIONAL and must be OMITTED ENTIRELY unless a real
                  document date is genuinely present in the filename or the excerpt. NEVER
                  invent, guess or infer a date. No date ⇒ {CATEGORY}_{subject}.{ext}.
                  A year on its own renders as the year alone (2021_SALARY_loenregulering.pdf).
                - {CATEGORY} is exactly the category you chose above.
                - {subject} is short (1-4 words), lowercase, hyphen-separated, and says what
                  the document IS (loenregulering, ansaettelseskontrakt, sundhedskort).
                  Danish words are preserved; drop noise like "final", "ny", "kopi", "(2)".
                - {ext} keeps the original file's extension.
                Return null for suggested_name when the input says too little to name it.""";
    }

    private ObjectNode batchSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("results");
        ObjectNode results = schema.putObject("properties").putObject("results");
        results.put("type", "array");
        ObjectNode item = results.putObject("items");
        fillVerdictSchema(item, true);
        return schema;
    }

    private ObjectNode singleSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        fillVerdictSchema(schema, false);
        return schema;
    }

    private void fillVerdictSchema(ObjectNode target, boolean withIndex) {
        target.put("type", "object");
        target.put("additionalProperties", false);
        var required = target.putArray("required");
        if (withIndex) required.add("index");
        // OpenAI strict schema: additionalProperties=false means EVERY
        // property must also be listed in required (nullable ones use a
        // ["string","null"] type instead of being optional).
        required.add("category").add("archived").add("label").add("confidence").add("suggested_name");
        ObjectNode props = target.putObject("properties");
        if (withIndex) props.putObject("index").put("type", "integer");
        var categoryEnum = props.putObject("category").put("type", "string").putArray("enum");
        for (EmployeeDocumentCategory category : EmployeeDocumentCategory.values()) {
            categoryEnum.add(category.name());
        }
        categoryEnum.add("INCONCLUSIVE");
        props.putObject("archived").put("type", "boolean");
        props.putObject("label").putArray("type").add("string").add("null");
        props.putObject("confidence").put("type", "string")
                .putArray("enum").add("HIGH").add("MEDIUM").add("LOW");
        props.putObject("suggested_name").putArray("type").add("string").add("null");
    }

    // ── Excerpt extraction (pass 2) ────────────────────────────────────────

    String extractExcerpt(String docUuid, String contentType) {
        String s3Key = QuarkusTransaction.requiringNew().call(() -> {
            EmployeeDocument doc = EmployeeDocument.findById(docUuid);
            return doc == null ? null : doc.getS3Key();
        });
        if (s3Key == null) return null;
        byte[] bytes = storage.get(s3Key).bytes();
        String text = contentType.equals("application/pdf")
                ? pdfFirstPageText(bytes)
                : docxText(bytes);
        if (text == null) return null;
        text = text.strip();
        return text.length() > EXCERPT_CHAR_CAP ? text.substring(0, EXCERPT_CHAR_CAP) : text;
    }

    static String pdfFirstPageText(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            return stripper.getText(document);
        } catch (Exception e) {
            return null;
        }
    }

    /** Cheap DOCX text: unzip word/document.xml and strip the XML tags. */
    static String docxText(byte[] bytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("word/document.xml")) {
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    return xml.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    // ── Signing linkage (decision A4 — deterministic only) ─────────────────

    LinkSummary linkSigningCases(List<String> errors) {
        List<String> caseKeys = QuarkusTransaction.requiringNew().call(() ->
                signingCaseRepository.findBySharepointUploadStatus("UPLOADED").stream()
                        .map(SigningCase::getCaseKey)
                        .toList());

        int linked = 0;
        int alreadyLinked = 0;
        int unmatched = 0;
        int ambiguous = 0;
        List<String> unmatchedKeys = new ArrayList<>();

        for (String caseKey : caseKeys) {
            try {
                LinkOutcome outcome = QuarkusTransaction.requiringNew().call(() -> linkOneCase(caseKey));
                switch (outcome) {
                    case LINKED -> linked++;
                    case ALREADY_LINKED -> alreadyLinked++;
                    case AMBIGUOUS -> {
                        ambiguous++;
                        unmatchedKeys.add(caseKey + " (ambiguous)");
                    }
                    case UNMATCHED -> {
                        unmatched++;
                        unmatchedKeys.add(caseKey);
                    }
                }
            } catch (Exception e) {
                log.errorf(e, "Signing linkage failed for case %s", caseKey);
                errors.add("linkage " + caseKey + ": " + e.getMessage());
                unmatched++;
                unmatchedKeys.add(caseKey + " (error)");
            }
        }
        return new LinkSummary(caseKeys.size(), linked, alreadyLinked, unmatched, ambiguous, unmatchedKeys);
    }

    enum LinkOutcome { LINKED, ALREADY_LINKED, AMBIGUOUS, UNMATCHED }

    private LinkOutcome linkOneCase(String caseKey) {
        SigningCase signingCase = signingCaseRepository.findByCaseKey(caseKey).orElse(null);
        if (signingCase == null) return LinkOutcome.UNMATCHED;

        if (!EmployeeDocument.findBySigningCase(caseKey).isEmpty()) {
            markCaseArchived(signingCase);
            return LinkOutcome.ALREADY_LINKED;
        }

        String urlFilename = filenameFromUrl(signingCase.getSharepointFileUrl());
        Pattern pattern = signedPattern(signingCase.getDocumentName());

        List<EmployeeDocument> candidates = EmployeeDocument.<EmployeeDocument>list(
                        "userUuid = ?1 AND source = ?2 AND signingCaseKey IS NULL",
                        signingCase.getUserUuid(),
                        dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource.MIGRATION)
                .stream()
                .filter(doc -> matchesExactly(doc.getOriginalFilename(), urlFilename, pattern))
                .toList();

        if (candidates.isEmpty()) return LinkOutcome.UNMATCHED;
        if (candidates.size() > 1) return LinkOutcome.AMBIGUOUS;

        EmployeeDocument doc = candidates.get(0);
        doc.setSigningCaseKey(caseKey);
        doc.setDocumentIndex(0);
        doc.persist();
        markCaseArchived(signingCase);
        return LinkOutcome.LINKED;
    }

    private void markCaseArchived(SigningCase signingCase) {
        if (!"ARCHIVED".equals(signingCase.getArchiveStatus())) {
            signingCase.setArchiveStatus("ARCHIVED");
            signingCase.setArchiveError(null);
            signingCaseRepository.persist(signingCase);
        }
    }

    /**
     * Exact matching only (A4): the migrated file's stored filename must
     * equal the legacy uploaded filename, or match the exact
     * {@code {sanitizedDocName}_signed_{timestamp}.pdf} pattern.
     */
    static boolean matchesExactly(String storedFilename, String urlFilename, Pattern signedPattern) {
        if (storedFilename == null) return false;
        if (urlFilename != null && storedFilename.equalsIgnoreCase(urlFilename)) {
            return storedFilename.toLowerCase(Locale.ROOT).contains("_signed_");
        }
        return signedPattern != null && signedPattern.matcher(storedFilename).matches();
    }

    /** Last path segment of the SharePoint URL, decoded + store-sanitized. */
    static String filenameFromUrl(String sharePointFileUrl) {
        if (sharePointFileUrl == null || sharePointFileUrl.isBlank()) return null;
        String path = sharePointFileUrl;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int slash = path.lastIndexOf('/');
        if (slash >= 0) path = path.substring(slash + 1);
        try {
            path = URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // keep the raw segment
        }
        String sanitized = EmployeeDocumentService.sanitizeFilename(path);
        return sanitized.isBlank() ? null : sanitized;
    }

    /** {@code ^{sanitizedDocName}_signed_<anything>.pdf$}, case-insensitive. */
    static Pattern signedPattern(String documentName) {
        if (documentName == null || documentName.isBlank()) return null;
        String base = documentName.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? documentName.substring(0, documentName.length() - 4)
                : documentName;
        String sanitized = EmployeeDocumentService.sanitizeFilename(base);
        if (sanitized.isBlank()) return null;
        return Pattern.compile(Pattern.quote(sanitized) + "_signed_.+\\.pdf",
                Pattern.CASE_INSENSITIVE);
    }
}
