package dk.trustworks.intranet.expenseservice.exceptions;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Exception thrown when expense upload to e-conomics fails.
 * Captures detailed error information for debugging and user feedback.
 *
 * The e-conomic HTTP status and response body are part of {@link #getMessage()}:
 * several consumers (stack-trace logging, batch exception tracking) only ever see
 * the message, and a bare "Failed to post voucher to e-conomics" made the
 * 2026-08-12 e-conomic 503 outage undiagnosable from the logs.
 */
public class ExpenseUploadException extends IOException {

    /** Cap on how much of the e-conomic response body is carried into the message. */
    static final int MAX_DETAILS_IN_MESSAGE = 500;

    private final String errorDetails;
    private final Integer httpStatus;
    private final LocalDateTime timestamp;

    public ExpenseUploadException(String message, Integer httpStatus, String errorDetails) {
        this(message, null, httpStatus, errorDetails);
    }

    public ExpenseUploadException(String message, Throwable cause, Integer httpStatus, String errorDetails) {
        super(buildMessage(message, httpStatus, errorDetails), cause);
        this.httpStatus = httpStatus;
        this.errorDetails = errorDetails;
        this.timestamp = LocalDateTime.now();
    }

    static String buildMessage(String message, Integer httpStatus, String errorDetails) {
        StringBuilder sb = new StringBuilder(message == null ? "" : message);
        if (httpStatus != null) {
            sb.append(" [e-conomic HTTP ").append(httpStatus).append("]");
        }
        if (errorDetails != null && !errorDetails.isBlank()) {
            String details = errorDetails.length() > MAX_DETAILS_IN_MESSAGE
                    ? errorDetails.substring(0, MAX_DETAILS_IN_MESSAGE) + "..."
                    : errorDetails;
            sb.append(" — response: ").append(details);
        }
        return sb.toString();
    }

    /**
     * Get a detailed error message suitable for storing in the database.
     * Status and (truncated) response details are already part of {@link #getMessage()}.
     */
    public String getDetailedMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(timestamp).append("] ");
        sb.append(getMessage());
        if (getCause() != null) {
            sb.append("\nCause: ").append(getCause());
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
