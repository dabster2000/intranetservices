package dk.trustworks.intranet.utils.converter;

/**
 * Exception thrown when Word-to-PDF conversion fails.
 */
public class WordConversionException extends RuntimeException {

    public WordConversionException(String message) {
        super(message);
    }

    public WordConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
