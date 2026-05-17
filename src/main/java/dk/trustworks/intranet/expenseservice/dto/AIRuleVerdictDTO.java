package dk.trustworks.intranet.expenseservice.dto;

public record AIRuleVerdictDTO(
    String ruleId, String severity, String resolutionType,
    boolean fired, String explanation) {}
