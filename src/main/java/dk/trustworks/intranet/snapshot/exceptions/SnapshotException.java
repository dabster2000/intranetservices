package dk.trustworks.intranet.snapshot.exceptions;

/**
 * Generic exception for snapshot operations.
 * Thrown when snapshot creation, serialization, or validation fails.
 */
public class SnapshotException extends RuntimeException {

    public SnapshotException(String message) {
        super(message);
    }

    public SnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
