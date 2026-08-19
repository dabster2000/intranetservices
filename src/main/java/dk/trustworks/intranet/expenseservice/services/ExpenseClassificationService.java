package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.expenseservice.dto.ExpenseClassificationDTOs;
import dk.trustworks.intranet.expenseservice.model.*;
import dk.trustworks.intranet.model.Company;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@JBossLog
@ApplicationScoped
public class ExpenseClassificationService {

    // Lowered from 0.70 → 0.40 because the previous cutoff silently discarded usable
    // suggestions from cheaper vision models and left the UI with zero AI hints. Dropped
    // answers are now logged so we can tune this number with real evidence.
    private static final double AI_ACCEPT_THRESHOLD = 0.40;
    private static final String UNREADABLE_WARNING =
            "We couldn't read the receipt. Try a sharper, well-lit photo showing the date, merchant and total.";

    // The currency the accounting side expects. Everything downstream stores the amount in a
    // *_dkk column, but the model can only read what is printed on the receipt — it has no
    // exchange rate — so a non-DKK receipt must be flagged to the employee instead of being
    // silently treated as kroner. Production comparison of 62 rows showed implied FX ratios
    // of ~7.5 (EUR) and ~6.4 (USD): face value passed straight through as if it were DKK.
    private static final String ACCOUNTING_CURRENCY = "DKK";

    /**
     * What the model may legitimately return in {@code currency}, enumerated in the schema so it
     * cannot answer "kr." in the first place. Null stays valid — it is the escape hatch for a
     * currency that is genuinely unreadable or not on this list, and is deliberately preferable
     * to the model picking the nearest listed code. Danish consultancy travel: the Nordics,
     * the euro zone, the UK/US/Swiss, plus the CEE countries we actually invoice from.
     */
    private static final List<String> ALLOWED_RECEIPT_CURRENCIES =
            List.of("DKK", "EUR", "USD", "SEK", "NOK", "GBP", "CHF", "ISK", "PLN", "CZK");

    /**
     * Everything a Danish receipt calls a krone, keyed by its punctuation-stripped upper-case
     * form (see {@link #normalizeCurrency}). Without this the wizard shouted "not DKK" at the
     * employee on an ordinary domestic purchase — the loudest warning we have, on the most
     * common case there is — because a vision model reading "kr." off a Netto receipt has no
     * reason to type the ISO code we happen to want.
     */
    private static final Map<String, String> CURRENCY_SYNONYMS = Map.ofEntries(
            Map.entry("KR", ACCOUNTING_CURRENCY),
            Map.entry("KRS", ACCOUNTING_CURRENCY),
            Map.entry("KRONE", ACCOUNTING_CURRENCY),
            Map.entry("KRONER", ACCOUNTING_CURRENCY),
            Map.entry("DKR", ACCOUNTING_CURRENCY),
            Map.entry("DK", ACCOUNTING_CURRENCY),
            Map.entry("DK KR", ACCOUNTING_CURRENCY),
            Map.entry("DKK KR", ACCOUNTING_CURRENCY),
            Map.entry("DANSK KRONE", ACCOUNTING_CURRENCY),
            Map.entry("DANSKE KRONER", ACCOUNTING_CURRENCY),
            Map.entry("DANISH KRONE", ACCOUNTING_CURRENCY),
            Map.entry("DANISH KRONER", ACCOUNTING_CURRENCY)
    );
    // documentType values the model may return. The schema keeps the field a nullable string
    // (persisted rows already carry "pdf" and "receipt" written by this service), but these two
    // are the only values the AI is asked for: a refund is a CREDIT_NOTE with a positive amount,
    // never a receipt with a negative one. Five production rows came back at exactly -1.000 the
    // expected ratio because the sign was carrying that distinction instead.
    private static final String DOCUMENT_TYPE_RECEIPT = "receipt";
    private static final String DOCUMENT_TYPE_CREDIT_NOTE = "credit_note";
    private static final String CREDIT_NOTE_WARNING =
            "This looks like a credit note or refund, not a purchase. Check the amount and that you are expensing the right document.";

    // The AI must never auto-select the "Other / not sure" catch-all. When the model cannot
    // confidently place the receipt in a real category we want the employee to choose, not have
    // the expense silently routed to the 9998 Finance-review fallback. Employees may still pick
    // "Other / not sure" manually in the wizard — this only suppresses the AI's *proposal* of it.
    private static final String ROOT_NODE_KEY = "root";
    private static final Set<String> AI_SUPPRESSED_ROOT_ANSWERS = Set.of("other_unsure");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    @Inject OpenAIService openAIService;
    @Inject UserService userService;

    /**
     * Vision model for the receipt read. Deliberately NOT the global {@code openai.vision-model}:
     * that property is still shared with the recruitment CV/document intake path
     * ({@code AiIntakeGenerationService#callModel}) and is pinned to gpt-4o-mini for reasons that
     * have nothing to do with receipts. (The recruitment calendar-image path no longer shares it —
     * that one has its own {@code dk.trustworks.recruitment.ai.image-model}.) Reading a crumpled,
     * angled or low-light receipt is a real visual task, so this path gets its own knob.
     * Override per env with {@code DK_TRUSTWORKS_EXPENSE_AI_RECEIPT_MODEL}.
     */
    @ConfigProperty(name = "dk.trustworks.expense.ai.receipt-model", defaultValue = "gpt-4o-mini")
    String receiptModel;

