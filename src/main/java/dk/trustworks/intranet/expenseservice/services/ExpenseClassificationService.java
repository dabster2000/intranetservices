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
    // Vision + strict JSON schema can produce more tokens than the default 4096
    // budget once the model includes a rich line-item array; bump just for this path.
    private static final int VISION_MAX_OUTPUT_TOKENS = 8192;
    private static final String UNREADABLE_WARNING =
            "We couldn't read the receipt. Try a sharper, well-lit photo showing the date, merchant and total.";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    @Inject OpenAIService openAIService;
    @Inject UserService userService;

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
        String visionModel = openAIService.getVisionModel();
        int base64Len = request.receiptBase64().length();
        log.infof("[Expense-Classify] Start analyze. useruuid=%s treeVersion=%s mime=%s base64Len=%d model=%s",
                useruuid, version, mime, base64Len, visionModel);

        long startNanos = System.nanoTime();
        String raw = openAIService.askWithSchemaAndImage(
                analysisSystemPrompt(version),
                "Read this employee receipt. Return only facts visible on the receipt and proposed question-tree answers. Do not choose final account numbers.",
                request.receiptBase64(),
                mime,
                analysisSchema(),
                "expense_receipt_analysis",
                fallback,
                visionModel,
                VISION_MAX_OUTPUT_TOKENS
        );
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        String rawPreview = raw == null ? "null"
                : (raw.length() > 500 ? raw.substring(0, 500) + "..." : raw);
        log.infof("[Expense-Classify] OpenAI returned in %d ms. rawLen=%d preview=%s",
                elapsedMs, raw == null ? 0 : raw.length(), rawPreview);

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

    private ExpenseClassificationDTOs.ReceiptFacts emptyFacts(String documentType) {
        return new ExpenseClassificationDTOs.ReceiptFacts(null, null, null, "DKK", null, null, List.of(), documentType);
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

                The "date" field in receiptFacts MUST be formatted as ISO yyyy-MM-dd (e.g. 2025-11-07).
                Convert from any format printed on the receipt (e.g. "07-11-2025", "7/11 2025", "Nov 7,
                2025"). If you cannot determine a full date, return null.
                """);
        return prompt.toString();
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
                    .stream().map(o -> o.answerKey).toList();
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
        nullableNumber(factProps, "amount");
        nullableString(factProps, "currency");
        nullableString(factProps, "supplierCountry");
        nullableString(factProps, "visibleVatText");
        ObjectNode lineItems = factProps.putObject("lineItems");
        lineItems.put("type", "array");
        lineItems.putObject("items").put("type", "string");
        nullableString(factProps, "documentType");

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

    private void nullableString(ObjectNode props, String name) {
        ObjectNode field = props.putObject(name);
        ArrayNode types = field.putArray("type");
        types.add("string").add("null");
    }

    private void nullableNumber(ObjectNode props, String name) {
        ObjectNode field = props.putObject(name);
        ArrayNode types = field.putArray("type");
        types.add("number").add("null");
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
                    "currency": "DKK",
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
