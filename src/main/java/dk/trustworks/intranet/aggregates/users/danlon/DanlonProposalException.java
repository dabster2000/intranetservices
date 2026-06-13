package dk.trustworks.intranet.aggregates.users.danlon;

/** Thrown when an HR approval/rejection action cannot proceed. Mapped to 409
 * (or 404 for "not found") at the resource layer. Spec §11: fail loudly,
 * never swallow. */
public class DanlonProposalException extends RuntimeException {
    public DanlonProposalException(String message) { super(message); }
}
