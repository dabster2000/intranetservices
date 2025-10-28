package dk.trustworks.intranet.expenseservice.exceptions;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Exception thrown when expense upload to e-conomics fails.
 * Captures detailed error information for debugging and user feedback.
 */
public class ExpenseUploadException extends IOException {

    private final String errorDetails;
    private final Integer httpStatus;
    private final LocalDateTime timestamp;

    public ExpenseUploadException(String message, Integer httpStatus, String errorDetails) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorDetails = errorDetails;
        this.timestamp = LocalDateTime.now();
    }

    public ExpenseUploadException(String message, Throwable cause, Integer httpStatus, String errorDetails) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.errorDetails = errorDetails;
        this.timestamp = LocalDateTime.now();
    }

    /**
     * Get a detailed error message suitable for storing in the database
     */
    public String getDetailedMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(timestamp).append("] ");
        if (httpStatus != null) {
            sb.append("HTTP ").append(httpStatus).append(": ");
        }
        sb.append(getMessage());
        if (errorDetails != null && !errorDetails.isEmpty()) {
            sb.append("\nDetails: ").append(errorDetails);
        }
        if (getCause() != null) {
            sb.append("\nCause: ").append(getCause().getMessage());
        }
        return sb.toString();
    }

    public String getErrorDetails() {
        return errorDetails;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}
