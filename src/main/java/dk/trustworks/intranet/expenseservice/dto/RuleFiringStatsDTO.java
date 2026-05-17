package dk.trustworks.intranet.expenseservice.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RuleFiringStatsDTO(List<Entry> stats) {
    public record Entry(String ruleId, int firingsLast30d, LocalDateTime lastFiredAt) {}
}
