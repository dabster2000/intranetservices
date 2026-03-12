package dk.trustworks.intranet.exceptions;

import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.stream.Collectors;

/**
 * Maps Jakarta Bean Validation constraint violations to a consistent JSON 400 response.
 * Produces human-readable field-level error messages.
 */
@Provider
public class ConstraintViolationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .map(violation -> {
                    String field = extractFieldName(violation.getPropertyPath().toString());
                    return field + ": " + violation.getMessage();
                })
                .collect(Collectors.joining("; "));

        if (message.isBlank()) {
            message = "Validation failed";
        }

        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse(message, 400))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /**
     * Extract the last segment of the property path (the field name).
     * E.g., "createUser.arg0.name" -> "name"
     */
    private String extractFieldName(String propertyPath) {
        if (propertyPath == null || propertyPath.isEmpty()) {
            return "unknown";
        }
        int lastDot = propertyPath.lastIndexOf('.');
        return lastDot >= 0 ? propertyPath.substring(lastDot + 1) : propertyPath;
    }
}
