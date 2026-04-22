package dk.trustworks.intranet.aggregates.invoice.model.dto;

public enum Classification {
    UNTOUCHED,   // baseline present, current == baseline
    INCREASED,   // baseline present, current > baseline
    DECREASED,   // baseline present, current < baseline
    DELETED,     // in baseline, no current line
    ADDED        // in current, not in baseline
}
