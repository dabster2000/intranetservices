package dk.trustworks.intranet.utils.services;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Generic service for generating PDFs from dynamic HTML/Thymeleaf templates.
 * <p>
 * Unlike {@link dk.trustworks.intranet.utils.EmploymentContractPdfService} which uses
 * ClassLoaderTemplateResolver for file-based templates, this service uses
 * StringTemplateResolver to accept template content directly as a string.
 * This enables dynamic, user-defined templates to be rendered at runtime.
 * </p>
 * <p>
 * Uses Thymeleaf for template processing and OpenHTMLToPDF for PDF rendering.
 * </p>
 *
 * @see org.thymeleaf.templateresolver.StringTemplateResolver
 * @see com.openhtmltopdf.pdfboxout.PdfRendererBuilder
 */
@ApplicationScoped
public class DocumentPdfService {

    private static final Logger LOG = Logger.getLogger(DocumentPdfService.class);
    private static final Locale DANISH_LOCALE = Locale.forLanguageTag("da-DK");

    // Date format patterns
    /** Pattern to detect ISO date format: yyyy-MM-dd */
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    /** Parser for ISO dates */
    private static final DateTimeFormatter ISO_DATE_PARSER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** Danish long date format: "4. december 2025" */
    private static final DateTimeFormatter DANISH_DATE_FORMAT =
            DateTimeFormatter.ofPattern("d. MMMM yyyy", DANISH_LOCALE);

    /**
     * CSS override block injected into templates to fix common PDF rendering issues.
     * Uses high specificity to override problematic styles without breaking valid CSS.
     */
    private static final String PDF_CSS_OVERRIDES = """
        <style type="text/css">
        /* PDF CSS Overrides - Injected by DocumentPdfService */
        /* Fix text clipping: disable justify (causes character spacing overflow in PDF) */
        p, div, span, td, th, li { text-align: left !important; }
        /* Ensure text wraps instead of overflowing */
        p, div, span, td, th { word-wrap: break-word; overflow-wrap: break-word; }
        /* Fix max-width conflicts: 170mm = A4 (210mm) minus safe margins */
        .page-container, .document-container, .content { max-width: 170mm !important; }
        /* Prevent fixed widths from causing overflow */
        body { padding: 0 !important; margin: 0 !important; }
        </style>
        """;

    private TemplateEngine templateEngine;

    /**
     * Initializes the Thymeleaf TemplateEngine with StringTemplateResolver.
     * <p>
     * Configuration:
     * <ul>
     *   <li>Resolver: StringTemplateResolver for dynamic template content</li>
     *   <li>Mode: HTML5</li>
     *   <li>Caching: Disabled (each template is unique)</li>
     * </ul>
     */
    @PostConstruct
    void initializeTemplateEngine() {
        LOG.info("Initializing Thymeleaf TemplateEngine with StringTemplateResolver for dynamic PDF generation");

        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCacheable(false); // Templates are dynamic, caching not beneficial

        templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        LOG.info("DocumentPdfService TemplateEngine initialized");
    }

