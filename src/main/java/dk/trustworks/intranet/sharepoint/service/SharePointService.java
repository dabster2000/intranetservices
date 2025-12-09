package dk.trustworks.intranet.sharepoint.service;

import dk.trustworks.intranet.sharepoint.client.GraphApiClient;
import dk.trustworks.intranet.sharepoint.client.GraphResponseExceptionMapper.SharePointException;
import dk.trustworks.intranet.sharepoint.dto.Drive;
import dk.trustworks.intranet.sharepoint.dto.DriveCollectionResponse;
import dk.trustworks.intranet.sharepoint.dto.DriveItem;
import dk.trustworks.intranet.sharepoint.dto.DriveItemCollectionResponse;
import dk.trustworks.intranet.sharepoint.dto.GraphPath;
import dk.trustworks.intranet.sharepoint.dto.Site;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Service for SharePoint file operations via Microsoft Graph API.
 * Provides high-level methods for listing, uploading, and downloading files.
 *
 * <p>This service handles the resolution of site URLs to IDs, drive names to IDs,
 * and folder paths to IDs, abstracting the Graph API complexity from consumers.
 */
@JBossLog
@ApplicationScoped
public class SharePointService {

    @RestClient
    GraphApiClient graphClient;

    @ConfigProperty(name = "sharepoint.hostname", defaultValue = "trustworks.sharepoint.com")
    String defaultHostname;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    // =========== ID Resolution ===========

    /**
     * Resolves a SharePoint site URL to its unique site ID.
     *
     * @param siteUrl the full SharePoint site URL (e.g., "https://tenant.sharepoint.com/sites/MySite")
     * @return the site ID
     * @throws SharePointException if the site cannot be found
     */
    public String resolveSiteId(String siteUrl) {
        log.debugf("Resolving site ID for: %s", siteUrl);
        URI uri = URI.create(siteUrl);
        String hostname = uri.getHost();
        String relativePath = uri.getPath();

        // Build site path - needs to start with / for Graph API
        String sitePathEncoded = "/" + GraphPath.encodePath(
            relativePath.replaceFirst("^/+", "")
        );

        Site site = graphClient.getSiteByPath(hostname, sitePathEncoded);
        log.debugf("Resolved site: id=%s, name=%s", site.id(), site.displayName());
        return site.id();
    }

    /**
     * Resolves a drive (document library) name to its unique drive ID.
     *
     * @param siteId the site ID
     * @param driveName the display name of the document library
     * @return the drive ID
     * @throws SharePointException if the drive cannot be found (404)
     */
    public String resolveDriveId(String siteId, String driveName) {
        if (driveName == null || driveName.isBlank() || "default".equalsIgnoreCase(driveName)) {
            return graphClient.getDefaultDrive(siteId).id();
        }

        log.debugf("Resolving drive ID: siteId=%s, driveName=%s", siteId, driveName);
        DriveCollectionResponse drives = graphClient.listDrives(siteId);

        Optional<Drive> match = drives.value().stream()
            .filter(d -> d.name() != null && d.name().equalsIgnoreCase(driveName))
            .findFirst();

        return match.map(d -> {
            log.debugf("Resolved drive: id=%s", d.id());
            return d.id();
        }).orElseThrow(() -> new SharePointException(
            "Drive not found: " + driveName, 404));
    }

    /**
     * Resolves a folder path to its DriveItem ID.
     *
     * @param driveId the drive ID
     * @param folderPath the folder path relative to the drive root (can be null/empty for root)
     * @return the folder's item ID
     * @throws SharePointException if the folder doesn't exist or is not a folder
     */
    public String resolveFolderId(String driveId, String folderPath) {
        if (folderPath == null || folderPath.isBlank()) {
            return graphClient.getDriveRoot(driveId).id();
        }

        String encoded = GraphPath.encodePath(folderPath);
        DriveItem item = graphClient.getDriveItemByPath(driveId, encoded);

        if (!item.isFolder()) {
            throw new SharePointException(
                "Path exists but is not a folder: " + folderPath, 400);
        }
        return item.id();
    }

    // =========== File Operations ===========

