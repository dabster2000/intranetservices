package dk.trustworks.intranet.aggregates.bugreport.dto;

public record SuggestResponse(
    String field,
    String suggestion
) {}
