package dk.trustworks.intranet.fileservice.resources;

import dk.trustworks.intranet.documentservice.model.TemplateSigningStoreEntity;
import dk.trustworks.intranet.fileservice.dto.UserSharePointDocumentDTO;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.signing.repository.SigningCaseRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resource for user SharePoint document operations.
 * Provides methods to list and download signed documents uploaded to SharePoint.
 */
@ApplicationScoped
@JBossLog
public class UserSharePointDocumentResource {

    /**
     * Pattern for parsing SharePoint URLs.
     * Format: https://tenant.sharepoint.com/sites/SiteName/LibraryName/FolderPath/FileName.pdf
     */
    private static final Pattern SHAREPOINT_URL_PATTERN = Pattern.compile(
        "^(https://[^/]+/sites/[^/]+)/([^/]+)/(.+)/([^/]+)$"
    );

    @Inject
    SigningCaseRepository signingCaseRepository;

    @Inject
    SharePointService sharePointService;

    /**
     * Find all SharePoint documents for a user.
     * Queries signing_cases where sharepoint_upload_status = 'UPLOADED'.
     *
     * @param userUuid the user UUID
     * @return list of SharePoint documents for this user
     */
    public List<UserSharePointDocumentDTO> findByUserUUID(String userUuid) {
        log.debugf("Finding SharePoint documents for user: %s", userUuid);

        List<SigningCase> cases = signingCaseRepository.find(
            "userUuid = ?1 AND sharepointUploadStatus = 'UPLOADED' AND sharepointFileUrl IS NOT NULL " +
            "ORDER BY createdAt DESC",
            userUuid
        ).list();

        log.debugf("Found %d SharePoint documents for user %s", cases.size(), userUuid);

        return cases.stream()
            .map(this::toDTO)
            .toList();
    }

    /**
     * Download a SharePoint document by signing case ID.
     *
     * Uses the signing store configuration (from template_signing_stores) to get the correct
     * site URL and drive name for Graph API, since URL path segments don't always match
     * Graph API drive names (e.g., "Delte dokumenter" vs "Shared Documents").
     *
     * @param signingCaseId the signing case ID
     * @return the file content as bytes
     * @throws NotFoundException if the signing case doesn't exist or has no SharePoint URL
     */
    public byte[] downloadDocument(Long signingCaseId) {
        log.infof("Downloading SharePoint document for signing case: %d", signingCaseId);

        SigningCase signingCase = signingCaseRepository.findById(signingCaseId);
        if (signingCase == null) {
            throw new NotFoundException("Signing case not found: " + signingCaseId);
        }

        String url = signingCase.getSharepointFileUrl();
        if (url == null || url.isBlank()) {
            throw new NotFoundException("No SharePoint URL for signing case: " + signingCaseId);
        }

        // Get signing store configuration for correct site URL and drive name
        String signingStoreUuid = signingCase.getSigningStoreUuid();
        if (signingStoreUuid == null || signingStoreUuid.isBlank()) {
            log.warnf("No signing store UUID for case %d, falling back to URL parsing", signingCaseId);
            return downloadUsingUrlParsing(url, signingCaseId);
        }

        TemplateSigningStoreEntity store = TemplateSigningStoreEntity.findById(signingStoreUuid);
        if (store == null) {
            log.warnf("Signing store not found: %s, falling back to URL parsing", signingStoreUuid);
            return downloadUsingUrlParsing(url, signingCaseId);
        }

        // Extract folder path and filename from URL
        FolderAndFilename folderAndFilename = extractFolderAndFilename(url, store.getFolderPath());
        if (folderAndFilename == null) {
            log.errorf("Failed to extract folder/filename from URL: %s", url);
            throw new NotFoundException("Invalid SharePoint URL for signing case: " + signingCaseId);
        }

        log.infof("Downloading from SharePoint using store config: site=%s, drive=%s, folder=%s, file=%s",
            store.getSiteUrl(), store.getDriveName(), folderAndFilename.folderPath, folderAndFilename.fileName);

        return sharePointService.downloadFile(
            store.getSiteUrl(),
            store.getDriveName(),
            folderAndFilename.folderPath,
            folderAndFilename.fileName
        );
    }

    /**
     * Fallback method for downloading when signing store configuration is not available.
     * Parses the URL to extract all components including drive name.
     */
    private byte[] downloadUsingUrlParsing(String url, Long signingCaseId) {
        SharePointUrlComponents components = parseSharePointUrl(url);
        if (components == null) {
            log.errorf("Failed to parse SharePoint URL: %s", url);
            throw new NotFoundException("Invalid SharePoint URL for signing case: " + signingCaseId);
        }

        log.infof("Downloading from SharePoint (URL parsing fallback): site=%s, drive=%s, folder=%s, file=%s",
            components.siteUrl, components.driveName, components.folderPath, components.fileName);

        return sharePointService.downloadFile(
            components.siteUrl,
            components.driveName,
            components.folderPath,
            components.fileName
        );
    }