    /**
     * Lists files and folders in a SharePoint folder.
     *
     * @param siteUrl the SharePoint site URL
     * @param driveName the document library name
     * @param folderPath the folder path (null or empty for root)
     * @return collection of drive items
     * @throws SharePointException if the location cannot be accessed
     */
    public DriveItemCollectionResponse listFolder(
            String siteUrl, String driveName, String folderPath) {
        log.infof("Listing folder: site=%s, drive=%s, path=%s",
            siteUrl, driveName, folderPath);

        String siteId = resolveSiteId(siteUrl);
        String driveId = resolveDriveId(siteId, driveName);

        if (folderPath == null || folderPath.isBlank()) {
            return graphClient.listRootChildren(driveId);
        }

        String encodedPath = GraphPath.encodePath(folderPath);
        return graphClient.listChildrenByPath(driveId, encodedPath);
    }

    /**
     * Uploads a file to SharePoint.
     *
     * @param siteUrl the SharePoint site URL
     * @param driveName the document library name
     * @param folderPath the folder path (null or empty for root)
     * @param fileName the name for the uploaded file
     * @param content the file content
     * @return the created DriveItem
     * @throws SharePointException if upload fails
     */
    public DriveItem uploadFile(
            String siteUrl, String driveName, String folderPath,
            String fileName, byte[] content) {
        log.infof("Uploading file: site=%s, drive=%s, path=%s, file=%s, size=%d",
            siteUrl, driveName, folderPath, fileName, content.length);

        String filePath = GraphPath.buildFilePath(folderPath, fileName);
        String filePathEncoded = GraphPath.encodePath(filePath);

        String siteId = resolveSiteId(siteUrl);
        String driveId = resolveDriveId(siteId, driveName);

        DriveItem result = graphClient.uploadContent(driveId, filePathEncoded, content);
        log.infof("File uploaded successfully: id=%s, webUrl=%s", result.id(), result.webUrl());
        return result;
    }

    /**
     * Downloads a file from SharePoint.
     *
     * @param siteUrl the SharePoint site URL
     * @param driveName the document library name
     * @param folderPath the folder path (null or empty for root)
     * @param fileName the name of the file to download
     * @return the file content as bytes
     * @throws SharePointException if download fails
     */
    public byte[] downloadFile(
            String siteUrl, String driveName, String folderPath, String fileName) {
        log.infof("Downloading file: site=%s, drive=%s, path=%s, file=%s",
            siteUrl, driveName, folderPath, fileName);

        String filePath = GraphPath.buildFilePath(folderPath, fileName);
        String filePathEncoded = GraphPath.encodePath(filePath);

        String siteId = resolveSiteId(siteUrl);
        String driveId = resolveDriveId(siteId, driveName);

        // Get item to retrieve ID
        DriveItem item = graphClient.getDriveItemByPath(driveId, filePathEncoded);

        // Download via redirect
        try (Response response = graphClient.downloadContent(driveId, item.id())) {
            String location = response.getHeaderString("Location");

            if (location == null) {
                // Some Graph API responses return content directly
                if (response.hasEntity()) {
                    Object entity = response.getEntity();
                    if (entity instanceof byte[] bytes) {
                        return bytes;
                    }
                }
                throw new SharePointException(
                    "Expected redirect or content, got status: " + response.getStatus(), 500);
            }

            // Follow redirect to download from Azure blob storage
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(location))
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();

            HttpResponse<byte[]> httpResponse = httpClient.send(
                request, HttpResponse.BodyHandlers.ofByteArray());

            if (httpResponse.statusCode() != 200) {
                throw new SharePointException(
                    "Download failed with status: " + httpResponse.statusCode(), httpResponse.statusCode());
            }

            log.infof("File downloaded successfully: %d bytes", httpResponse.body().length);
            return httpResponse.body();
        } catch (SharePointException e) {
            throw e;
        } catch (Exception e) {
            log.errorf(e, "Download failed for file: %s", fileName);
            throw new SharePointException("Download failed: " + e.getMessage(), 500);
        }
    }

    /**
     * Gets file metadata without downloading content.
     *
     * @param siteUrl the SharePoint site URL
     * @param driveName the document library name
     * @param folderPath the folder path (null or empty for root)
     * @param fileName the name of the file
     * @return the DriveItem metadata
     * @throws SharePointException if the file doesn't exist
     */
    public DriveItem getFileMetadata(
            String siteUrl, String driveName, String folderPath, String fileName) {
        log.debugf("Getting file metadata: site=%s, drive=%s, path=%s, file=%s",
            siteUrl, driveName, folderPath, fileName);

        String filePath = GraphPath.buildFilePath(folderPath, fileName);
        String filePathEncoded = GraphPath.encodePath(filePath);

        String siteId = resolveSiteId(siteUrl);
        String driveId = resolveDriveId(siteId, driveName);

        return graphClient.getDriveItemByPath(driveId, filePathEncoded);
    }
}
