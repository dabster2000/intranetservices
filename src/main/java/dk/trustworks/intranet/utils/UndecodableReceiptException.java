package dk.trustworks.intranet.utils;

import java.io.IOException;

/**
 * Signals that receipt bytes cannot be decoded as an image supported by the upload pipeline.
 * This is an item-level data-quality problem, distinct from general upload or I/O failures.
 */
public final class UndecodableReceiptException extends IOException {

    public static final String DEFAULT_MESSAGE =
            "Receipt is not a decodable image (e.g. PDF, HEIC, or corrupt file); " +
            "cannot compress for e-conomic upload";

    public UndecodableReceiptException() {
        super(DEFAULT_MESSAGE);
    }

    public UndecodableReceiptException(Throwable cause) {
        super(DEFAULT_MESSAGE, cause);
    }
}