    /**
     * {@code reasoning.effort} for the receipt read, or empty to omit the reasoning node.
     * <p>
     * Declared {@code Optional<String>} deliberately: EMPTY is the default because
     * {@link #receiptModel} defaults to gpt-4o-mini, a non-reasoning model that rejects the
     * node with HTTP 400. A plain {@code String} cannot express that — SmallRye converts an
     * empty-but-present value to null, and because the raw value is non-null the
     * {@code defaultValue} never rescues it, so the whole application fails to boot with
     * SRCFG00040. Same trap as {@code cvtool.username}/{@code cvtool.password}.
     */
    @ConfigProperty(name = "dk.trustworks.expense.ai.receipt-reasoning-effort")
    Optional<String> receiptReasoningEffort;

    /**
     * {@code max_output_tokens} for the receipt call. Vision + strict JSON schema can produce
     * more tokens than the default 4096 budget once the model includes a rich line-item array.
     * <p>
     * WARNING: this, {@link #receiptModel} and {@link #receiptReasoningEffort} move TOGETHER.
     * A reasoning-class model spends the same budget on hidden reasoning FIRST, so raising the
     * model or the effort without raising this reproduces the empty-structured-output failure
     * documented on {@code openai.vision-model}: a 2xx response carrying no output text.
     */
    @ConfigProperty(name = "dk.trustworks.expense.ai.receipt-max-output-tokens", defaultValue = "8192")
    int receiptMaxOutputTokens;

    /**
     * {@code input_image} detail — "high" stops the Responses API downsampling a photo before
     * the model can read the amount, VAT line and date on it. Empty omits the field (API
     * default). {@code Optional<String>} for the same SRCFG00040 reason as
     * {@link #receiptReasoningEffort}.
     */
    @ConfigProperty(name = "dk.trustworks.expense.ai.receipt-image-detail")
    Optional<String> receiptImageDetail;

    /** The effort to send, or null to omit the reasoning node entirely. */
    private String receiptReasoningEffortOrNull() {
        return receiptReasoningEffort.filter(e -> !e.isBlank()).orElse(null);
    }

    /** The image detail to send, or null to omit the field and take the API default. */
    private String receiptImageDetailOrNull() {
        return receiptImageDetail.filter(d -> !d.isBlank()).orElse(null);
    }

    public String activeTreeVersion() {
        ExpenseClassificationTree tree = ExpenseClassificationTree.find("active = ?1", true).firstResult();
        if (tree == null) throw new NotFoundException("No active expense classification tree");
        return tree.treeVersion;
    }

    public ExpenseClassificationDTOs.TreeResponse getActiveTree() {
        String version = activeTreeVersion();
        return new ExpenseClassificationDTOs.TreeResponse(version, treeNodes(version, null));
    }

    @Transactional
    public ExpenseClassificationDTOs.AnalyzeResponse analyze(String useruuid, ExpenseClassificationDTOs.AnalyzeRequest request) {
        if (request == null || request.receiptBase64() == null || request.receiptBase64().isBlank()) {
            throw new BadRequestException("receiptBase64 is required");
        }
        String mime = request.mimeType() == null || request.mimeType().isBlank() ? "image/jpeg" : request.mimeType();
        if ("application/pdf".equalsIgnoreCase(mime)) {
            String version = activeTreeVersion();
            log.infof("[Expense-Classify] PDF receipt; skipping AI extraction. useruuid=%s treeVersion=%s",
                    useruuid, version);
            ExpenseClassificationDTOs.AnalyzeResponse response = new ExpenseClassificationDTOs.AnalyzeResponse(
                    UUID.randomUUID().toString(),
                    version,
                    emptyFacts("pdf"),
                    List.of(),
                    List.of("PDF receipts start from manual classification in v1."),
                    "PDF receipt was not analyzed before submission."
            );
            persistAnalysis(useruuid, response);
            return response;
        }

        String version = activeTreeVersion();
        String fallback = fallbackAnalysisJson();
        String reasoningEffort = receiptReasoningEffortOrNull();
        String imageDetail = receiptImageDetailOrNull();
        int base64Len = request.receiptBase64().length();
        log.infof("[Expense-Classify] Start analyze. useruuid=%s treeVersion=%s mime=%s base64Len=%d model=%s effort=%s detail=%s maxOutputTokens=%d",
                useruuid, version, mime, base64Len, receiptModel,
                reasoningEffort == null ? "none" : reasoningEffort,
                imageDetail == null ? "default" : imageDetail,
                receiptMaxOutputTokens);

        long startNanos = System.nanoTime();
        // store=false: the request body is an employee's receipt image. The flag buys two things
        // and no more — it keeps the request out of OpenAI-side retention, and it makes
        // askWithSchemaAndImagesInternal suppress the upstream error-body/refusal echo. It does
        // NOT make this path log-free: whatever we log here is our own responsibility, which is
        // why the model's verbatim output below only goes out at DEBUG.
        String raw = openAIService.askWithSchemaAndImage(
                analysisSystemPrompt(version),
                "Read this employee receipt. Return only facts visible on the receipt and proposed question-tree answers. Do not choose final account numbers.",
                request.receiptBase64(),
                mime,
                analysisSchema(),
                "expense_receipt_analysis",
                fallback,
                receiptModel,
                receiptMaxOutputTokens,
                false,
                reasoningEffort,
                imageDetail
        );
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        log.infof("[Expense-Classify] OpenAI returned in %d ms. rawLen=%d",
                elapsedMs, raw == null ? 0 : raw.length());
        if (log.isDebugEnabled()) {
            // By construction this preview is the model's verbatim read of an employee's receipt:
            // merchant, amount, date and the evidence quotes it lifted off the image. That is
            // personal spending data, so it stays behind DEBUG — the length above is what INFO
            // needs to tell an empty response from a real one.
            String rawPreview = raw == null ? "null"
                    : (raw.length() > 500 ? raw.substring(0, 500) + "..." : raw);
            log.debugf("[Expense-Classify] Raw model output preview=%s", rawPreview);
        }

        ExpenseClassificationDTOs.AnalyzeResponse parsed = parseAnalysis(version, raw);
        log.infof("[Expense-Classify] Parsed. merchant=%s date=%s amount=%s answersKept=%d warnings=%d",
                parsed.receiptFacts() == null ? "null" : parsed.receiptFacts().merchantName(),
                parsed.receiptFacts() == null ? "null" : parsed.receiptFacts().date(),
                parsed.receiptFacts() == null ? "null" : String.valueOf(parsed.receiptFacts().amount()),
                parsed.proposedAnswers().size(),
                parsed.warnings().size());
        persistAnalysis(useruuid, parsed);
        return parsed;
    }

