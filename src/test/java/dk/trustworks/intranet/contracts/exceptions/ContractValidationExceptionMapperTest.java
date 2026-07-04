package dk.trustworks.intranet.contracts.exceptions;

import dk.trustworks.intranet.contracts.exceptions.ContractValidationException.ErrorType;
import dk.trustworks.intranet.contracts.exceptions.ContractValidationException.ValidationError;
import dk.trustworks.intranet.contracts.exceptions.ContractValidationExceptionMapper.ContractValidationErrorResponse;
import dk.trustworks.intranet.contracts.exceptions.ContractValidationExceptionMapper.FieldError;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit test (no DB / no @QuarkusTest) — verifies that {@link ContractValidationException},
 * which previously fell through to {@code GenericExceptionMapper} and surfaced as a 500, is mapped to
 * a <b>400 Bad Request</b> carrying the structured validation-error list instead of a generic server error.
 */
class ContractValidationExceptionMapperTest {

    private final ContractValidationExceptionMapper mapper = new ContractValidationExceptionMapper();

    @Test
    void maps_validation_errors_to_400_with_structured_error_list() {
        List<ValidationError> errors = List.of(
                new ValidationError("activeTo", "Active-to date is before active-from date", ErrorType.DATE_RANGE_INVALID),
                new ValidationError("useruuid", "Consultant already assigned in an overlapping period", ErrorType.OVERLAP_CONFLICT));

        Response response = mapper.toResponse(new ContractValidationException(errors));

        // Status + content type
        assertEquals(400, response.getStatus(), "contract validation failure must map to 400, not 500");
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());

        // Body keeps the standard ErrorResponse shape (error + status) ...
        assertInstanceOf(ContractValidationErrorResponse.class, response.getEntity());
        ContractValidationErrorResponse body = (ContractValidationErrorResponse) response.getEntity();
        assertEquals(400, body.status());
        assertNotNull(body.error());
        assertTrue(body.error().contains("2 error(s)"), "summary should mention the error count");

        // ... and adds the structured, machine-readable list.
        assertEquals(2, body.errors().size());

        FieldError first = body.errors().get(0);
        assertEquals("activeTo", first.field());
        assertEquals("Active-to date is before active-from date", first.message());
        assertEquals("DATE_RANGE_INVALID", first.type());

        FieldError second = body.errors().get(1);
        assertEquals("useruuid", second.field());
        assertEquals("OVERLAP_CONFLICT", second.type());
    }

    @Test
    void maps_message_only_exception_to_400_with_empty_error_list() {
        Response response = mapper.toResponse(new ContractValidationException("Contract is inactive"));

        assertEquals(400, response.getStatus());
        assertInstanceOf(ContractValidationErrorResponse.class, response.getEntity());

        ContractValidationErrorResponse body = (ContractValidationErrorResponse) response.getEntity();
        assertEquals(400, body.status());
        assertEquals("Contract is inactive", body.error(), "the exception message is preserved as the summary");
        assertTrue(body.errors().isEmpty(), "a message-only exception yields an empty structured list, never null");
    }
}
