package dk.trustworks.intranet.exceptions;

import io.quarkus.arc.ArcUndeclaredThrowableException;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.jbosslog.JBossLog;

/**
 * Unwraps {@link ArcUndeclaredThrowableException} so the persistence mappers can see what is
 * actually inside it.
 *
 * <p><b>Why this exists.</b> {@link DatabaseConstraintViolationExceptionMapper} deliberately maps
 * transient InnoDB lock contention — a lock-wait timeout (MariaDB 1205) or a deadlock victim
 * (MariaDB 1213) — to a retriable <b>409</b> rather than a 500. But it is declared as
 * {@code ExceptionMapper<PersistenceException>}, and a deadlock raised at <em>JTA commit</em>
 * happens after the resource method has already returned. Arc wraps it as an
 * {@code ArcUndeclaredThrowableException}, which is not a {@code PersistenceException}, so JAX-RS
 * never selects that mapper and {@link GenericExceptionMapper} answers 500 instead.
 *
 * <p>That is not academic: on 2026-08-07 invoice 77ee648a was booked at e-conomic (201,
 * bookedNumber 28214) and the local transaction then died on a 1213 at commit. The caller saw a
 * 500 — indistinguishable from "nothing happened" — and retried, which is how the invoice ended up
 * booked twice.
 *
 * <p><b>Where the real exception hides.</b> Verified against narayana-jta 7.3.4.Final rather than
 * assumed. When a {@code beforeCompletion} synchronization throws — which is where Hibernate's
 * flush runs — {@code TwoPhaseCoordinator.beforeCompletion:360-364} stores it verbatim as
 * {@code _deferredThrowable}. {@code TransactionImple.commitAndDisassociate:1327-1346} then builds
 * the {@code RollbackException} through {@code addSuppressedThrowables} (which
 * {@code addSuppressed}es every deferred throwable, {@code :222-228}) and, on the
 * {@code ABORTED}/{@code H_ROLLBACK} branch, additionally {@code initCause}s it. So on today's path
 * the Hibernate exception is reachable <b>both</b> as a cause and as a suppressed throwable — but
 * the {@code H_MIXED}, {@code H_HAZARD} and invalid-state branches never call {@code initCause},
 * and there suppressed is the only carrier. The shared predicate walks both.
 *
 * <p><b>Scope.</b> This mapper is narrow by construction. Every exception of this type falls
 * through to {@code GenericExceptionMapper}'s 500 today; here, lock contention becomes a retriable
 * 409 and anything else wrapping a {@code PersistenceException} gets that mapper's verdict, while
 * everything else keeps the identical 500 body. No other error path changes.
 */
@Provider
@JBossLog
public class ArcUndeclaredThrowableExceptionMapper
        implements ExceptionMapper<ArcUndeclaredThrowableException> {

    @Inject
    DatabaseConstraintViolationExceptionMapper persistenceMapper;

    @Override
    public Response toResponse(ArcUndeclaredThrowableException exception) {
        // Checked FIRST and independently of the PersistenceException lookup below: the predicate
        // walks cause AND suppressed and also matches on SQLSTATE 40001 / MariaDB 1205 / 1213, so
        // it still fires if the wrapping ever changes shape and no PersistenceException survives.
        // There is exactly ONE definition of this predicate — see the javadoc on the method.
        if (DatabaseConstraintViolationExceptionMapper.isTransientLockContention(exception)) {
            log.warnf("Commit-phase DB lock contention -> 409: %s", exception.toString());
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse(
                            DatabaseConstraintViolationExceptionMapper.LOCK_CONTENTION_MESSAGE,
                            Response.Status.CONFLICT.getStatusCode()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        PersistenceException persistence = DatabaseConstraintViolationExceptionMapper
                .findCause(exception, PersistenceException.class);
        if (persistence != null) {
            log.warnf("Unwrapped ArcUndeclaredThrowableException -> %s",
                    persistence.getClass().getName());
            return persistenceMapper.toResponse(persistence);
        }

        log.error("Unhandled exception in REST resource", exception);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new ErrorResponse("Internal server error", 500))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