    /**
     * Extracts folder path and filename from a SharePoint URL.
     * Uses the store's configured folder path as a reference point.
     */
    private FolderAndFilename extractFolderAndFilename(String url, String storeFolderPath) {
        try {
            String decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8);
            URI uri = URI.create(decodedUrl);
            String path = uri.getPath();

            // Extract filename (last segment)
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash < 0 || lastSlash >= path.length() - 1) {
                return null;
            }
            String fileName = path.substring(lastSlash + 1);

            // Extract folder path by removing site, drive, and filename from path
            // Path format: /sites/SiteName/LibraryName/FolderPath/FileName
            String[] segments = path.split("/");

            // Skip: empty, "sites", siteName, libraryName
            // Collect remaining segments (except filename) as folder path
            if (segments.length < 5) {
                // Minimal path: /sites/Site/Library/file.pdf -> no folder
                return new FolderAndFilename(storeFolderPath, fileName);
            }

            StringBuilder folderPath = new StringBuilder();
            for (int i = 4; i < segments.length - 1; i++) {
                if (folderPath.length() > 0) folderPath.append("/");
                folderPath.append(segments[i]);
            }

            String folder = folderPath.length() > 0 ? folderPath.toString() : storeFolderPath;
            return new FolderAndFilename(folder, fileName);

        } catch (Exception e) {
            log.warnf(e, "Error extracting folder/filename from URL: %s", url);
            return null;
        }
    }

    /**
     * Holder for folder path and filename extracted from URL.
     */
    record FolderAndFilename(String folderPath, String fileName) {}

    /**
     * Converts a SigningCase entity to a UserSharePointDocumentDTO.
     */
    private UserSharePointDocumentDTO toDTO(SigningCase signingCase) {
        // Derive filename from URL if possible, otherwise from document name
        String filename = deriveFilename(signingCase);

        return UserSharePointDocumentDTO.builder()
            .id(signingCase.getId())
            .name(signingCase.getDocumentName())
            .filename(filename)
            .uploadDate(signingCase.getCreatedAt() != null
                ? signingCase.getCreatedAt().toLocalDate()
                : null)
            .sharepointFileUrl(signingCase.getSharepointFileUrl())
            .source("SHAREPOINT")
            .build();
    }

    /**
     * Derives the filename from SharePoint URL or document name.
     */
    private String deriveFilename(SigningCase signingCase) {
        String url = signingCase.getSharepointFileUrl();
        if (url != null && !url.isBlank()) {
            try {
                URI uri = URI.create(url);
                String path = uri.getPath();
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                    String filename = path.substring(lastSlash + 1);
                    // URL-decode the filename
                    return URLDecoder.decode(filename, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                log.debugf("Could not extract filename from URL: %s", url);
            }
        }

        // Fallback: use document name with .pdf extension
        String docName = signingCase.getDocumentName();
        if (docName != null && !docName.isBlank()) {
            if (docName.toLowerCase().endsWith(".pdf")) {
                return docName;
            }
            return docName + ".pdf";
        }

        return "document.pdf";
    }

    /**
     * Parses a SharePoint URL into its components.
     *
     * Expected format: https://tenant.sharepoint.com/sites/SiteName/LibraryName/FolderPath/FileName.pdf
     *
     * @param url the SharePoint file URL
     * @return parsed components or null if parsing fails
     */
    SharePointUrlComponents parseSharePointUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        try {
            // URL decode first
            String decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8);

            Matcher matcher = SHAREPOINT_URL_PATTERN.matcher(decodedUrl);
            if (matcher.matches()) {
                String siteUrl = matcher.group(1);      // https://tenant.sharepoint.com/sites/SiteName
                String driveName = matcher.group(2);    // LibraryName (Documents, etc.)
                String folderPath = matcher.group(3);   // Folder/Subfolder/...
                String fileName = matcher.group(4);     // filename.pdf

                return new SharePointUrlComponents(siteUrl, driveName, folderPath, fileName);
            }

            // Fallback: try URI parsing for simpler cases
            URI uri = URI.create(url);
            String path = uri.getPath();
            String[] segments = path.split("/");

            // Expect at least: /sites/SiteName/Library/filename
            if (segments.length >= 5 && "sites".equals(segments[1])) {
                String siteUrl = uri.getScheme() + "://" + uri.getHost() + "/sites/" + segments[2];
                String driveName = segments[3];

                // Everything between library and filename is folder path
                StringBuilder folderPath = new StringBuilder();
                for (int i = 4; i < segments.length - 1; i++) {
                    if (folderPath.length() > 0) folderPath.append("/");
                    folderPath.append(segments[i]);
                }

                String fileName = segments[segments.length - 1];
                String folder = folderPath.length() > 0 ? folderPath.toString() : null;

                return new SharePointUrlComponents(siteUrl, driveName, folder, fileName);
            }

            log.warnf("Could not parse SharePoint URL: %s", url);
            return null;

        } catch (Exception e) {
            log.warnf(e, "Error parsing SharePoint URL: %s", url);
            return null;
        }
    }

    /**
     * Holder for parsed SharePoint URL components.
     */
    record SharePointUrlComponents(
        String siteUrl,
        String driveName,
        String folderPath,
        String fileName
    ) {}
}
