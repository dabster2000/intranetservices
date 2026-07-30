package dk.trustworks.intranet.utils;

import java.io.IOException;

/**
 * Signals that receipt bytes cannot be turned into an attachment e-conomic accepts.
 * This is an item-level data-quality problem, distinct from general upload or I/O failures.
 * PDFs are NOT in this category — they pass through to e-conomic unmodified.
 */
public final class UndecodableReceiptException extends IOException {

    public static final String DEFAULT_MESSAGE =
            "Receipt file is corrupt or in an unsupported format; " +
            "cannot prepare it for e-conomic upload — re-upload the receipt as JPEG, PNG, or PDF";

    public static final String HEIC_MESSAGE =
            "Receipt is a HEIC/HEIF image, which e-conomic does not accept and the pipeline " +
            "cannot convert — re-upload the receipt as JPEG, PNG, or PDF";

    public UndecodableReceiptException() {
        super(DEFAULT_MESSAGE);
    }

    public UndecodableReceiptException(String message) {
        super(message);
    }

    public UndecodableReceiptException(Throwable cause) {
        super(DEFAULT_MESSAGE, cause);
    }
}
