package dk.trustworks.intranet.recruitmentservice.model.exception;

/**
 * Thrown by recruitment-domain aggregates when a caller attempts an operation
 * that is forbidden by the aggregate's current state — e.g. declining a
 * candidate that is already terminal, or allocating a revision on a closed
 * dossier.
 * <p>
 * This is a {@link RuntimeException} so it bubbles out of
 * {@code @Transactional} application service methods and rolls back the
 * surrounding transaction. The REST layer (Stage 2) will translate it into
 * an HTTP 409 Conflict response.
 */
public class BusinessRuleViolation extends RuntimeException {

    public BusinessRuleViolation(String message) {
        super(message);
    }

    public BusinessRuleViolation(String message, Throwable cause) {
        super(message, cause);
    }
}