    public ExpenseClassificationDTOs.ResolveResponse resolve(String useruuid, ExpenseClassificationDTOs.ResolveRequest request) {
        String version = request != null && request.treeVersion() != null && !request.treeVersion().isBlank()
                ? request.treeVersion()
                : activeTreeVersion();
        List<ExpenseClassificationDTOs.Answer> accepted = sanitizeAnswers(version, request != null ? request.answers() : List.of());
        Map<String, String> answerMap = accepted.stream()
                .collect(Collectors.toMap(
                        ExpenseClassificationDTOs.Answer::nodeKey,
                        ExpenseClassificationDTOs.Answer::answerKey,
                        (previous, next) -> next,
                        LinkedHashMap::new
                ));

        List<ExpenseClassificationDTOs.NodeDTO> visibleNodes = visibleTreeNodes(version, answerMap);
        List<ExpenseClassificationDTOs.NodeDTO> unanswered = visibleNodes.stream()
                .filter(ExpenseClassificationDTOs.NodeDTO::required)
                .filter(node -> !answerMap.containsKey(node.nodeKey()))
                .toList();
        if (!unanswered.isEmpty()) {
            return new ExpenseClassificationDTOs.ResolveResponse(
                    "NEEDS_ANSWERS",
                    version,
                    unanswered,
                    null,
                    accepted
            );
        }

        ExpenseClassificationResult result = findMatchingResult(version, answerMap);
        if (result == null) {
            throw new BadRequestException("No classification result matches the provided answers");
        }

        ExpenseAccountMapping mapping = resolveMapping(result.accountKey, currentCompanyUuid(useruuid));
        boolean mappingFallback = mapping == null;
        if (mapping == null) mapping = fallbackMapping();

        ExpenseClassificationDTOs.ResolvedResult resolved = new ExpenseClassificationDTOs.ResolvedResult(
                result.resultKey,
                result.employeeLabel,
                result.employeeSummary,
                mapping.accountKey,
                mapping.accountNumber,
                mapping.accountName,
                result.taxTreatment,
                result.requiresFinanceReview || mappingFallback || "finance_review_fallback".equals(mapping.accountKey)
        );

        return new ExpenseClassificationDTOs.ResolveResponse(
                "RESOLVED",
                version,
                List.of(),
                resolved,
                accepted
        );
    }

    public ExpenseClassificationDTOs.ResolveResponse resolveSubmission(String useruuid, ExpenseClassificationDTOs.Submission submission) {
        if (submission == null) throw new BadRequestException("classification is required");
        ExpenseClassificationDTOs.ResolveRequest request = new ExpenseClassificationDTOs.ResolveRequest(
                submission.treeVersion(),
                submission.analysisId(),
                submission.aiUsed(),
                submission.aiIgnored(),
                submission.answers(),
                submission.ignoredAiAnswers()
        );
        ExpenseClassificationDTOs.ResolveResponse response = resolve(useruuid, request);
        if (response.result() == null) throw new BadRequestException("classification is incomplete");
        return response;
    }

    public void applyResolvedAccount(Expense expense) {
        if (expense.getClassification() == null) return;
        ExpenseClassificationDTOs.ResolveResponse resolved = resolveSubmission(expense.getUseruuid(), expense.getClassification());
        expense.setAccount(resolved.result().accountNumber());
        expense.setAccountname(resolved.result().accountName());
    }

    @Transactional
    public void persistSubmittedClassification(Expense expense) {
        ExpenseClassificationDTOs.Submission submission = expense.getClassification();
        if (submission == null) return;
        ExpenseClassificationDTOs.ResolveResponse resolved = resolveSubmission(expense.getUseruuid(), submission);

        ExpenseClassification row = ExpenseClassification.find("expenseUuid", expense.getUuid()).firstResult();
        if (row == null) {
            row = new ExpenseClassification();
            row.uuid = UUID.randomUUID().toString();
            row.expenseUuid = expense.getUuid();
            row.createdAt = LocalDateTime.now();
        }
        row.useruuid = expense.getUseruuid();
        row.analysisId = submission.analysisId();
        row.treeVersion = resolved.treeVersion();
        row.aiUsed = Boolean.TRUE.equals(submission.aiUsed());
        row.aiIgnored = Boolean.TRUE.equals(submission.aiIgnored());
        row.decisionResultKey = resolved.result().resultKey();
        row.accountKey = resolved.result().accountKey();
        row.accountNumber = resolved.result().accountNumber();
        row.accountName = resolved.result().accountName();
        row.requiresFinanceReview = resolved.result().requiresFinanceReview();
        row.answersJson = toJson(resolved.acceptedAnswers(), "[]");
        row.ignoredAiAnswersJson = toJson(submission.ignoredAiAnswers() == null ? List.of() : submission.ignoredAiAnswers(), "[]");
        row.persist();
    }

