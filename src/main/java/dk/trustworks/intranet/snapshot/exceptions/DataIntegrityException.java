package dk.trustworks.intranet.snapshot.exceptions;

/**
 * Exception thrown when data integrity validation fails.
 * Specifically for checksum mismatches or corrupted snapshot data.
 */
public class DataIntegrityException extends SnapshotException {

    public DataIntegrityException(String message) {
        super(message);
    }

    public DataIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
