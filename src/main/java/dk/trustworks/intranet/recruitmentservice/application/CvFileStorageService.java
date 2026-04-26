package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.sharepoint.dto.DriveItem;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * Stores candidate CV uploads in SharePoint via the existing {@link SharePointService}.
 *
 * <p>Layout: each candidate gets a per-UUID folder under the configured Recruitment site, and
 * uploaded files are timestamp-prefixed so multiple uploads for the same candidate never collide.
 * Inbound filenames are aggressively sanitised (only ASCII alphanumerics, dot, hyphen, underscore
 * survive) before being routed to SharePoint, to defuse path traversal and shell metacharacters.</p>
 *
 * <p>Site URL and drive name are configurable so production, staging, and test environments can
 * point at different SharePoint sites without code changes:
 * <ul>
 *   <li>{@code recruitment.sharepoint.site-url} — defaults to {@code https://trustworks.sharepoint.com/sites/Recruitment}</li>
 *   <li>{@code recruitment.sharepoint.drive-name} — defaults to {@code Documents} (the default drive)</li>
 *   <li>{@code recruitment.sharepoint.candidates-folder} — defaults to {@code Candidates}</li>
 * </ul>
 * </p>
 *
 * <p>Note on the upstream API: {@link SharePointService#uploadFile} returns a {@link DriveItem}
 * (not a raw URL string). We extract {@link DriveItem#webUrl()} as the canonical URL to persist
 * on {@code CandidateCv}. The supplied {@code contentType} is recorded by the caller on the
 * entity but not forwarded to {@link SharePointService}, whose Graph upload path infers MIME type
 * from filename and content.</p>
 */
@ApplicationScoped
public class CvFileStorageService {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Maximum length of the sanitised filename portion (excluding the {@code cv-{ts}-} prefix). */
    private static final int MAX_FILENAME_LENGTH = 80;

    @ConfigProperty(
            name = "recruitment.sharepoint.site-url",
            defaultValue = "https://trustworks.sharepoint.com/sites/Recruitment")
    String siteUrl;

    @ConfigProperty(
            name = "recruitment.sharepoint.drive-name",
            defaultValue = "Documents")
    String driveName;

    @ConfigProperty(
            name = "recruitment.sharepoint.candidates-folder",
            defaultValue = "Candidates")
    String candidatesFolder;

    @Inject
    SharePointService sharePointService;

    /**
     * Uploads a CV for the given candidate and returns the SharePoint web URL.
     *
     * @param candidateUuid candidate aggregate root UUID — used as the folder name; expected to be
     *                      a valid UUID supplied by the application service, never user input.
     *                      Sanitisation is still applied as defence in depth.
     * @param filename      original client-supplied filename; sanitised before use.
     * @param contentType   MIME type recorded by the caller on the persistence entity. Not
     *                      forwarded to SharePoint (Graph infers from extension/content).
     * @param data          file bytes; size validation is the caller's responsibility (the
     *                      {@code CvFileExtractor} already enforces a 10 MB cap on inbound CVs).
     * @return the SharePoint {@code webUrl} for the uploaded file; {@code null} only if the
     *         upstream {@link DriveItem} carried no {@code webUrl}, which Graph never does in
     *         practice.
     */
    public String store(String candidateUuid, String filename, String contentType, byte[] data) {
        String safe = sanitise(filename);
        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        String storedFilename = "cv-" + timestamp + "-" + safe;
        String folderPath = candidatesFolder + "/" + sanitise(candidateUuid);

        DriveItem result = sharePointService.uploadFile(
                siteUrl, driveName, folderPath, storedFilename, data);
        return result.webUrl();
    }

    /**
     * Computes the SHA-256 digest of the supplied bytes as a lowercase hex string.
     * Used by callers to populate {@code CandidateCv.sha256} for de-duplication.
     */
    public String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    }

    /**
     * Strips directory traversal sequences and shell metacharacters: replaces any character
     * outside {@code [A-Za-z0-9._-]} with an underscore, collapses runs of underscores or dots
     * into a single occurrence (so {@code ..} sequences cannot survive), and caps the result at
     * {@link #MAX_FILENAME_LENGTH} characters. Returns {@code "cv"} when the input is
     * {@code null} or blank.
     */
    private String sanitise(String filename) {
        if (filename == null || filename.isBlank()) {
            return "cv";
        }
        String cleaned = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        cleaned = cleaned.replaceAll("_+", "_");
        cleaned = cleaned.replaceAll("\\.{2,}", ".");
        if (cleaned.length() > MAX_FILENAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_FILENAME_LENGTH);
        }
        return cleaned;
    }
}