    public boolean requiresFinanceReview(String expenseUuid) {
        ExpenseClassification row = ExpenseClassification.find("expenseUuid", expenseUuid).firstResult();
        return row != null && row.requiresFinanceReview;
    }

    List<ExpenseClassificationDTOs.Answer> sanitizeAnswers(String version, List<ExpenseClassificationDTOs.Answer> answers) {
        if (answers == null || answers.isEmpty()) return List.of();
        Map<String, ExpenseClassificationNode> nodes = ExpenseClassificationNode.<ExpenseClassificationNode>list("treeVersion = ?1", version)
                .stream()
                .collect(Collectors.toMap(node -> node.nodeKey, Function.identity()));
        Set<String> validOptionKeys = ExpenseClassificationOption.<ExpenseClassificationOption>list("treeVersion = ?1", version)
                .stream()
                .map(option -> option.nodeKey + "\u0000" + option.answerKey)
                .collect(Collectors.toSet());

        LinkedHashMap<String, ExpenseClassificationDTOs.Answer> deduped = new LinkedHashMap<>();
        for (ExpenseClassificationDTOs.Answer answer : answers) {
            if (answer == null || answer.nodeKey() == null || answer.answerKey() == null) continue;
            if (!nodes.containsKey(answer.nodeKey())) continue;
            if (!validOptionKeys.contains(answer.nodeKey() + "\u0000" + answer.answerKey())) continue;
            deduped.put(answer.nodeKey(), answer);
        }
        return new ArrayList<>(deduped.values());
    }

    private List<ExpenseClassificationDTOs.NodeDTO> visibleTreeNodes(String version, Map<String, String> answers) {
        return treeNodes(version, answers);
    }

    private List<ExpenseClassificationDTOs.NodeDTO> treeNodes(String version, Map<String, String> answersOrNull) {
        List<ExpenseClassificationNode> nodes = ExpenseClassificationNode.list(
                "treeVersion = ?1",
                Sort.by("sortOrder"),
                version
        );
        Map<String, List<ExpenseClassificationOption>> options = ExpenseClassificationOption.<ExpenseClassificationOption>list(
                        "treeVersion = ?1",
                        Sort.by("sortOrder"),
                        version
                )
                .stream()
                .collect(Collectors.groupingBy(option -> option.nodeKey, LinkedHashMap::new, Collectors.toList()));

        return nodes.stream()
                .filter(node -> answersOrNull == null || visible(node.visibleWhenJson, answersOrNull))
                .map(node -> new ExpenseClassificationDTOs.NodeDTO(
                        node.nodeKey,
                        node.prompt,
                        node.answerSourcePolicy,
                        node.required,
                        options.getOrDefault(node.nodeKey, List.of()).stream()
                                .map(option -> new ExpenseClassificationDTOs.OptionDTO(option.answerKey, option.label))
                                .toList()
                ))
                .toList();
    }

    private boolean visible(String visibleWhenJson, Map<String, String> answers) {
        Map<String, String> conditions = parseStringMap(visibleWhenJson);
        for (Map.Entry<String, String> entry : conditions.entrySet()) {
            if (!entry.getValue().equals(answers.get(entry.getKey()))) return false;
        }
        return true;
    }

    private ExpenseClassificationResult findMatchingResult(String version, Map<String, String> answers) {
        return ExpenseClassificationResult.<ExpenseClassificationResult>list(
                        "treeVersion = ?1",
                        Sort.by("sortOrder"),
                        version
                )
                .stream()
                .filter(result -> visible(result.conditionsJson, answers))
                .findFirst()
                .orElse(null);
    }

    private ExpenseAccountMapping resolveMapping(String accountKey, String companyuuid) {
        if (companyuuid != null && !companyuuid.isBlank()) {
            ExpenseAccountMapping exact = ExpenseAccountMapping.find(
                    "accountKey = ?1 and companyuuid = ?2 and active = true",
                    accountKey,
                    companyuuid
            ).firstResult();
            if (exact != null) return exact;
        }
        return ExpenseAccountMapping.find(
                "accountKey = ?1 and companyuuid is null and active = true",
                accountKey
        ).firstResult();
    }

    private ExpenseAccountMapping fallbackMapping() {
        ExpenseAccountMapping fallback = resolveMapping("finance_review_fallback", null);
        if (fallback != null) return fallback;
        ExpenseAccountMapping synthetic = new ExpenseAccountMapping();
        synthetic.accountKey = "finance_review_fallback";
        synthetic.accountNumber = "9998";
        synthetic.accountName = "Finance review";
        synthetic.active = true;
        return synthetic;
    }

    private String currentCompanyUuid(String useruuid) {
        try {
            User user = userService.findById(useruuid, false);
            if (user == null) return null;
            Company company = userService.getUserStatus(user, LocalDate.now()).getCompany();
            return company != null ? company.getUuid() : null;
        } catch (Exception ex) {
            log.warnf(ex, "Could not resolve company for expense classification useruuid=%s", useruuid);
            return null;
        }
    }

    private void persistAnalysis(String useruuid, ExpenseClassificationDTOs.AnalyzeResponse response) {
        ExpenseReceiptAnalysis row = new ExpenseReceiptAnalysis();
        row.analysisId = response.analysisId();
        row.useruuid = useruuid;
        row.treeVersion = response.treeVersion();
        row.createdAt = LocalDateTime.now();
        row.receiptFactsJson = toJson(response.receiptFacts(), "{}");
        row.proposedAnswersJson = toJson(response.proposedAnswers(), "[]");
        row.warningsJson = toJson(response.warnings(), "[]");
        row.rawModelSummary = response.rawModelSummary();
        row.persist();
    }

