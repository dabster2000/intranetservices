package dk.trustworks.intranet.utils.converter;

import java.util.Map;

/**
 * Strategy interface for Word-to-PDF conversion with placeholder replacement.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link LocalWordToPdfConverter} - Uses poi-tl + LibreOffice (default)</li>
 *   <li>{@link NextsignWordToPdfConverter} - Uses NextSign API (fallback)</li>
 * </ul>
 */
public interface WordToPdfConverter {

    /**
     * Converts a Word document (.docx) to PDF with placeholder replacement.
     *
     * <p>Placeholders in the document use the syntax: {{PLACEHOLDER_NAME}}
     * where PLACEHOLDER_NAME consists of uppercase letters, digits, and underscores.
     *
     * @param docxBytes    The Word document content as byte array
     * @param placeholders Map of placeholder names (without braces) to their values
     * @param documentName Display name for the output document
     * @return PDF document as byte array
     * @throws WordConversionException if conversion fails
     */
    byte[] convert(byte[] docxBytes, Map<String, Object> placeholders, String documentName);

    /**
     * Returns the name of this converter implementation.
     * Used for logging and diagnostics.
     */
    default String getName() {
        return getClass().getSimpleName();
    }
}
