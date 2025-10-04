package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.contracts.exceptions.ContractValidationException;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Report containing the results of contract validation.
 * Used to provide detailed feedback about validation issues before saving.
 */
@Data
@NoArgsConstructor
public class ValidationReport {

    private boolean valid = true;
    private LocalDateTime validatedAt = LocalDateTime.now();
    private List<ContractValidationException.ValidationError> errors = new ArrayList<>();
    private List<Warning> warnings = new ArrayList<>();
    private List<ContractOverlap> overlaps = new ArrayList<>();
    private ValidationContext context;

    /**
     * Statistics about the validation process.
     */
    @Data
    @NoArgsConstructor
    public static class ValidationContext {
        private String contractUuid;
        private String consultantUuid;
        private String consultantName;
        private String projectUuid;
        private String projectName;
        private int affectedWorkEntries;
        private double unbilledAmount;
        private int conflictingContracts;
    }

    /**
     * Warning that doesn't prevent saving but should be reviewed.
     */
    @Data
    @NoArgsConstructor
    public static class Warning {
        private String field;
        private String message;
        private WarningType type;

        public Warning(String field, String message, WarningType type) {
            this.field = field;
            this.message = message;
            this.type = type;
        }
    }

    public enum WarningType {
        RATE_CHANGE("Rate change affects unbilled work"),
        FUTURE_CONTRACT("Contract starts in the future"),
        GAP_IN_COVERAGE("Gap in consultant coverage"),
        RETROACTIVE_CONTRACT("Contract covers past work"),
        HIGH_RATE("Rate is unusually high"),
        LOW_RATE("Rate is unusually low");

        private final String description;

        WarningType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public void addError(ContractValidationException.ValidationError error) {
        this.errors.add(error);
        this.valid = false;
    }

    public void addWarning(Warning warning) {
        this.warnings.add(warning);
    }

    public void addOverlap(ContractOverlap overlap) {
        this.overlaps.add(overlap);
        this.valid = false;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    public boolean hasOverlaps() {
        return !overlaps.isEmpty();
    }

    public String getSummary() {
        if (valid) {
            return "Validation passed successfully";
        }

        StringBuilder summary = new StringBuilder("Validation failed: ");
        if (hasErrors()) {
            summary.append(errors.size()).append(" error(s)");
        }
        if (hasOverlaps()) {
            if (hasErrors()) summary.append(", ");
            summary.append(overlaps.size()).append(" overlap(s)");
        }
        if (hasWarnings()) {
            if (hasErrors() || hasOverlaps()) summary.append(", ");
            summary.append(warnings.size()).append(" warning(s)");
        }
        return summary.toString();
    }
}