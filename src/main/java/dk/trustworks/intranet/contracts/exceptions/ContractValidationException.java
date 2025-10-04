package dk.trustworks.intranet.contracts.exceptions;

import java.util.ArrayList;
import java.util.List;

/**
 * Exception thrown when contract validation fails.
 * Contains detailed information about validation errors to help users understand and fix issues.
 */
public class ContractValidationException extends RuntimeException {

    private final List<ValidationError> errors = new ArrayList<>();

    public ContractValidationException(String message) {
        super(message);
    }

    public ContractValidationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ContractValidationException(List<ValidationError> errors) {
        super(buildMessage(errors));
        this.errors.addAll(errors);
    }

    public void addError(ValidationError error) {
        this.errors.add(error);
    }

    public List<ValidationError> getErrors() {
        return new ArrayList<>(errors);
    }

    private static String buildMessage(List<ValidationError> errors) {
        if (errors.isEmpty()) {
            return "Contract validation failed";
        }

        StringBuilder message = new StringBuilder("Contract validation failed with ")
            .append(errors.size())
            .append(" error(s):\n");

        for (ValidationError error : errors) {
            message.append("- ").append(error.getMessage()).append("\n");
        }

        return message.toString();
    }

    /**
     * Represents a single validation error with context.
     */
    public static class ValidationError {
        private final String field;
        private final String message;
        private final ErrorType type;

        public ValidationError(String field, String message, ErrorType type) {
            this.field = field;
            this.message = message;
            this.type = type;
        }

        public String getField() {
            return field;
        }

        public String getMessage() {
            return message;
        }

        public ErrorType getType() {
            return type;
        }

        @Override
        public String toString() {
            return String.format("%s: %s [%s]", field, message, type);
        }
    }

    /**
     * Type of validation error.
     */
    public enum ErrorType {
        OVERLAP_CONFLICT("Overlapping consultant assignment"),
        DATE_RANGE_INVALID("Invalid date range"),
        MISSING_REQUIRED("Missing required field"),
        RATE_CONFLICT("Rate conflict detected"),
        CONTRACT_INACTIVE("Contract is inactive"),
        WORK_EXISTS("Work exists in affected period"),
        DUPLICATE_PROJECT("Project already linked to contract");

        private final String description;

        ErrorType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}