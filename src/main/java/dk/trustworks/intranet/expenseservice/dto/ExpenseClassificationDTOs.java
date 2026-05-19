package dk.trustworks.intranet.expenseservice.dto;

import java.util.List;

public final class ExpenseClassificationDTOs {
    private ExpenseClassificationDTOs() {}

    public record Answer(
            String nodeKey,
            String answerKey,
            String source,
            Double confidence,
            String evidence,
            Boolean accepted
    ) {}

    public record ReceiptFacts(
            String merchantName,
            String date,
            Double amount,
            String currency,
            String supplierCountry,
            String visibleVatText,
            List<String> lineItems,
            String documentType
    ) {}

    public record AnalyzeRequest(String receiptBase64, String mimeType, String fileName) {}

    public record AnalyzeResponse(
            String analysisId,
            String treeVersion,
            ReceiptFacts receiptFacts,
            List<Answer> proposedAnswers,
            List<String> warnings,
            String rawModelSummary
    ) {}

    public record OptionDTO(String answerKey, String label) {}

    public record NodeDTO(
            String nodeKey,
            String prompt,
            String answerSourcePolicy,
            boolean required,
            List<OptionDTO> options
    ) {}

    public record TreeResponse(String treeVersion, List<NodeDTO> nodes) {}

    public record ResolveRequest(
            String treeVersion,
            String analysisId,
            Boolean aiUsed,
            Boolean aiIgnored,
            List<Answer> answers,
            List<Answer> ignoredAiAnswers
    ) {}

    public record ResolvedResult(
            String resultKey,
            String employeeLabel,
            String employeeSummary,
            String accountKey,
            String accountNumber,
            String accountName,
            String taxTreatment,
            boolean requiresFinanceReview
    ) {}

    public record ResolveResponse(
            String state,
            String treeVersion,
            List<NodeDTO> unansweredNodes,
            ResolvedResult result,
            List<Answer> acceptedAnswers
    ) {}

    public record Submission(
            String treeVersion,
            String decisionResultKey,
            String accountKey,
            String analysisId,
            Boolean aiUsed,
            Boolean aiIgnored,
            Boolean requiresFinanceReview,
            List<Answer> answers,
            List<Answer> ignoredAiAnswers
    ) {}
}
