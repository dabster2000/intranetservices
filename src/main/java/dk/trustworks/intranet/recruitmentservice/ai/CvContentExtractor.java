package dk.trustworks.intranet.recruitmentservice.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.services.S3FileService;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Turns a candidate's most recent CV upload into prompt-ready content for
 * the P9 intake generation (contract §5.3 — NEW code; the codebase had no
 * PDF text extraction or PDF→image rendering before this class).
 * <p>
 * Pipeline:
 * <ol>
 *   <li>Locate the CV: the candidate's {@code DOCUMENT_UPLOADED} events
 *       with {@code payload.kind == "CV"}; the fact from the highest-seq
 *       event wins ("latest CV"). The {@code files.relateduuid} must match
 *       the candidate before any S3 round-trip (IDOR-style guard, kept
 *       even for the reactor).</li>
 *   <li>{@code application/pdf} (or {@code %PDF} magic bytes, whatever the
 *       declared type says): PDFBox text extraction, truncated at
 *       {@value #MAX_TEXT_CHARS} chars. An image-only PDF (extracted text
 *       under {@value #MIN_TEXT_CHARS} chars) falls back to rendering
 *       page 1 at {@value #RENDER_DPI} DPI as PNG for the vision path.</li>
 *   <li>{@code image/jpeg}/{@code image/png} CVs go straight to the
 *       vision path.</li>
 * </ol>
 * <b>Raw PDF bytes are NEVER returned as image content</b> — passing PDFs
 * as {@code input_image} caused production HTTP 400s in the expense flows
 * (PR #178 posture); only rendered PNG pages reach the vision API.
 * <p>
 * Every failure degrades to {@link CvContent#empty()} — the intake prompt
 * then runs answers-only, which is a legitimate generation (contract
 * §5.3.5). This class never throws for missing/broken CVs.
 */
@JBossLog
@ApplicationScoped
public class CvContentExtractor {

    /** Extracted PDF text is truncated here — plenty for a CV, caps the token bill. */
    static final int MAX_TEXT_CHARS = 15_000;
    /** Under this many extracted chars the PDF is treated as image-only (scan). */
    static final int MIN_TEXT_CHARS = 200;
    /** Page-1 render resolution for the vision fallback. */
    static final int RENDER_DPI = 150;

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    S3FileService s3FileService;

    /**
     * Prompt-ready CV content: exactly one of {@link #text} or
     * {@link #base64Image}+{@link #mimeType} is set — or neither (no CV /
     * unreadable CV ⇒ answers-only prompt).
     */
    public record CvContent(String text, String base64Image, String mimeType) {

        public static CvContent empty() {
            return new CvContent(null, null, null);
        }

        public static CvContent ofText(String text) {
            return new CvContent(text, null, null);
        }

        public static CvContent ofImage(String base64, String mimeType) {
            return new CvContent(null, base64, mimeType);
        }

        public boolean hasText() {
            return text != null && !text.isBlank();
        }

        public boolean hasImage() {
            return base64Image != null && !base64Image.isBlank();
        }

        public boolean isEmpty() {
            return !hasText() && !hasImage();
        }
    }

    /**
     * Extract the candidate's latest CV as prompt content. Degrades to
     * {@link CvContent#empty()} on every failure path (no CV event, file
     * row missing, relateduuid mismatch, S3 miss, unparseable PDF).
     */
    public CvContent extract(String candidateUuid) {
        CvRef ref = latestCvRef(candidateUuid);
        if (ref == null) {
            return CvContent.empty();
        }
        File meta = File.findById(ref.fileUuid());
        if (meta == null || !candidateUuid.equals(meta.getRelateduuid())) {
            // IDOR-style guard, kept even for the reactor: a CV event whose
            // file row belongs to someone else (or is gone) is never read.
            log.warnf("CV file %s missing or not related to candidate %s — skipping CV content",
                    ref.fileUuid(), candidateUuid);
            return CvContent.empty();
        }
        byte[] bytes;
        try {
            File withBytes = s3FileService.findOne(ref.fileUuid());
            bytes = withBytes == null ? null : withBytes.getFile();
        } catch (Exception e) {
            log.warnf("Fetching CV bytes failed for file %s (%s) — proceeding answers-only",
                    ref.fileUuid(), e.getClass().getSimpleName());
            return CvContent.empty();
        }
        if (bytes == null || bytes.length == 0) {
            return CvContent.empty();
        }
        if (isPdf(bytes, ref.contentType())) {
            return fromPdf(bytes, ref.fileUuid());
        }
        if ("image/jpeg".equalsIgnoreCase(ref.contentType())
                || "image/png".equalsIgnoreCase(ref.contentType())) {
            return CvContent.ofImage(Base64.getEncoder().encodeToString(bytes),
                    ref.contentType().toLowerCase(java.util.Locale.ROOT));
        }
        log.warnf("CV file %s has unsupported content type %s — proceeding answers-only",
                ref.fileUuid(), ref.contentType());
        return CvContent.empty();
    }

    // ---- PDF handling ----------------------------------------------------------

    private CvContent fromPdf(byte[] bytes, String fileUuid) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            String trimmed = text == null ? "" : text.strip();
            if (trimmed.length() >= MIN_TEXT_CHARS) {
                return CvContent.ofText(trimmed.length() > MAX_TEXT_CHARS
                        ? trimmed.substring(0, MAX_TEXT_CHARS)
                        : trimmed);
            }
            // Image-only CV (scan): render page 1 as PNG for the vision
            // path. NEVER the raw PDF bytes — prod-bug posture (PR #178).
            if (document.getNumberOfPages() == 0) {
                return CvContent.empty();
            }
            BufferedImage page = new PDFRenderer(document).renderImageWithDPI(0, RENDER_DPI);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(page, "png", png);
            return CvContent.ofImage(Base64.getEncoder().encodeToString(png.toByteArray()), "image/png");
        } catch (Exception e) {
            log.warnf("PDF CV %s could not be parsed (%s) — proceeding answers-only",
                    fileUuid, e.getClass().getSimpleName());
            return CvContent.empty();
        }
    }

    private static boolean isPdf(byte[] bytes, String declaredContentType) {
        if ("application/pdf".equalsIgnoreCase(declaredContentType)) {
            return true;
        }
        // Magic-byte sniff: a mislabelled PDF must still take the PDF path,
        // never the raw input_image one.
        return bytes.length >= 4
                && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    // ---- CV location -----------------------------------------------------------

    /** The latest CV fact (highest-seq DOCUMENT_UPLOADED with kind=CV). */
    private record CvRef(String fileUuid, String contentType) {
    }

    private CvRef latestCvRef(String candidateUuid) {
        List<RecruitmentEvent> events = RecruitmentEvent.list(
                "candidateUuid = ?1 and eventType = ?2 order by seq desc",
                candidateUuid, RecruitmentEventType.DOCUMENT_UPLOADED);
        for (RecruitmentEvent event : events) {
            Map<String, Object> payload = parse(event.getPayload());
            if (!"CV".equals(payload.get("kind"))) {
                continue;
            }
            if (payload.get("file_uuid") instanceof String fileUuid && !fileUuid.isBlank()) {
                String contentType = payload.get("content_type") instanceof String c ? c : null;
                return new CvRef(fileUuid, contentType);
            }
        }
        return null;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
