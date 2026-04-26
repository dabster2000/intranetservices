package dk.trustworks.intranet.recruitmentservice.application;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/**
 * Extracts plain text from CV uploads (PDF or DOCX).
 *
 * <p>Stateless utility — no I/O beyond the supplied byte array, no database, no DI of other beans.
 * Bean scope is {@link ApplicationScoped} purely so callers can inject it; constructor instantiation
 * is fully supported (and used by the test, which deliberately avoids {@code @QuarkusTest}).</p>
 *
 * <p>Hard limit: {@value #MAX_BYTES} bytes (10 MB). Files above the cap are rejected with
 * {@link IllegalArgumentException} <em>before</em> any parser is invoked, so that a malicious
 * upload cannot exhaust memory by exploiting decompression amplification in PDFBox or POI.</p>
 */
@ApplicationScoped
public class CvFileExtractor {

    /** Maximum accepted upload size, in bytes (10 MB). */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    /**
     * Extracts plain text from {@code data}, dispatching by filename extension.
     *
     * @param data     the file contents
     * @param filename the original filename (used for extension dispatch only — not opened on disk)
     * @return the extracted plain text, trimmed of leading/trailing whitespace
     * @throws IllegalArgumentException if the payload is empty, exceeds {@link #MAX_BYTES},
     *                                  or has an unsupported extension (only {@code .pdf} and
     *                                  {@code .docx} are accepted)
     * @throws IllegalStateException    if the underlying PDFBox or POI parser fails for a reason
     *                                  other than size/extension validation
     */
    public String extract(byte[] data, String filename) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("empty file");
        }
        if (data.length > MAX_BYTES) {
            throw new IllegalArgumentException("file exceeds 10 MB");
        }
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return extractPdf(data);
        }
        if (name.endsWith(".docx")) {
            return extractDocx(data);
        }
        throw new IllegalArgumentException(
                "unsupported file type: " + filename + " (only .pdf and .docx)");
    }

    private String extractPdf(byte[] data) {
        try (var doc = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc).trim();
        } catch (Exception e) {
            throw new IllegalStateException("failed to extract PDF text", e);
        }
    }

    private String extractDocx(byte[] data) {
        try (var bis = new ByteArrayInputStream(data);
             var doc = new XWPFDocument(bis);
             var ext = new XWPFWordExtractor(doc)) {
            return ext.getText().trim();
        } catch (Exception e) {
            throw new IllegalStateException("failed to extract DOCX text", e);
        }
    }
}
