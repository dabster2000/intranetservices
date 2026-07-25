package dk.trustworks.intranet.exceptions;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PersistenceException;
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
    void wrappedDuplicateKeyConstraintViolationMapsTo409() {
        ConstraintViolationException constraint = mock(ConstraintViolationException.class);
        when(constraint.getConstraintName()).thenReturn("uq_userstatus_user_date");
        when(constraint.getSQLException()).thenReturn(
                new SQLException("Duplicate entry 'u-2026-06-01' for key 'uq_userstatus_user_date'", "23000", 1062));
        when(constraint.getMessage()).thenReturn("could not execute statement");

        Response r = new DatabaseConstraintViolationExceptionMapper()
                .toResponse(new PersistenceException("flush failed", constraint));

        assertEquals(Response.Status.CONFLICT.getStatusCode(), r.getStatus(),
                "Wrapped DB constraint violations must still map to 409");
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

    @Test
    void unrelatedPersistenceExceptionKeepsGeneric500Shape() {
        Response r = new DatabaseConstraintViolationExceptionMapper()
                .toResponse(new PersistenceException("database unavailable"));

        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), r.getStatus());
        assertInstanceOf(ErrorResponse.class, r.getEntity());
        ErrorResponse body = (ErrorResponse) r.getEntity();
        assertEquals("Internal server error", body.error());
        assertEquals(500, body.status());
    }

    @Test
    void lockWaitTimeoutMapsToRetriable409() {
        // The exact prod shape (2026-07-24 09:00:57Z): a work-row UPDATE waited past
        // innodb_lock_wait_timeout and Hibernate surfaced jakarta LockTimeoutException.
        LockTimeoutException ex = new LockTimeoutException(
                "(conn=21056) Lock wait timeout exceeded; try restarting transaction "
                        + "[update work w1_0 set workduration=? ...]");

        Response r = new DatabaseConstraintViolationExceptionMapper().toResponse(ex);

        assertEquals(Response.Status.CONFLICT.getStatusCode(), r.getStatus(),
                "Lock-wait timeout is transient contention and must map to a retriable 409, not 500");
        ErrorResponse body = (ErrorResponse) r.getEntity();
        assertEquals(DatabaseConstraintViolationExceptionMapper.LOCK_CONTENTION_MESSAGE, body.error());
        assertEquals(409, body.status());
    }

    @Test
    void wrappedHibernateLockTimeoutMapsTo409() {
        // Hibernate-native shape wrapped in a generic PersistenceException.
        org.hibernate.exception.LockTimeoutException hibernateTimeout =
                new org.hibernate.exception.LockTimeoutException(
                        "could not execute statement",
                        new SQLException("Lock wait timeout exceeded; try restarting transaction", "HY000", 1205));

        Response r = new DatabaseConstraintViolationExceptionMapper()
                .toResponse(new PersistenceException("save failed", hibernateTimeout));

        assertEquals(409, r.getStatus(), "Wrapped Hibernate lock timeouts must still map to 409");
        assertEquals(DatabaseConstraintViolationExceptionMapper.LOCK_CONTENTION_MESSAGE,
                ((ErrorResponse) r.getEntity()).error());
    }

    @Test
    void deadlockVictimMapsTo409() {
        // MariaDB 1213 deadlock victim — same transient-contention family as the 1205 timeout.
        org.hibernate.exception.LockAcquisitionException deadlock =
                new org.hibernate.exception.LockAcquisitionException(
                        "could not execute statement",
                        new SQLException("Deadlock found when trying to get lock; try restarting transaction", "40001", 1213));

        Response r = new DatabaseConstraintViolationExceptionMapper()
                .toResponse(new PersistenceException("save failed", deadlock));

        assertEquals(409, r.getStatus(), "Deadlock victims must map to a retriable 409, not 500");
        assertEquals(DatabaseConstraintViolationExceptionMapper.LOCK_CONTENTION_MESSAGE,
                ((ErrorResponse) r.getEntity()).error());
    }

    @Test
    void constraintViolationStillWinsOverLockContentionShape() {
        // A real constraint violation keeps the uniqueness message even if the text mentions locks.
        ConstraintViolationException constraint = mock(ConstraintViolationException.class);
        when(constraint.getConstraintName()).thenReturn("uq_work_user_date_task");
        when(constraint.getSQLException()).thenReturn(
                new SQLException("Duplicate entry", "23000", 1062));
        when(constraint.getMessage()).thenReturn("could not execute statement");

        Response r = new DatabaseConstraintViolationExceptionMapper()
                .toResponse(new PersistenceException("flush failed", constraint));

        assertEquals(409, r.getStatus());
        assertEquals(DatabaseConstraintViolationExceptionMapper.MESSAGE,
                ((ErrorResponse) r.getEntity()).error());
    }
}
