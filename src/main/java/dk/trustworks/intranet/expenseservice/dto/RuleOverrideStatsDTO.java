package dk.trustworks.intranet.expenseservice.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RuleOverrideStatsDTO(int windowDays, List<Entry> stats) {
    public record Entry(String ruleId, int firings, int blockedExpenses,
                        int overriddenExpenses, double overrideRate, LocalDateTime lastFiredAt) {

        public static Entry of(String ruleId, int firings, int blockedExpenses,
                               int overriddenExpenses, LocalDateTime lastFiredAt) {
            double rate = blockedExpenses == 0 ? 0.0 : (double) overriddenExpenses / blockedExpenses;
            return new Entry(ruleId, firings, blockedExpenses, overriddenExpenses, rate, lastFiredAt);
        }
    }
}
