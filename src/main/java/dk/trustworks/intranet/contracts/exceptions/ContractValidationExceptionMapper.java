package dk.trustworks.intranet.contracts.exceptions;

import dk.trustworks.intranet.contracts.exceptions.ContractValidationException.ValidationError;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

/**
 * Maps {@link ContractValidationException} — a client/validation error raised when a contract fails
 * business-rule validation (overlapping assignments, invalid date ranges, missing required fields …) —
 * to an HTTP <b>400 Bad Request</b> carrying the structured list of validation errors.
 * <p>
 * Without this mapper the exception falls through to
 * {@link dk.trustworks.intranet.exceptions.GenericExceptionMapper} and surfaces as a generic <b>500</b>,
 * so the user sees "Internal Server Error" instead of the actionable per-field messages that explain
 * what to fix. Because {@code ContractValidationException} is a validation failure (never a server fault)
 * and is thrown from a single site (contract validation enforcement), mapping it to 400 is always correct.
 * <p>
 * The response body is a superset of the standard
 * {@link dk.trustworks.intranet.exceptions.ErrorResponse} shape: it keeps the same {@code error}
 * (summary) and {@code status} fields and adds a structured {@code errors} array so the frontend can
 * render field-level feedback. This is a client error, so — like the other 4xx mappers — it is not
 * logged at {@code ERROR}; that ERROR-level noise is exactly the symptom this mapper removes.
 */
@Provider
@JBossLog
public class ContractValidationExceptionMapper implements ExceptionMapper<ContractValidationException> {

    @Override
    public Response toResponse(ContractValidationException exception) {
        List<FieldError> fieldErrors = exception.getErrors().stream()
                .map(ContractValidationExceptionMapper::toFieldError)
                .toList();

        log.debugf("Contract validation failed -> 400: %d error(s)", fieldErrors.size());

        ContractValidationErrorResponse body = new ContractValidationErrorResponse(
                exception.getMessage(),
                Response.Status.BAD_REQUEST.getStatusCode(),
                fieldErrors);

        return Response.status(Response.Status.BAD_REQUEST)
                .entity(body)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static FieldError toFieldError(ValidationError error) {
        return new FieldError(
                error.getField(),
                error.getMessage(),
                error.getType() != null ? error.getType().name() : null);
    }

    /**
     * Response body for a failed contract validation. Extends the standard
     * {@link dk.trustworks.intranet.exceptions.ErrorResponse} contract ({@code error} + {@code status})
     * with a structured, machine-readable {@code errors} list.
     */
    public record ContractValidationErrorResponse(String error, int status, List<FieldError> errors) {
    }

    /**
     * A single field-level validation error. {@code type} is the
     * {@link ContractValidationException.ErrorType} name (e.g. {@code OVERLAP_CONFLICT}), serialized as a
     * stable string so the frontend can branch on it without depending on the enum ordinal.
     */
    public record FieldError(String field, String message, String type) {
    }
}
