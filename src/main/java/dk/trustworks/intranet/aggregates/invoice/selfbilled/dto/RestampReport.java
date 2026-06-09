package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

import java.util.List;
import java.util.Map;

/** Result of the re-stamp phase: counts by outcome + the per-internal decisions (audit). */
public record RestampReport(boolean applied, Map<String, Integer> counts, List<RestampDecision> decisions) {}
