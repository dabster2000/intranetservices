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
     * <p>
     * <b>Important:</b> OpenHTMLToPDF uses an XML parser that doesn't recognize HTML entities
     * like {@code &nbsp;} without DTD declarations. We replace common HTML entities with their
     * numeric character references (e.g., {@code &#160;}) which are always valid in XML.
     * <p>
     * Configuration: Fast mode enabled, no base URI (assuming no external resources).
     *
     * @param html The HTML string to convert (from Thymeleaf template)
     * @return PDF binary data as byte array
     * @throws IOException If PDF conversion fails
     */
    private byte[] convertHtmlToPdf(String html) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            // Replace common HTML entities with numeric character references
            // OpenHTMLToPDF requires numeric entities or actual Unicode characters
            String sanitizedHtml = html
                .replace("&nbsp;", "&#160;")     // Non-breaking space
                .replace("&copy;", "&#169;")     // Copyright symbol
                .replace("&reg;", "&#174;")      // Registered trademark
                .replace("&trade;", "&#8482;")   // Trademark symbol
                .replace("&euro;", "&#8364;")    // Euro sign
                .replace("&pound;", "&#163;")    // Pound sign
                .replace("&yen;", "&#165;")      // Yen sign
                .replace("&sect;", "&#167;")     // Section sign
                .replace("&para;", "&#182;")     // Pilcrow (paragraph sign)
                .replace("&mdash;", "&#8212;")   // Em dash
                .replace("&ndash;", "&#8211;")   // En dash
                .replace("&laquo;", "&#171;")    // Left-pointing double angle quotation mark
                .replace("&raquo;", "&#187;")    // Right-pointing double angle quotation mark
                .replace("&bull;", "&#8226;")    // Bullet
                .replace("&hellip;", "&#8230;"); // Horizontal ellipsis

            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(sanitizedHtml, null); // Use sanitized HTML, baseUri = null
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