    /**
     * Generates a PDF from the provided HTML/Thymeleaf template and form values.
     * <p>
     * Process:
     * <ol>
     *   <li>Populates Thymeleaf context with all form values</li>
     *   <li>Renders HTML from the provided template content</li>
     *   <li>Converts HTML to PDF using OpenHTMLToPDF with fast mode enabled</li>
     * </ol>
     *
     * @param templateContent HTML/Thymeleaf template content (e.g., containing [[${fieldName}]] placeholders)
     * @param formValues      Key-value pairs to populate template placeholders
     * @return byte array containing the generated PDF
     * @throws DocumentPdfException if PDF generation fails
     * @throws IllegalArgumentException if templateContent is null or blank
     */
    public byte[] generatePdfFromTemplate(String templateContent, Map<String, String> formValues) {
        if (templateContent == null || templateContent.isBlank()) {
            throw new IllegalArgumentException("Template content cannot be null or blank");
        }

        LOG.debugf("Generating PDF from template (%d chars) with %d form values",
            templateContent.length(),
            formValues != null ? formValues.size() : 0);

        try {
            // 1) Build Thymeleaf context with all form values
            Context ctx = createTemplateContext(formValues);

            // 2) Render HTML from template content
            String html = renderTemplate(templateContent, ctx);

            // 3) Convert HTML to PDF with OpenHTMLToPDF
            byte[] pdf = convertHtmlToPdf(html);

            LOG.infof("Successfully generated PDF from template (%d bytes)", pdf.length);

            return pdf;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to generate PDF from template. Template size: %d chars, Form values: %d",
                       templateContent.length(),
                       formValues != null ? formValues.size() : 0);
            throw new DocumentPdfException("Failed to generate PDF from template", e);
        }
    }

    /**
     * Creates a Thymeleaf context populated with all form values.
     * <p>
     * Automatically converts ISO date strings (yyyy-MM-dd) to Danish long format (d. MMMM yyyy).
     * </p>
     */
    private Context createTemplateContext(Map<String, String> formValues) {
        Context ctx = new Context(DANISH_LOCALE);
        if (formValues != null) {
            Map<String, String> convertedValues = convertDatesToDisplayFormat(formValues);
            convertedValues.forEach(ctx::setVariable);
        }
        return ctx;
    }

    /**
     * Converts ISO date values (yyyy-MM-dd) to Danish display format (d. MMMM yyyy).
     * <p>
     * Scans all form values and converts any that match the ISO date pattern.
     * Non-date values are passed through unchanged.
     * </p>
     *
     * @param formValues The original form values
     * @return A new map with date values converted to Danish format
     */
    private Map<String, String> convertDatesToDisplayFormat(Map<String, String> formValues) {
        Map<String, String> result = new HashMap<>(formValues.size());

        for (Map.Entry<String, String> entry : formValues.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (value != null && ISO_DATE_PATTERN.matcher(value).matches()) {
                try {
                    LocalDate date = LocalDate.parse(value, ISO_DATE_PARSER);
                    String formatted = date.format(DANISH_DATE_FORMAT);
                    result.put(key, formatted);
                    LOG.debugf("Converted date '%s' from ISO (%s) to Danish format (%s)", key, value, formatted);
                } catch (DateTimeParseException e) {
                    // If parsing fails, keep original value
                    LOG.warnf("Failed to parse date '%s' value '%s': %s", key, value, e.getMessage());
                    result.put(key, value);
                }
            } else {
                result.put(key, value);
            }
        }

        return result;
    }

    /**
     * Renders the provided template content using Thymeleaf.
     *
     * @param templateContent The HTML/Thymeleaf template as a string
     * @param ctx            The Thymeleaf context with variables
     * @return Rendered HTML string
     */
    private String renderTemplate(String templateContent, Context ctx) {
        try {
            return templateEngine.process(templateContent, ctx);
        } catch (Exception e) {
            throw new DocumentPdfException("Failed to render Thymeleaf template", e);
        }
    }

    /**
     * Normalizes HTML5 DOCTYPE declarations to XHTML format for OpenHTMLToPDF compatibility.
     * <p>
     * OpenHTMLToPDF requires strict XHTML, so we convert lowercase HTML5 DOCTYPE
     * to uppercase format. This helps avoid SAXParseException errors.
     * </p>
     *
     * @param html The HTML content with potential HTML5 DOCTYPE
     * @return HTML with normalized DOCTYPE declaration
     */
    private String normalizeDoctype(String html) {
        // Replace case-insensitive <!doctype html> with uppercase version
        // OpenHTMLToPDF is more lenient with uppercase DOCTYPE
        String doctypePattern = "(?i)<!doctype\\s+html>";
        return html.replaceFirst(doctypePattern, "<!DOCTYPE html>");
    }

    /**
     * Fixes self-closing tags to ensure proper XML format for OpenHTMLToPDF.
     * <p>
     * Adds space before /&gt; in self-closing tags: &lt;br/&gt; becomes &lt;br /&gt;
     * This is required for XHTML compliance and prevents XML parsing errors.
     * </p>
     *
     * @param html The HTML content with potential malformed self-closing tags
     * @return HTML with properly formatted self-closing tags
     */
    private String fixSelfClosingTags(String html) {
        // Fix self-closing tags to have space before />
        html = html.replaceAll("<br/>", "<br />");
        html = html.replaceAll("<hr/>", "<hr />");
        html = html.replaceAll("<img([^>]*)/>", "<img$1 />");
        html = html.replaceAll("<input([^>]*)/>", "<input$1 />");
        html = html.replaceAll("<meta([^>]*)/>", "<meta$1 />");
        html = html.replaceAll("<link([^>]*)/>", "<link$1 />");

        return html;
    }

    /**
     * Injects CSS override block into HTML to fix common PDF rendering issues.
     * <p>
     * Instead of using fragile regex replacements on existing CSS, this method injects
     * a CSS override block with high-specificity rules that override problematic styles.
     * The block is injected just before &lt;/head&gt; to ensure it takes precedence.
     * </p>
     * <p>
     * This approach is more robust because:
     * <ul>
     *   <li>Uses CSS cascade instead of text manipulation</li>
     *   <li>Doesn't risk breaking valid CSS patterns</li>
     *   <li>Works regardless of original CSS formatting/whitespace</li>
     *   <li>High-specificity selectors ensure overrides apply</li>
     * </ul>
     *
     * @param html The HTML content to inject CSS overrides into
     * @return HTML with CSS override block injected before &lt;/head&gt;
     */
    private String injectPdfCssOverrides(String html) {
        // Find </head> and inject our CSS overrides just before it
        int headEndIndex = html.toLowerCase().indexOf("</head>");
        if (headEndIndex > 0) {
            html = html.substring(0, headEndIndex) + PDF_CSS_OVERRIDES + html.substring(headEndIndex);
            LOG.debugf("Injected PDF CSS overrides before </head>");
        } else {
            // No </head> found - inject at start of <body> instead
            int bodyIndex = html.toLowerCase().indexOf("<body");
            if (bodyIndex > 0) {
                int bodyTagEnd = html.indexOf(">", bodyIndex);
                if (bodyTagEnd > 0) {
                    html = html.substring(0, bodyTagEnd + 1) + PDF_CSS_OVERRIDES + html.substring(bodyTagEnd + 1);
                    LOG.debugf("Injected PDF CSS overrides after <body>");
                }
            } else {
                // Last resort: prepend to document
                html = PDF_CSS_OVERRIDES + html;
                LOG.warnf("No <head> or <body> found - prepended CSS overrides to document");
            }
        }
        return html;
    }

    /**
     * Converts rendered HTML to PDF using OpenHTMLToPDF.
     *
     * <p><strong>HTML-to-XML Sanitization (Reverse Strategy):</strong>
     * OpenHTMLToPDF uses a strict XML parser that requires proper entity encoding.
     * This method uses a robust reverse sanitization approach:
     * <ol>
     *   <li>Remove BOM and trim whitespace</li>
     *   <li>Normalize DOCTYPE to XHTML-compliant format</li>
     *   <li>Fix self-closing tags to include space before /&gt;</li>
     *   <li>Inject CSS overrides to prevent text clipping and overflow</li>
     *   <li>Escape ALL ampersands: & to &amp;amp; (catches everything including incomplete entity refs)</li>
     *   <li>Replace named entities with numeric equivalents for compatibility</li>
     *   <li>Un-escape numeric entities we specifically want: &amp;amp;#160; to &amp;#160;</li>
     * </ol>
     *
     * @param html The HTML string from Thymeleaf template
     * @return PDF binary data as byte array
     * @throws IOException If PDF conversion fails
     */
    private byte[] convertHtmlToPdf(String html) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            // Step 1: Remove BOM (Byte Order Mark) and trim whitespace
            html = html.trim();
            if (html.startsWith("\uFEFF")) {
                html = html.substring(1);
                LOG.debugf("Removed BOM from HTML content");
            }

            // Step 2: Normalize DOCTYPE to XHTML-compliant format
            html = normalizeDoctype(html);

            // Step 3: Fix self-closing tags (br/, img/, etc. → br /, img /, etc.)
            html = fixSelfClosingTags(html);

            // Step 4: Inject CSS overrides to fix text clipping in PDFs
            html = injectPdfCssOverrides(html);

            // Step 5: Escape ALL ampersands (catches everything including incomplete entities)
            String sanitizedHtml = html.replace("&", "&amp;");

            // Step 6: Replace HTML named entities with numeric references
            // These will become &amp;nbsp; after step 5, so we need to un-escape them
            sanitizedHtml = sanitizedHtml
                .replace("&amp;nbsp;", "&#160;")           // Non-breaking space
                .replace("&amp;copy;", "&#169;")           // Copyright symbol
                .replace("&amp;reg;", "&#174;")            // Registered trademark
                .replace("&amp;trade;", "&#8482;")         // Trademark symbol
                .replace("&amp;euro;", "&#8364;")          // Euro sign
                .replace("&amp;pound;", "&#163;")          // Pound sign
                .replace("&amp;yen;", "&#165;")            // Yen sign
                .replace("&amp;sect;", "&#167;")           // Section sign
                .replace("&amp;para;", "&#182;")           // Pilcrow
                .replace("&amp;mdash;", "&#8212;")         // Em dash
                .replace("&amp;ndash;", "&#8211;")         // En dash
                .replace("&amp;laquo;", "&#171;")          // Left angle quotation
                .replace("&amp;raquo;", "&#187;")          // Right angle quotation
                .replace("&amp;bull;", "&#8226;")          // Bullet
                .replace("&amp;hellip;", "&#8230;")        // Horizontal ellipsis
                .replace("&amp;lt;", "&#60;")              // Less-than
                .replace("&amp;gt;", "&#62;")              // Greater-than
                .replace("&amp;quot;", "&#34;")            // Quotation mark
                .replace("&amp;apos;", "&#39;");           // Apostrophe

            // Step 7: Un-escape numeric entities (they came from Thymeleaf as &#160;, not &amp;#160;)
            // This ensures numeric entities already in template stay valid
            // Pattern: &amp;#(number); to &#(number);
            sanitizedHtml = sanitizedHtml.replaceAll("&amp;(#\\d+;)", "$1");

            // Step 8: Build and execute PDF conversion
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(sanitizedHtml, null);
            builder.toStream(baos);
            builder.run();

            return baos.toByteArray();
        } catch (Exception e) {
            // Enhanced error logging with HTML preview
            String preview = html.length() > 1000
                ? html.substring(0, 1000) + "\n...[truncated at 1000 chars]"
                : html;
            LOG.errorf("PDF conversion failed.\n=== HTML Preview (first 1000 chars) ===\n%s\n=== Error ===\n%s",
                       preview, e.getMessage());

            throw new IOException("Failed to convert HTML to PDF using OpenHTMLToPDF", e);
        }
    }

    /**
     * Exception for document PDF generation failures.
     */
    public static class DocumentPdfException extends RuntimeException {
        public DocumentPdfException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
