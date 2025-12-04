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
import java.util.Locale;
import java.util.Map;

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
            LOG.errorf(e, "Failed to generate PDF from template");
            throw new DocumentPdfException("Failed to generate PDF from template", e);
        }
    }

    /**
     * Creates a Thymeleaf context populated with all form values.
     */
    private Context createTemplateContext(Map<String, String> formValues) {
        Context ctx = new Context(DANISH_LOCALE);
        if (formValues != null) {
            formValues.forEach(ctx::setVariable);
        }
        return ctx;
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
     * Converts rendered HTML to PDF using OpenHTMLToPDF.
     *
     * <p><strong>HTML-to-XML Sanitization (Reverse Strategy):</strong>
     * OpenHTMLToPDF uses a strict XML parser that requires proper entity encoding.
     * This method uses a robust reverse sanitization approach:
     * <ol>
     *   <li>Escape ALL ampersands: & to &amp;amp; (catches everything including incomplete entity refs)</li>
     *   <li>Un-escape numeric entities we specifically want: &amp;amp;#160; to &amp;#160;</li>
     *   <li>Replace named entities with their numeric equivalents for compatibility</li>
     * </ol>
     *
     * @param html The HTML string from Thymeleaf template
     * @return PDF binary data as byte array
     * @throws IOException If PDF conversion fails
     */
    private byte[] convertHtmlToPdf(String html) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            // Step 1: Escape ALL ampersands (catches everything including incomplete entities)
            String sanitizedHtml = html.replace("&", "&amp;");

            // Step 2: Replace HTML named entities with numeric references
            // These will become &amp;nbsp; after step 1, so we need to un-escape them
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

            // Step 3: Un-escape numeric entities (they came from Thymeleaf as &#160;, not &amp;#160;)
            // This ensures numeric entities already in template stay valid
            // Pattern: &amp;#(number); to &#(number);
            sanitizedHtml = sanitizedHtml.replaceAll("&amp;(#\\d+;)", "$1");

            // Build and execute PDF conversion
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(sanitizedHtml, null);
            builder.toStream(baos);
            builder.run();

            return baos.toByteArray();
        } catch (Exception e) {
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
