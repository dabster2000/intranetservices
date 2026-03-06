package dk.trustworks.intranet.fileservice.resources;

import dk.trustworks.intranet.fileservice.model.File;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheManager;
import io.quarkus.cache.CacheResult;
import jakarta.inject.Inject;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.tika.Tika;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.*;

@ApplicationScoped
@JBossLog
public class PhotoService {

    private static final Map<String, String> MIME_TO_EXTENSION = new HashMap<>();

    static {
        // Image formats
        MIME_TO_EXTENSION.put("image/jpeg", ".jpg");
        MIME_TO_EXTENSION.put("image/jpg", ".jpg");
        MIME_TO_EXTENSION.put("image/png", ".png");
        MIME_TO_EXTENSION.put("image/gif", ".gif");
        MIME_TO_EXTENSION.put("image/bmp", ".bmp");
        MIME_TO_EXTENSION.put("image/tiff", ".tiff");
        MIME_TO_EXTENSION.put("image/webp", ".webp");
        MIME_TO_EXTENSION.put("image/svg+xml", ".svg");
        MIME_TO_EXTENSION.put("image/x-icon", ".ico");

        // Default binary
        MIME_TO_EXTENSION.put("application/octet-stream", ".bin");
    }

    @ConfigProperty(name = "bucket.files")
    String bucketName;

    @Inject
    CacheManager cacheManager;

    // Common thumbnail widths requested by the frontend (UserAvatar component)
    private static final int[] COMMON_THUMBNAIL_WIDTHS = {32, 48, 64, 96, 128, 256, 512};

    // S3Client is thread-safe for operations, initialized once in constructor
    private final S3Client s3;

    public PhotoService() {
        // Initialize S3Client once as singleton (thread-safe)
        Region regionNew = Region.EU_WEST_1;
        ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder()
                .proxyConfiguration(proxyConfig.build());

        this.s3 = S3Client.builder()
                .region(regionNew)
                .httpClientBuilder(httpClientBuilder)
                .build();
    }

