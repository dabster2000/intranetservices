package dk.trustworks.intranet.utils.converter;

import com.deepoove.poi.XWPFTemplate;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Local Word-to-PDF converter using poi-tl for placeholder replacement
 * and LibreOffice headless for PDF conversion.
 *
 * <p>This converter:
 * <ul>
 *   <li>Uses poi-tl to replace {{PLACEHOLDER}} tags in Word documents</li>
 *   <li>Handles Word's run-fragmentation issue transparently</li>
 *   <li>Uses LibreOffice in headless mode for high-quality PDF conversion</li>
 *   <li>Limits concurrent conversions via semaphore (LibreOffice is memory-intensive)</li>
 * </ul>
 *
 * <p>Requires LibreOffice to be installed on the system.
 * Default path: /usr/bin/libreoffice (configurable via word-converter.libreoffice.path)
 */
@JBossLog
@ApplicationScoped
@IfBuildProperty(name = "word-converter.backend", stringValue = "local", enableIfMissing = true)
public class LocalWordToPdfConverter implements WordToPdfConverter {

    @Inject
    WordConverterConfig config;

    private Semaphore conversionSemaphore;

    @PostConstruct
    void init() {
        conversionSemaphore = new Semaphore(config.maxConcurrent());
        log.infof("LocalWordToPdfConverter initialized: maxConcurrent=%d, libreOfficePath=%s, timeout=%ds",
                config.maxConcurrent(),
                config.libreoffice().path(),
                config.libreoffice().timeoutSeconds());
    }

    @Override
    public byte[] convert(byte[] docxBytes, Map<String, Object> placeholders, String documentName) {
        if (docxBytes == null || docxBytes.length == 0) {
            throw new WordConversionException("Document content is empty");
        }

        log.infof("Starting local Word-to-PDF conversion: %s (%d bytes, %d placeholders)",
                documentName, docxBytes.length, placeholders != null ? placeholders.size() : 0);

        // Acquire semaphore permit
        boolean acquired = false;
        try {
            acquired = conversionSemaphore.tryAcquire(config.libreoffice().timeoutSeconds(), TimeUnit.SECONDS);
            if (!acquired) {
                throw new WordConversionException(
                        "Conversion queue full (max " + config.maxConcurrent() + " concurrent). Try again later.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WordConversionException("Conversion interrupted while waiting for permit", e);
        }

        Path tempDir = null;
        try {
            // Create unique temp directory for this conversion
            tempDir = Files.createTempDirectory("word-convert-" + UUID.randomUUID().toString().substring(0, 8) + "-");
            log.debugf("Created temp directory: %s", tempDir);

            // Step 1: Write original DOCX to temp file
            Path inputDocx = tempDir.resolve("input.docx");
            Files.write(inputDocx, docxBytes);

            // Step 2: Replace placeholders using poi-tl
            Path processedDocx = tempDir.resolve("processed.docx");
            replacePlaceholders(inputDocx, processedDocx, placeholders != null ? placeholders : Map.of());

            // Step 3: Convert to PDF using LibreOffice
            Path outputPdf = convertToPdf(processedDocx, tempDir);

            // Step 4: Read and return PDF bytes
            byte[] pdfBytes = Files.readAllBytes(outputPdf);
            log.infof("Successfully converted Word to PDF: %s -> %d bytes", documentName, pdfBytes.length);

            return pdfBytes;

        } catch (WordConversionException e) {
            throw e;
        } catch (Exception e) {
            log.errorf(e, "Word-to-PDF conversion failed: %s", e.getMessage());
            throw new WordConversionException("Conversion failed: " + e.getMessage(), e);
        } finally {
            // Release semaphore
            if (acquired) {
                conversionSemaphore.release();
            }

            // Cleanup temp directory
            if (tempDir != null) {
                cleanupTempDir(tempDir);
            }
        }
    }

    /**
     * Replaces {{PLACEHOLDER}} tags in the Word document using poi-tl.
     *
     * <p>Note: Requires Xalan on the classpath for Apache POI's XML transformer.
     * The xalan:xalan dependency is included in pom.xml.
     */
    private void replacePlaceholders(Path inputDocx, Path outputDocx, Map<String, Object> placeholders) {
        log.debugf("Replacing %d placeholders in Word document", placeholders.size());

        try (XWPFTemplate template = XWPFTemplate.compile(inputDocx.toFile())) {
            // poi-tl expects {{key}} syntax - it handles the braces internally
            template.render(placeholders);
            template.writeToFile(outputDocx.toString());
            log.debugf("Placeholders replaced, wrote processed document to: %s", outputDocx);
        } catch (Exception e) {
            throw new WordConversionException("Failed to replace placeholders: " + e.getMessage(), e);
        }
    }

    /**
     * Converts DOCX to PDF using LibreOffice headless.
     *
     * @return Path to the generated PDF file
     */
    private Path convertToPdf(Path docxPath, Path outputDir) {
        String libreOfficePath = config.libreoffice().path();
        int timeoutSeconds = config.libreoffice().timeoutSeconds();

        log.debugf("Converting to PDF using LibreOffice: %s", libreOfficePath);

        // Verify LibreOffice exists
        if (!Files.exists(Path.of(libreOfficePath))) {
            throw new WordConversionException(
                    "LibreOffice not found at: " + libreOfficePath +
                            ". Install LibreOffice or configure word-converter.libreoffice.path");
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    libreOfficePath,
                    "--headless",
                    "--convert-to", "pdf",
                    "--outdir", outputDir.toString(),
                    docxPath.toString()
            );
            pb.redirectErrorStream(true);
            pb.directory(outputDir.toFile());

            Process process = pb.start();

            // Capture output for debugging
            String output = new String(process.getInputStream().readAllBytes());

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new WordConversionException(
                        "LibreOffice conversion timed out after " + timeoutSeconds + " seconds");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.errorf("LibreOffice failed with exit code %d. Output: %s", exitCode, output);
                throw new WordConversionException(
                        "LibreOffice conversion failed with exit code: " + exitCode);
            }

            // Find the output PDF (LibreOffice names it based on input filename)
            String pdfFilename = docxPath.getFileName().toString().replace(".docx", ".pdf");
            Path pdfPath = outputDir.resolve(pdfFilename);

            if (!Files.exists(pdfPath)) {
                // Try to find any PDF in the output directory
                try (var files = Files.list(outputDir)) {
                    pdfPath = files
                            .filter(p -> p.toString().endsWith(".pdf"))
                            .findFirst()
                            .orElse(null);
                }
            }

            if (pdfPath == null || !Files.exists(pdfPath)) {
                log.errorf("PDF not found after conversion. Output dir contents: %s",
                        String.join(", ", outputDir.toFile().list()));
                throw new WordConversionException(
                        "PDF not generated - LibreOffice may have failed silently. Output: " + output);
            }

            log.debugf("PDF generated successfully: %s (%d bytes)",
                    pdfPath, Files.size(pdfPath));

            return pdfPath;

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new WordConversionException("LibreOffice conversion failed: " + e.getMessage(), e);
        }
    }

    /**
     * Safely cleans up the temporary directory.
     */
    private void cleanupTempDir(Path tempDir) {
        try {
            FileUtils.deleteDirectory(tempDir.toFile());
            log.debugf("Cleaned up temp directory: %s", tempDir);
        } catch (IOException e) {
            log.warnf("Failed to cleanup temp directory %s: %s", tempDir, e.getMessage());
        }
    }

    @Override
    public String getName() {
        return "LocalWordToPdfConverter (poi-tl + LibreOffice)";
    }
}