    ExpenseClassificationDTOs.AnalyzeResponse parseAnalysis(String version, String raw) {
        String rawForPersist = raw == null ? "" : raw;
        try {
            JsonNode node = MAPPER.readTree(raw == null || raw.isBlank() ? fallbackAnalysisJson() : raw);
            String analysisId = UUID.randomUUID().toString();
            ExpenseClassificationDTOs.ReceiptFacts facts = MAPPER.treeToValue(node.path("receiptFacts"), ExpenseClassificationDTOs.ReceiptFacts.class);
            List<ExpenseClassificationDTOs.Answer> proposed = new ArrayList<>();
            int dropped = 0;
            JsonNode proposedNode = node.path("proposedAnswers");
            if (proposedNode.isArray()) {
                for (JsonNode answerNode : proposedNode) {
                    ExpenseClassificationDTOs.Answer answer = MAPPER.treeToValue(answerNode, ExpenseClassificationDTOs.Answer.class);
                    if (isAiSuppressedAnswer(answer.nodeKey(), answer.answerKey())) {
                        log.infof("[Expense-Classify] Suppressing AI catch-all proposal node=%s answer=%s — employee must choose.",
                                answer.nodeKey(), answer.answerKey());
                        continue;
                    }
                    if (answer.confidence() != null && answer.confidence() >= AI_ACCEPT_THRESHOLD) {
                        proposed.add(modelProposedAnswer(answer));
                    } else {
                        dropped++;
                        log.infof("[Expense-Classify] Dropping low-confidence answer node=%s answer=%s confidence=%s threshold=%.2f",
                                answer.nodeKey(),
                                answer.answerKey(),
                                String.valueOf(answer.confidence()),
                                AI_ACCEPT_THRESHOLD);
                    }
                }
            }
            if (dropped > 0) {
                log.infof("[Expense-Classify] %d proposed answer(s) below threshold %.2f", dropped, AI_ACCEPT_THRESHOLD);
            }
            List<String> warnings = new ArrayList<>();
            JsonNode warningsNode = node.path("warnings");
            if (warningsNode.isArray()) {
                warningsNode.forEach(w -> warnings.add(w.asText()));
            }
            facts = normalizeFacts(facts, warnings);
            if (isReceiptUnreadable(facts) && warnings.stream().noneMatch(w -> w != null && w.contains("couldn't read"))) {
                warnings.add(UNREADABLE_WARNING);
                log.infof("[Expense-Classify] Receipt facts empty — appending unreadable warning so the UI surfaces it.");
            }
            String summary = node.path("rawModelSummary").asText("");
            // Persist the raw model output (or a slice of it) when the model produced no
            // useful summary but parsing technically succeeded. Without this, silent-empty
            // results are unrecoverable after the fact.
            if (summary == null || summary.isBlank()) {
                summary = rawForPersist.length() > 4000 ? rawForPersist.substring(0, 4000) : rawForPersist;
            }
            return new ExpenseClassificationDTOs.AnalyzeResponse(
                    analysisId,
                    version,
                    facts == null ? emptyFacts("receipt") : facts,
                    sanitizeAnswers(version, proposed),
                    warnings,
                    summary
            );
        } catch (Exception ex) {
            log.warnf(ex, "[Expense-Classify] Failed to parse expense receipt classification response");
            String summary = rawForPersist.length() > 4000 ? rawForPersist.substring(0, 4000) : rawForPersist;
            return new ExpenseClassificationDTOs.AnalyzeResponse(
                    UUID.randomUUID().toString(),
                    version,
                    emptyFacts("receipt"),
                    List.of(),
                    List.of("Receipt analysis was unavailable. Please choose the classification manually."),
                    summary
            );
        }
    }

    /**
     * True when {@code facts} carries no meaningful receipt signal — i.e. merchantName,
     * date and amount are all null/blank. A genuine receipt always carries at least one
     * of these; if all are missing the analyser either looked at a non-receipt image or
     * the vision model produced an empty response, and we should ask the user for a
     * better photo instead of silently approving.
     */
    static boolean isReceiptUnreadable(ExpenseClassificationDTOs.ReceiptFacts facts) {
        if (facts == null) return true;
        boolean noMerchant = facts.merchantName() == null || facts.merchantName().isBlank();
        boolean noDate = facts.date() == null || facts.date().isBlank();
        boolean noAmount = facts.amount() == null;
        return noMerchant && noDate && noAmount;
    }

