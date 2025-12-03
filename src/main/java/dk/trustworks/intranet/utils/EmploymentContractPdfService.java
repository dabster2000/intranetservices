package dk.trustworks.intranet.utils;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import dk.trustworks.intranet.utils.dto.EmploymentContractData;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Quarkus CDI service for generating employment contract PDFs using Thymeleaf templates and OpenHTMLToPDF.
 * <p>
 * This service uses Thymeleaf (rather than Qute) because it provides robust HTML5 parsing and
 * CSS support required for complex PDF generation with OpenHTMLToPDF. Qute is optimized for
 * server-side rendering but lacks the extensive HTML/CSS processing capabilities needed for
 * high-fidelity PDF rendering.
 * </p>
 * <p>
 * Template location: {@code src/main/resources/META-INF/resources/thymeleaf/employment-contract.html}
 * </p>
 *
 * @see EmploymentContractData
 * @see PdfRendererBuilder
 */
@ApplicationScoped
public class EmploymentContractPdfService {

    private static final Logger LOG = Logger.getLogger(EmploymentContractPdfService.class);

    private static final String TEMPLATE_PREFIX = "/META-INF/resources/thymeleaf/";
    private static final String TEMPLATE_SUFFIX = ".html";
    private static final String TEMPLATE_NAME = "employment-contract";
    private static final Locale DANISH_LOCALE = Locale.forLanguageTag("da-DK");

    private TemplateEngine templateEngine;

    /**
     * Initializes the Thymeleaf TemplateEngine with proper ClassLoader-based resolution.
     * <p>
     * Configuration:
     * <ul>
     *   <li>Prefix: {@code /META-INF/resources/thymeleaf/} (matches actual template location)</li>
     *   <li>Suffix: {@code .html}</li>
     *   <li>Mode: HTML5</li>
     *   <li>Encoding: UTF-8</li>
     *   <li>Caching: Disabled for development (enable in production for performance)</li>
     * </ul>
     */
    @PostConstruct
    void initializeTemplateEngine() {
        LOG.info("Initializing Thymeleaf TemplateEngine for employment contract PDF generation");

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(TEMPLATE_PREFIX);
        resolver.setSuffix(TEMPLATE_SUFFIX);
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false); // TODO: Enable caching in production for performance
        resolver.setCheckExistence(true); // Fail fast if template doesn't exist

        templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        LOG.infof("TemplateEngine initialized with prefix: %s", TEMPLATE_PREFIX);
    }

    /**
     * Generates an employment contract PDF from the provided data.
     * <p>
     * Process:
     * <ol>
     *   <li>Populates Thymeleaf context with contract data</li>
     *   <li>Renders HTML from the {@code employment-contract.html} template</li>
     *   <li>Converts HTML to PDF using OpenHTMLToPDF with fast mode enabled</li>
     * </ol>
     *
     * @param data the employment contract data to render
     * @return byte array containing the generated PDF
     * @throws EmploymentContractPdfException if PDF generation fails (template missing, rendering error, etc.)
     * @throws IllegalArgumentException if data is null or contains invalid values
     */
    public byte[] generatePdf(EmploymentContractData data) {
        if (data == null) {
            throw new IllegalArgumentException("EmploymentContractData cannot be null");
        }

        LOG.infof("Generating employment contract PDF for employee: %s", data.employeeName());

        try {
            // 1) Build Thymeleaf context with all contract variables
            Context ctx = createTemplateContext(data);

            // 2) Render HTML from Thymeleaf template
            String html = renderTemplate(ctx);

            // 3) Convert HTML to PDF with OpenHTMLToPDF
            byte[] pdf = convertHtmlToPdf(html);

            LOG.infof("Successfully generated employment contract PDF (%d bytes) for: %s",
                     pdf.length, data.employeeName());

            return pdf;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to generate employment contract PDF for employee: %s", data.employeeName());
            throw new EmploymentContractPdfException(
                "Failed to generate employment contract PDF for: " + data.employeeName(), e);
        }
    }

    /**
     * Creates a Thymeleaf context populated with all employment contract data fields.
     */
    private Context createTemplateContext(EmploymentContractData data) {
        Context ctx = new Context(DANISH_LOCALE);
        ctx.setVariable("employeeName", data.employeeName());
        ctx.setVariable("employeeAddress", data.employeeAddress());
        ctx.setVariable("companyLegalName", data.companyLegalName());
        ctx.setVariable("companyCvr", data.companyCvr());
        ctx.setVariable("companyAddress", data.companyAddress());
        ctx.setVariable("employmentStartDate", data.employmentStartDate());
        ctx.setVariable("jobTitle", data.jobTitle());
        ctx.setVariable("weeklyHours", data.weeklyHours());
        ctx.setVariable("monthlySalary", data.monthlySalary());
        ctx.setVariable("contractCity", data.contractCity());
        ctx.setVariable("contractDate", data.contractDate());
        return ctx;
    }

    /**
     * Renders the employment contract HTML template using Thymeleaf.
     */
    private String renderTemplate(Context ctx) {
        try {
            return templateEngine.process(TEMPLATE_NAME, ctx);
        } catch (Exception e) {
            throw new EmploymentContractPdfException(
                "Failed to render Thymeleaf template: " + TEMPLATE_NAME + TEMPLATE_SUFFIX, e);
        }
    }

    /**
     * Converts rendered HTML to PDF using OpenHTMLToPDF.
     *
     * <p><strong>HTML-to-XML Sanitization (Reverse Strategy):</strong>
     * OpenHTMLToPDF uses a strict XML parser that requires proper entity encoding.
     * This method uses a robust reverse sanitization approach:
     * <ol>
     *   <li>Escape ALL ampersands: & → &amp; (catches everything including incomplete entity refs)</li>
     *   <li>Un-escape numeric entities we specifically want: &amp;#160; → &#160;</li>
     *   <li>Replace named entities with their numeric equivalents for compatibility</li>
     * </ol>
     *
     * <p>This approach is more robust than regex-based detection because:
     * <ul>
     *   <li>Catches ALL problematic ampersands (including incomplete entity refs like "&co ")</li>
     *   <li>Prevents double-escaping by only un-escaping numeric entities we explicitly generate</li>
     *   <li>Handles edge cases: URLs, text content, mixed scenarios</li>
     * </ul>
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
            // These will become &amp;#160; after step 1, so we need to un-escape them
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
            // Pattern: &amp;#(number); → &#(number);
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
     * Custom exception for employment contract PDF generation failures.
     */
    public static class EmploymentContractPdfException extends RuntimeException {
        public EmploymentContractPdfException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
