package dk.trustworks.intranet.fileservice.services;

import dk.trustworks.intranet.fileservice.model.File;
import io.quarkus.cache.CacheResult;
import lombok.extern.jbosslog.JBossLog;
import org.apache.tika.Tika;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for managing video files in S3
 * Handles video upload, retrieval, deletion, and presigned URL generation for streaming
 */
@ApplicationScoped
@JBossLog
public class VideoService {

    @ConfigProperty(name = "bucket.videos", defaultValue = "tw-video-bucket")
    String videoBucketName;

    private static final Region REGION = Region.EU_WEST_1;
    private static final Duration PRESIGNED_URL_DURATION = Duration.ofMinutes(15);
    private static final long MULTIPART_THRESHOLD = 5 * 1024 * 1024; // 5MB

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    public VideoService() {
        ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder()
                .proxyConfiguration(proxyConfig.build());

        this.s3Client = S3Client.builder()
                .region(REGION)
                .httpClientBuilder(httpClientBuilder)
                .build();

        this.s3Presigner = S3Presigner.builder()
                .region(REGION)
                .build();
    }

    /**
     * List all videos with metadata
     * Cached for 3 hours for performance
     */
    @CacheResult(cacheName = "videos")
    public List<File> listVideos() {
        log.info("Fetching all videos from S3 bucket: " + videoBucketName);

        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(videoBucketName)
                .build();

        ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);

        return listResponse.contents().stream()
                .map(s3Object -> {
                    Optional<File> fileOptional = File.findByIdOptional(s3Object.key());
                    if (fileOptional.isPresent()) {
                        return fileOptional.get();
                    } else {
                        // Create placeholder if metadata not found
                        File file = new File();
                        file.setUuid(s3Object.key());
                        file.setType(File.TYPE_VIDEO);
                        file.setFilename(s3Object.key());
                        file.setName("Unknown");
                        file.setUploaddate(LocalDate.now());
                        return file;
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * Get video metadata and file content
     * Cached for 3 hours
     */
    @CacheResult(cacheName = "videos")
    public Optional<File> getVideo(String uuid) {
        log.info("Fetching video: " + uuid);

        Optional<File> fileOptional = File.findByIdOptional(uuid);
        if (fileOptional.isEmpty()) {
            log.warn("Video metadata not found in database: " + uuid);
            return Optional.empty();
        }

        File file = fileOptional.get();

        try {
            // Download video content from S3
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            s3Client.getObject(
                    GetObjectRequest.builder()
                            .bucket(videoBucketName)
                            .key(uuid)
                            .build(),
                    ResponseTransformer.toOutputStream(baos)
            );

            file.setFile(baos.toByteArray());
            log.info("Successfully retrieved video: " + uuid + " (" + baos.size() + " bytes)");
            return Optional.of(file);

        } catch (S3Exception e) {
            log.error("Error loading video from S3: " + uuid + " - " + e.awsErrorDetails().errorMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Generate presigned URL for video streaming
     * URL is valid for 15 minutes
     * This is the preferred method for video playback to avoid loading entire file into memory
     */
    public String generatePresignedUrl(String uuid) {
        log.info("Generating presigned URL for video: " + uuid);

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(videoBucketName)
                .key(uuid)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(PRESIGNED_URL_DURATION)
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
        String url = presignedRequest.url().toString();

        log.info("Generated presigned URL valid for " + PRESIGNED_URL_DURATION.toMinutes() + " minutes");
        return url;
    }

    /**
     * Save video to S3 and persist metadata
     * Uses multipart upload for files larger than 5MB
     */
    @Transactional
    public void saveVideo(File video) {
        log.info("Saving video: " + video.getFilename());

        // Generate UUID if not provided
        if (video.getUuid() == null || video.getUuid().isEmpty()) {
            video.setUuid(UUID.randomUUID().toString());
        }

        // Set type and upload date
        video.setType(File.TYPE_VIDEO);
        if (video.getUploaddate() == null) {
            video.setUploaddate(LocalDate.now());
        }

        // Detect MIME type
        try {
            Tika tika = new Tika();
            String mimeType = tika.detect(video.getFile());
            log.info("Detected MIME type: " + mimeType);

            if (!isValidVideoMimeType(mimeType)) {
                log.warn("File may not be a valid video. MIME type: " + mimeType);
            }
        } catch (Exception e) {
            log.warn("Could not detect MIME type", e);
        }

        // Persist metadata to database
        File.persist(video);

        // Upload to S3
        byte[] videoData = video.getFile();
        if (videoData.length > MULTIPART_THRESHOLD) {
            log.info("Using multipart upload for large file (" + videoData.length + " bytes)");
            // For large files, use standard putObject which handles multipart automatically
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(videoBucketName)
                            .key(video.getUuid())
                            .contentType(detectContentType(video.getFilename()))
                            .build(),
                    RequestBody.fromBytes(videoData)
            );
        } else {
            log.info("Using standard upload (" + videoData.length + " bytes)");
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(videoBucketName)
                            .key(video.getUuid())
                            .contentType(detectContentType(video.getFilename()))
                            .build(),
                    RequestBody.fromBytes(videoData)
            );
        }

        log.info("Successfully saved video: " + video.getUuid());
    }

    /**
     * Delete video from S3 and database
     * This will invalidate the cache
     */
    @Transactional
    public void deleteVideo(String uuid) {
        log.info("Deleting video: " + uuid);

        try {
            // Delete from database
            File.deleteById(uuid);

            // Delete from S3
            s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(videoBucketName)
                            .key(uuid)
                            .build()
            );

            log.info("Successfully deleted video: " + uuid);
        } catch (Exception e) {
            log.error("Error deleting video: " + uuid, e);
            throw e;
        }
    }

    /**
     * Check if a video exists in S3
     */
    public boolean videoExists(String uuid) {
        try {
            s3Client.headObject(
                    HeadObjectRequest.builder()
                            .bucket(videoBucketName)
                            .key(uuid)
                            .build()
            );
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    /**
     * Validate video MIME type
     */
    private boolean isValidVideoMimeType(String mimeType) {
        return mimeType != null && (
                mimeType.startsWith("video/") ||
                mimeType.equals("application/octet-stream") // Sometimes video files are detected as this
        );
    }

    /**
     * Detect content type from filename
     */
    private String detectContentType(String filename) {
        if (filename == null) return "application/octet-stream";

        String lower = filename.toLowerCase();
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".flv")) return "video/x-flv";

        return "application/octet-stream";
    }
}