    /**
     * Enforces the amount/currency contract the system prompt asks for, because a prompt is a
     * request and not a guarantee. A production comparison of 62 rows showed three recurring
     * signatures: a refund read as a negative amount (5 rows at exactly -1.000 the expected
     * ratio), a foreign-currency receipt whose face value was consumed as kroner (implied FX
     * ~7.5 for EUR and ~6.4 for USD), and an ex-VAT base reported as the total (ratio 0.800
     * recurring, Danish moms being 25%).
     * <p>
     * Only the first is mechanically correctable here — the sign is moved into
     * {@code documentType}, which is where the distinction belongs. The other two are visible
     * only to the model, so they are addressed in the prompt and schema, and this method just
     * makes the currency mismatch loud: it appends to the same {@code warnings} list the wizard
     * renders instead of converting an amount we have no exchange rate for. The employee is the
     * only party who knows what was actually charged in DKK. Only a currency that is really
     * foreign warns — {@link #normalizeCurrency} folds the Danish krone spellings into DKK first,
     * because shouting at an employee over an ordinary Netto receipt costs us the whole feature.
     *
     * @param warnings the wizard's warning list. MUST be mutable — this method appends to it.
     *                 {@code null} is tolerated (the warnings are then simply dropped); an
     *                 immutable non-empty list is a programming error and will throw.
     */
    static ExpenseClassificationDTOs.ReceiptFacts normalizeFacts(ExpenseClassificationDTOs.ReceiptFacts facts,
                                                                List<String> warnings) {
        if (facts == null) return null;
        List<String> warningSink = warnings == null ? new ArrayList<>() : warnings;
        Double amount = facts.amount();
        String documentType = facts.documentType();
        boolean creditNote = isCreditNote(documentType);

        if (amount != null && amount < 0) {
            log.infof("[Expense-Classify] Negative amount %s from model — normalizing to a positive %s typed as %s.",
                    amount, Math.abs(amount), DOCUMENT_TYPE_CREDIT_NOTE);
            amount = Math.abs(amount);
            creditNote = true;
        }
        if (creditNote) {
            documentType = DOCUMENT_TYPE_CREDIT_NOTE;
            // Dedupe on our own constant, never on a substring of model-authored text.
            if (!warningSink.contains(CREDIT_NOTE_WARNING)) {
                warningSink.add(CREDIT_NOTE_WARNING);
            }
        } else if (documentType == null || documentType.isBlank()) {
            documentType = DOCUMENT_TYPE_RECEIPT;
        }

        String currency = normalizeCurrency(facts.currency());
        if (currency != null && !ACCOUNTING_CURRENCY.equals(currency)) {
            String warning = currencyMismatchWarning(currency);
            if (!warningSink.contains(warning)) {
                warningSink.add(warning);
            }
            log.infof("[Expense-Classify] Receipt currency %s is not %s — amount is NOT converted; warning added.",
                    currency, ACCOUNTING_CURRENCY);
        }

        return new ExpenseClassificationDTOs.ReceiptFacts(
                facts.merchantName(),
                facts.date(),
                amount,
                currency,
                facts.supplierCountry(),
                facts.visibleVatText(),
                facts.lineItems(),
                documentType
        );
    }

    /**
     * Folds whatever the model wrote in {@code currency} into an ISO-ish upper-case code, mapping
     * every Danish spelling of the krone onto {@link #ACCOUNTING_CURRENCY}.
     * <p>
     * The schema constrains this field to {@link #ALLOWED_RECEIPT_CURRENCIES} plus null, but a
     * prompt-and-schema contract is a request, not a guarantee, and the cost of it being broken
     * here is asymmetric: an unmapped "kr." fires the loudest warning in the wizard on the most
     * ordinary purchase an employee can make. So the same rule is enforced twice.
     * <p>
     * Punctuation is stripped before the lookup, which also disposes of the Danish price artefacts
     * the model copies straight off the receipt: "kr.", "kr,-" and a bare ",-" all reduce to KR or
     * to nothing. A value that reduces to nothing is unknown, not a mismatch, so it returns null.
     */
    static String normalizeCurrency(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[.,\\-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) return null;
        return CURRENCY_SYNONYMS.getOrDefault(cleaned, cleaned);
    }

    /**
     * The exact wording used when the receipt is not in {@link #ACCOUNTING_CURRENCY}. Built in one
     * place so the dedupe guard above can compare on equality against a string we authored, rather
     * than probing untrusted model text for a substring.
     */
    private static String currencyMismatchWarning(String currency) {
        return "This receipt is in " + currency + " — the amount read off it is in "
                + currency + ", not DKK. Enter the DKK amount you were actually charged.";
    }

    /** True when the model typed the document as a refund/credit note rather than a purchase. */
    private static boolean isCreditNote(String documentType) {
        if (documentType == null) return false;
        String normalized = documentType.toLowerCase(Locale.ROOT);
        return normalized.contains("credit") || normalized.contains("kredit") || normalized.contains("refund");
    }

    /**
     * Facts for a receipt nothing could be read off — a PDF we never sent to the model, or a
     * response that failed to parse. The currency is null, NOT DKK: the prompt and schema tell
     * the model to return null rather than guess DKK, and this fallback has to make the same
     * distinction, or "we know nothing about this document" arrives downstream looking exactly
     * like "we confirmed this receipt is in kroner".
     */
    private ExpenseClassificationDTOs.ReceiptFacts emptyFacts(String documentType) {
        return new ExpenseClassificationDTOs.ReceiptFacts(null, null, null, null, null, null, List.of(), documentType);
    }