    @CacheResult(cacheName = "photo-cache")
    byte[] loadFromS3(@CacheKey String uuid) {
        log.debug("Fetching photo from S3 uuid=" + uuid);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            s3.getObject(GetObjectRequest.builder().bucket(bucketName).key(uuid).build(), ResponseTransformer.toOutputStream(baos));
            return baos.toByteArray();
        } catch (S3Exception e) {
            log.error("Error loading " + uuid + " from S3: " + e.awsErrorDetails().errorMessage(), e);
            return new byte[0];
        }
    }

    public File findPhotoByType(String type) {
        log.debug("findPhotoByType type=" + type);
        List<File> photos = File.find("type = ?1", type).list();
        if(!photos.isEmpty()) {
            File photo = photos.get(new Random().nextInt(photos.size()));
            photo.setFile(loadFromS3(photo.getUuid()));
            return photo;
        } else {
            log.debug("Is not present");
            File newPhoto = new File(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "PHOTO");
            QuarkusTransaction.run(() -> File.persist(newPhoto));

            newPhoto.setFile(loadFromS3("c297e216-e5cf-437d-9a1f-de840c7557e9"));
            return newPhoto;
        }
    }

    public List<File> findPhotosByType(String type) {
        return File.find("type = ?1", type).list();
    }

    public File findPhotoByRelatedUUID(String relateduuid) {
        log.debug("findPhotoByRelatedUUID uuid=" + relateduuid);
        Optional<File> photo = File.find("relateduuid = ?1 AND type = 'PHOTO'", relateduuid).firstResultOptional();
        if(photo.isPresent()) {
            byte[] file = loadFromS3(photo.get().getUuid());
            photo.get().setFile(file);
            String mimeType = detectMimeType(file);
            if (!mimeType.equals("image/webp")) {
                return photo.get();
            }
        }
        return photo.orElseGet(() -> {
            File newPhoto = new File(UUID.randomUUID().toString(), relateduuid, "PHOTO");
            QuarkusTransaction.run(() -> File.persist(newPhoto));
            newPhoto.setFile(loadFromS3("c297e216-e5cf-437d-9a1f-de840c7557e9"));
            return newPhoto;
        });
    }

    public String detectMimeType(byte[] data) {
        Tika tika = new Tika();
        try {
            return tika.detect(data);
        } catch (Exception e) {
            log.error("Error detecting MIME type: " + e.getMessage());
            return "application/octet-stream"; // Default to binary data if detection fails
        }
    }

    public String extensionFromMimeType(String mimeType) {
        return MIME_TO_EXTENSION.getOrDefault(mimeType, ".bin");
    }

    private long getFileSize(byte[] fileData) {
        return fileData.length / 1024; // Convert bytes to kilobytes
    }

    private byte[] resizeImage(byte[] data, int width) {
        log.debug("Resizing image locally to width=" + width);
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thumbnails.of(bais)
                    .size(width, width)
                    .outputFormat("jpg")
                    .outputQuality(0.85)
                    .toOutputStream(baos);
            byte[] resized = baos.toByteArray();
            log.debug("Local resize complete, size=" + getFileSize(resized) + "KB");
            return resized;
        } catch (Exception e) {
            log.error("Local resize failed", e);
            return data;
        }
    }

    private byte[] resizeToUploadDimensions(byte[] data, int maxWidth, int maxHeight) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thumbnails.of(bais)
                    .size(maxWidth, maxHeight)
                    .outputFormat("jpg")
                    .outputQuality(0.90)
                    .toOutputStream(baos);
            byte[] result = baos.toByteArray();
            log.debug("Resized upload image to fit " + maxWidth + "x" + maxHeight +
                    ", size=" + getFileSize(result) + "KB");
            return result;
        } catch (Exception e) {
            log.warn("Image resize failed, saving original", e);
            return data;
        }
    }

    private boolean s3ObjectExists(String key) {
        log.debug("Checking S3 for " + key);
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucketName).key(key).build());
            log.debug("S3 hit for " + key);
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            log.error("Error checking " + key + " on S3: " + e.awsErrorDetails().errorMessage(), e);
            return false;
        }
    }

    private String resizedKey(String uuid, int width) {
        return "resized/" + width + "/" + uuid;
    }

    /**
     * Invalidate all cached versions of a photo when it is updated.
     * Clears both Quarkus in-memory caches and stale S3 thumbnails.
     */
    private void invalidateCachesForPhoto(String relateduuid) {
        log.info("Invalidating photo caches for relateduuid=" + relateduuid);

        // Invalidate Quarkus in-memory caches
        cacheManager.getCache("photo-cache").ifPresent(cache ->
                cache.invalidateAll().await().indefinitely());
        cacheManager.getCache("photo-resize-cache").ifPresent(cache ->
                cache.invalidateAll().await().indefinitely());

        // Delete stale S3 thumbnails asynchronously
        CompletableFuture.runAsync(() -> {
            for (int width : COMMON_THUMBNAIL_WIDTHS) {
                String key = resizedKey(relateduuid, width);
                try {
                    s3.deleteObject(DeleteObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .build());
                    log.debug("Deleted stale S3 thumbnail: " + key);
                } catch (S3Exception e) {
                    log.debug("No S3 thumbnail to delete: " + key);
                }
            }
        });
    }

    private void saveToS3Async(String key, byte[] data) {
        CompletableFuture.runAsync(() -> {
            try {
                s3.putObject(PutObjectRequest.builder().bucket(bucketName).key(key).build(),
                        RequestBody.fromBytes(data));
                log.debug("Stored resized photo in S3 key=" + key);
            } catch (Exception e) {
                log.error("Failed to store resized photo in S3 key=" + key, e);
            }
        });
    }

    @CacheResult(cacheName = "photo-resize-cache")
    public byte[] getResizedPhoto(@CacheKey String relateduuid, @CacheKey int width) {
        String key = resizedKey(relateduuid, width);
        log.debug("Retrieving resized photo " + key);

        if (s3ObjectExists(key)) {
            return loadFromS3(key);
        }

        File photo = findPhotoByRelatedUUID(relateduuid);
        try {
            byte[] resized = resizeImage(photo.getFile(), width);
            saveToS3Async(key, resized);
            return resized;
        } catch (Exception e) {
            log.error("Error resizing photo", e);
            return photo.getFile();
        }
    }

    private void update(File photo) {
        if(photo.getUuid().isEmpty()) {
            photo.setUuid(UUID.randomUUID().toString());
        }

        // First, try to find existing photo
        File existingPhoto = File.find("uuid", photo.getUuid()).firstResult();

        if(photo.getType()==null || photo.getType().isEmpty()) {
            photo.setType("PHOTO");
        }

        // If photo exists, update it instead of trying to insert
        if (existingPhoto != null) {
            // Use Panache's persist() for updates in Quarkus 3
            existingPhoto.setFilename(photo.getFilename());
            existingPhoto.setName(photo.getName());
            existingPhoto.setRelateduuid(photo.getRelateduuid());
            existingPhoto.setType(photo.getType());
            existingPhoto.setUploaddate(photo.getUploaddate());
        } else {
            // Delete old photos with same relateduuid
            File.delete("relateduuid = ?1", photo.getRelateduuid());
            // Insert new photo using Panache persist
            photo.persist();
        }

        // Upload to S3 regardless of whether it's an update or new insert
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(photo.getUuid())
                        .build(),
                RequestBody.fromBytes(photo.getFile()));

        // Invalidate caches and delete stale thumbnails
        invalidateCachesForPhoto(photo.getRelateduuid());
    }

    @Transactional
    public void updatePhoto(File photo) {
        photo.setFile(resizeToUploadDimensions(photo.getFile(), 1600, 800));
        update(photo);
    }

    @Transactional
    public void updateLogo(File photo) {
        photo.setFile(resizeToUploadDimensions(photo.getFile(), 1600, 800));
        update(photo);
    }

    @Transactional
    public void updatePortrait(File photo) {
        photo.setFile(resizeToUploadDimensions(photo.getFile(), 1600, 800));
        update(photo);
        log.info("Photo updated");
    }

    @Transactional
    public void delete(String uuid) {
        File.deleteById(uuid);
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(uuid).build());
    }
}