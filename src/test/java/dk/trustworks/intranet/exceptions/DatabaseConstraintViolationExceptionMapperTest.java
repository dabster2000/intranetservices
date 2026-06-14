package dk.trustworks.intranet.exceptions;

import jakarta.ws.rs.core.Response;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plain unit test (no DB / no @QuarkusTest) — verifies the Hibernate DB constraint violation
 * (e.g. the duplicate uq_userstatus_user_date that previously surfaced as 500) is mapped to 409.
 */
class DatabaseConstraintViolationExceptionMapperTest {

    @Test
    void duplicateKeyConstraintViolationMapsTo409() {
        // The exact shape seen on staging: 1062-23000 duplicate entry on uq_userstatus_user_date.
        ConstraintViolationException ex = mock(ConstraintViolationException.class);
        when(ex.getConstraintName()).thenReturn("uq_userstatus_user_date");
        when(ex.getSQLException()).thenReturn(
                new SQLException("Duplicate entry 'u-2026-06-01' for key 'uq_userstatus_user_date'", "23000", 1062));
        when(ex.getMessage()).thenReturn("could not execute batch ... Duplicate entry");

        Response r = new DatabaseConstraintViolationExceptionMapper().toResponse(ex);

        assertEquals(Response.Status.CONFLICT.getStatusCode(), r.getStatus(), "DB constraint violation must map to 409");
        assertInstanceOf(ErrorResponse.class, r.getEntity());
        // Message is the clean, generic one — no schema leak to the client.
        ErrorResponse body = (ErrorResponse) r.getEntity();
        assertEquals(DatabaseConstraintViolationExceptionMapper.MESSAGE, body.error());
        assertEquals(409, body.status());
    }

    @Test
    void nullSqlExceptionDoesNotNpe() {
        ConstraintViolationException ex = mock(ConstraintViolationException.class);
        when(ex.getSQLException()).thenReturn(null);
        when(ex.getConstraintName()).thenReturn(null);
        when(ex.getMessage()).thenReturn("constraint violation");

        Response r = new DatabaseConstraintViolationExceptionMapper().toResponse(ex);
        assertEquals(409, r.getStatus());
    }
}
