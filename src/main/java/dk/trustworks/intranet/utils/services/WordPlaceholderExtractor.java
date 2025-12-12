package dk.trustworks.intranet.utils.services;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;
import org.apache.poi.xwpf.usermodel.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service to extract placeholders from Word documents (.docx files).
 *
 * <p>Placeholders use the syntax: {{PLACEHOLDER_KEY}}
 * <ul>
 *   <li>Double curly braces as delimiters</li>
 *   <li>Uppercase letters, numbers, and underscores only</li>
 *   <li>Case-sensitive matching</li>
 * </ul>
 *
 * <p>Example placeholders:
 * <ul>
 *   <li>{{EMPLOYEE_NAME}}</li>
 *   <li>{{START_DATE}}</li>
 *   <li>{{SALARY_AMOUNT}}</li>
 * </ul>
 *
 * <p>The extractor scans all parts of the Word document:
 * <ul>
 *   <li>Main body paragraphs</li>
 *   <li>Tables (all cells)</li>
 *   <li>Headers</li>
 *   <li>Footers</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class WordPlaceholderExtractor {

    /**
     * Regex pattern for poi-tl style placeholders.
     * Matches: {{PLACEHOLDER_KEY}} where key contains only uppercase letters, digits, underscores.
     */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{([A-Z][A-Z0-9_]*)\\}\\}");

    /**
     * Extracts all unique placeholders from a Word document.
     *
     * @param docxBytes The Word document as byte array
     * @return Set of placeholder keys found in the document (without curly braces)
     */
    public Set<String> extractPlaceholders(byte[] docxBytes) {
        if (docxBytes == null || docxBytes.length == 0) {
            log.debug("Empty document bytes provided, returning empty set");
            return Set.of();
        }

        Set<String> placeholders = new LinkedHashSet<>();

        try (ByteArrayInputStream bais = new ByteArrayInputStream(docxBytes);
             XWPFDocument document = new XWPFDocument(bais)) {

            // Extract from main body paragraphs
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                extractFromText(paragraph.getText(), placeholders);
            }

            // Extract from tables
            for (XWPFTable table : document.getTables()) {
                extractFromTable(table, placeholders);
            }

            // Extract from headers
            for (XWPFHeader header : document.getHeaderList()) {
                for (XWPFParagraph paragraph : header.getParagraphs()) {
                    extractFromText(paragraph.getText(), placeholders);
                }
                for (XWPFTable table : header.getTables()) {
                    extractFromTable(table, placeholders);
                }
            }

            // Extract from footers
            for (XWPFFooter footer : document.getFooterList()) {
                for (XWPFParagraph paragraph : footer.getParagraphs()) {
                    extractFromText(paragraph.getText(), placeholders);
                }
                for (XWPFTable table : footer.getTables()) {
                    extractFromTable(table, placeholders);
                }
            }

            log.infof("Extracted %d placeholders from Word document", placeholders.size());

        } catch (IOException e) {
            log.errorf(e, "Failed to read Word document for placeholder extraction");
        } catch (Exception e) {
            log.errorf(e, "Unexpected error during placeholder extraction");
        }

        return Collections.unmodifiableSet(placeholders);
    }

    /**
     * Extracts placeholders from table cells recursively.
     */
    private void extractFromTable(XWPFTable table, Set<String> placeholders) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                // Extract from cell text
                extractFromText(cell.getText(), placeholders);

                // Handle nested tables
                for (XWPFTable nestedTable : cell.getTables()) {
                    extractFromTable(nestedTable, placeholders);
                }
            }
        }
    }

    /**
     * Extracts placeholders from a text string using regex.
     */
    private void extractFromText(String text, Set<String> placeholders) {
        if (text == null || text.isBlank()) {
            return;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            String key = matcher.group(1);
            placeholders.add(key);
            log.debugf("Found placeholder: %s", key);
        }
    }

    /**
     * Validates if a string is a valid placeholder name.
     *
     * @param name The placeholder name to validate
     * @return true if the name is valid (uppercase, alphanumeric with underscores)
     */
    public boolean isValidPlaceholderName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return name.matches("[A-Z][A-Z0-9_]*");
    }

    /**
     * Counts the total number of placeholder occurrences in a document.
     * Note: This counts occurrences, not unique placeholders.
     *
     * @param docxBytes The Word document as byte array
     * @return Total count of placeholder occurrences
     */
    public int countPlaceholderOccurrences(byte[] docxBytes) {
        if (docxBytes == null || docxBytes.length == 0) {
            return 0;
        }

        int count = 0;
        StringBuilder fullText = new StringBuilder();

        try (ByteArrayInputStream bais = new ByteArrayInputStream(docxBytes);
             XWPFDocument document = new XWPFDocument(bais)) {

            // Collect all text
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                fullText.append(paragraph.getText()).append("\n");
            }
            for (XWPFTable table : document.getTables()) {
                fullText.append(table.getText()).append("\n");
            }

            // Count matches
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(fullText.toString());
            while (matcher.find()) {
                count++;
            }

        } catch (Exception e) {
            log.errorf(e, "Failed to count placeholders in Word document");
        }

        return count;
    }
}