    private Map<String, String> parseStringMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, STRING_MAP);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String toJson(Object value, String fallback) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }

    String analysisSystemPrompt(String treeVersion) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                You classify employee receipts for Trustworks before expense submission.
                Return facts visible on the receipt and proposed answers to the question tree.
                Do not output account numbers, tax codes, or final accounting decisions.

                Each proposedAnswer object MUST set:
                  - "nodeKey": the exact node identifier from the list below — NEVER the answer string itself
                  - "answerKey": one of the valid answer values listed for that node
                  - "confidence": a number 0.0 to 1.0 reflecting visual evidence
                  - "evidence": a short sentence quoting receipt details that justify the choice
                  - "source": "AI"

                You may ONLY propose answers for these AI-allowed nodes (skip a node if you have no evidence):
                """);
        for (NodeAnswerOptions node : aiAllowedNodes(treeVersion)) {
            prompt.append("  - nodeKey \"").append(node.nodeKey).append("\" — ").append(node.prompt).append("\n");
            prompt.append("      valid answerKey values: ").append(String.join(", ", node.answerKeys)).append("\n");
        }
        prompt.append("""

                Always propose an answer for nodeKey "root" when the receipt makes the expense category
                obvious (e.g. restaurant/cafeteria/grocery receipts ⇒ food_catering; bridge/toll/parking/
                taxi/train ⇒ transport_travel; flower shop or chocolatier ⇒ gift).

                Never invent a catch-all answer. If none of the listed categories clearly fits the
                receipt, do NOT answer the "root" node — leave it out of proposedAnswers so the
                employee selects the category themselves.

                The "date" field in receiptFacts MUST be formatted as ISO yyyy-MM-dd (e.g. 2025-11-07).
                Convert from any format printed on the receipt (e.g. "07-11-2025", "7/11 2025", "Nov 7,
                2025"). If you cannot determine a full date, return null.

                AMOUNT — read this carefully, it is the field we get wrong most often:
                  - "amount" MUST be the GROSS TOTAL ACTUALLY PAID, i.e. the final total INCLUDING VAT.
                    On a Danish receipt that is the line labelled "Total", "At betale", "I alt" or
                    "Beløb" — the number the card was charged.
                  - It is NEVER the ex-VAT base ("subtotal", "ekskl. moms", "netto", "grundlag"), NEVER
                    a subtotal, and NEVER a single line item. Danish VAT ("moms") is 25%, so an ex-VAT
                    base is exactly 0.80 of the gross total: if you report that instead you understate
                    the expense by a fifth. When the receipt prints both a base and a moms line, the
                    gross total is base + moms — report that sum, not the base.
                  - "amount" MUST be a POSITIVE number, even for a refund. Never return a negative
                    number and never return a leading minus sign.
                  - If the document is a refund, return, credit note ("kreditnota") or otherwise shows
                    money going BACK to the cardholder, report the absolute value in "amount" and set
                    "documentType" to "credit_note". Otherwise set "documentType" to "receipt". The
                    sign never carries this distinction — "documentType" does.

                CURRENCY — you cannot convert, so do not try:
                  - "currency" MUST be the currency PRINTED ON THE RECEIPT, as an ISO code (DKK, EUR,
                    USD, SEK, NOK, GBP …). Derive it from the symbol or wording if no code is printed
                    ("kr." on a Danish receipt ⇒ DKK, "€" ⇒ EUR, "$" ⇒ USD). If you genuinely cannot
                    tell, return null rather than guessing DKK.
                  - "amount" is ALWAYS expressed in "currency". NEVER convert to DKK, never apply an
                    exchange rate, never estimate one — you cannot know the rate that was actually
                    charged. A foreign-currency receipt is handed to the employee to convert.

                VAT: on Danish receipts "moms" means VAT. Put the VAT line exactly as printed into
                "visibleVatText" (e.g. "Moms 25% 62,50" or "Heraf moms kr. 62,50"), or null if the
                receipt shows no VAT line. Do not compute a VAT amount that is not printed.
                """);
        return prompt.toString();
    }

    /**
     * True when an AI-proposed answer is a catch-all the model is not allowed to choose on the
     * employee's behalf (currently the root "Other / not sure" route). Suppressing these forces an
     * unanswered required node so the wizard asks the employee instead of silently routing to the
     * Finance-review fallback. The same answer remains valid when a human submits it.
     */
    private boolean isAiSuppressedAnswer(String nodeKey, String answerKey) {
        return ROOT_NODE_KEY.equals(nodeKey) && AI_SUPPRESSED_ROOT_ANSWERS.contains(answerKey);
    }

    /**
     * Reads the active classification tree and returns the AI-allowed nodes (policy
     * AI_ALLOWED_USER_CAN_OVERRIDE) with their prompt + valid answer keys. Used to build
     * the OpenAI system prompt dynamically so it can never drift from the seeded tree.
     */
    List<NodeAnswerOptions> aiAllowedNodes(String treeVersion) {
        List<ExpenseClassificationNode> nodes = ExpenseClassificationNode.list(
                "treeVersion = ?1 and answerSourcePolicy = ?2",
                Sort.by("sortOrder"),
                treeVersion,
                "AI_ALLOWED_USER_CAN_OVERRIDE"
        );
        Map<String, List<ExpenseClassificationOption>> options = ExpenseClassificationOption.<ExpenseClassificationOption>list(
                        "treeVersion = ?1",
                        Sort.by("sortOrder"),
                        treeVersion
                )
                .stream()
                .collect(Collectors.groupingBy(o -> o.nodeKey, LinkedHashMap::new, Collectors.toList()));
        List<NodeAnswerOptions> result = new ArrayList<>(nodes.size());
        for (ExpenseClassificationNode node : nodes) {
            List<String> answerKeys = options.getOrDefault(node.nodeKey, List.of())
                    .stream().map(o -> o.answerKey)
                    .filter(answerKey -> !isAiSuppressedAnswer(node.nodeKey, answerKey))
                    .toList();
            result.add(new NodeAnswerOptions(node.nodeKey, node.prompt, answerKeys));
        }
        return result;
    }

    record NodeAnswerOptions(String nodeKey, String prompt, List<String> answerKeys) {}

    private ObjectNode analysisSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putArray("required").add("receiptFacts").add("proposedAnswers").add("warnings").add("rawModelSummary");
        schema.put("additionalProperties", false);

        ObjectNode properties = schema.putObject("properties");
        ObjectNode facts = properties.putObject("receiptFacts");
        facts.put("type", "object");
        facts.put("additionalProperties", false);
        facts.putArray("required")
                .add("merchantName").add("date").add("amount").add("currency")
                .add("supplierCountry").add("visibleVatText").add("lineItems").add("documentType");
        ObjectNode factProps = facts.putObject("properties");
        nullableString(factProps, "merchantName");
        nullableIsoDate(factProps, "date");
        nullableNumber(factProps, "amount").put("description",
                "The GROSS TOTAL ACTUALLY PAID, including VAT — the final 'Total'/'At betale'/'I alt' "
                        + "line the card was charged. Never the ex-VAT base ('ekskl. moms', 'subtotal', "
                        + "'netto'), never a subtotal, never a single line item. Danish VAT (moms) is "
                        + "25%, so an ex-VAT base is exactly 0.80 of the gross total. Always POSITIVE, "
                        + "including for refunds — a refund is signalled by documentType, not by a "
                        + "minus sign. Expressed in the 'currency' field, never converted.");
        nullableEnumString(factProps, "currency", ALLOWED_RECEIPT_CURRENCIES).put("description",
                "ISO code of the currency PRINTED ON THE RECEIPT, chosen from the listed codes and "
                        + "derived from the symbol or wording when no code is printed ('kr.' or "
                        + "'kroner' on a Danish receipt ⇒ DKK, '€' ⇒ EUR, '$' ⇒ USD). Null when it "
                        + "cannot be determined or is not one of the listed codes — do not guess "
                        + "DKK, and do not pick the nearest listed code. Never convert 'amount' "
                        + "into another currency; you cannot know the rate charged.");
        nullableString(factProps, "supplierCountry");
        nullableString(factProps, "visibleVatText").put("description",
                "The VAT line exactly as printed on the receipt — on Danish receipts 'moms' means VAT "
                        + "(e.g. 'Moms 25% 62,50'). Null when the receipt shows no VAT line. Never a "
                        + "VAT amount you computed yourself.");
        ObjectNode lineItems = factProps.putObject("lineItems");
        lineItems.put("type", "array");
        lineItems.putObject("items").put("type", "string");
        nullableString(factProps, "documentType").put("description",
                "\"receipt\" for a normal purchase, \"credit_note\" when the document is a refund, "
                        + "return or credit note (kreditnota) — money going back to the cardholder. "
                        + "This field, not the sign of 'amount', carries that distinction.");

        ObjectNode proposed = properties.putObject("proposedAnswers");
        proposed.put("type", "array");
        ObjectNode answerItem = proposed.putObject("items");
        answerItem.put("type", "object");
        answerItem.put("additionalProperties", false);
        answerItem.putArray("required").add("nodeKey").add("answerKey").add("source").add("confidence").add("evidence").add("accepted");
        ObjectNode answerProps = answerItem.putObject("properties");
        answerProps.putObject("nodeKey").put("type", "string");
        answerProps.putObject("answerKey").put("type", "string");
        ObjectNode source = answerProps.putObject("source");
        source.put("type", "string");
        source.putArray("enum").add("AI");
        nullableNumber(answerProps, "confidence");
        nullableString(answerProps, "evidence");
        ObjectNode accepted = answerProps.putObject("accepted");
        ArrayNode acceptedTypes = accepted.putArray("type");
        acceptedTypes.add("boolean").add("null");

        ObjectNode warnings = properties.putObject("warnings");
        warnings.put("type", "array");
        warnings.putObject("items").put("type", "string");
        properties.putObject("rawModelSummary").put("type", "string");
        return schema;
    }

    /** Declares a nullable string property and returns it so a description can be attached. */
    private ObjectNode nullableString(ObjectNode props, String name) {
        ObjectNode field = props.putObject(name);
        ArrayNode types = field.putArray("type");
        types.add("string").add("null");
        return field;
    }

    /**
     * Declares a nullable string property constrained to {@code values} and returns it so a
     * description can be attached. The null literal is added to the enum on purpose: strict mode
     * validates the value against the enum as well as the type union, so a field typed
     * ["string","null"] must list null among its allowed values or null becomes unreturnable.
     */
    private ObjectNode nullableEnumString(ObjectNode props, String name, List<String> values) {
        ObjectNode field = nullableString(props, name);
        ArrayNode allowed = field.putArray("enum");
        values.forEach(allowed::add);
        allowed.addNull();
        return field;
    }

    /** Declares a nullable number property and returns it so a description can be attached. */
    private ObjectNode nullableNumber(ObjectNode props, String name) {
        ObjectNode field = props.putObject(name);
        ArrayNode types = field.putArray("type");
        types.add("number").add("null");
        return field;
    }

    static ExpenseClassificationDTOs.Answer modelProposedAnswer(ExpenseClassificationDTOs.Answer answer) {
        return new ExpenseClassificationDTOs.Answer(
                answer.nodeKey(),
                answer.answerKey(),
                "AI",
                answer.confidence(),
                answer.evidence(),
                answer.accepted()
        );
    }

    private void nullableIsoDate(ObjectNode props, String name) {
        ObjectNode field = props.putObject(name);
        ArrayNode types = field.putArray("type");
        types.add("string").add("null");
        field.put("pattern", "^\\d{4}-\\d{2}-\\d{2}$");
    }

    private String fallbackAnalysisJson() {
        return """
                {
                  "receiptFacts": {
                    "merchantName": null,
                    "date": null,
                    "amount": null,
                    "currency": null,
                    "supplierCountry": null,
                    "visibleVatText": null,
                    "lineItems": [],
                    "documentType": "receipt"
                  },
                  "proposedAnswers": [],
                  "warnings": ["Receipt analysis was unavailable. Please choose the classification manually."],
                  "rawModelSummary": ""
                }
                """;
    }
}
