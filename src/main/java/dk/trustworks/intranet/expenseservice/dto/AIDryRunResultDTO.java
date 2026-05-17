package dk.trustworks.intranet.expenseservice.dto;

import java.util.List;

public record AIDryRunResultDTO(
    String extractedReceiptText,
    List<AIRuleVerdictDTO> ruleVerdicts,
    boolean approved, String routingOutcome) {}
