package dk.trustworks.intranet.fileservice.resources;

import dk.trustworks.intranet.fileservice.model.File;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheManager;
import io.quarkus.cache.CacheResult;
import io.quarkus.cache.CompositeCacheKey;
import jakarta.inject.Inject;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.tika.Tika;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.*;

@ApplicationScoped
@JBossLog
public class PhotoService {

    private static final String NO_SUCH_KEY_ERROR_CODE = "NoSuchKey";

    // Compile-time constants so the @CacheResult declarations and the invalidation below cannot drift apart.
    private static final String PHOTO_CACHE = "photo-cache";
    private static final String PHOTO_RESIZE_CACHE = "photo-resize-cache";
    private static final String RESIZED_KEY_PREFIX = "resized/";

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
        this(createS3Client());
    }

    PhotoService(S3Client s3) {
        this.s3 = s3;
    }

    private static S3Client createS3Client() {
        // Initialize S3Client once as singleton (thread-safe)
        Region regionNew = Region.EU_WEST_1;
        ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder()
                .proxyConfiguration(proxyConfig.build());

        return S3Client.builder()
                .region(regionNew)
                .httpClientBuilder(httpClientBuilder)
                .build();
    }

    @CacheResult(cacheName = PHOTO_CACHE)
    byte[] loadFromS3(@CacheKey String uuid) {
        log.debug("Fetching photo from S3 uuid=" + uuid);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            s3.getObject(GetObjectRequest.builder().bucket(bucketName).key(uuid).build(), ResponseTransformer.toOutputStream(baos));
            return baos.toByteArray();
        } catch (S3Exception e) {
            if (isMissingObject(e)) {
                // Expected, benign: this user simply has no photo uploaded. Callers already
                // handle the empty payload, so this must not pollute the ERROR stream.
                log.debugf("No photo stored in S3 for %s", uuid);
                return new byte[0];
            }
            log.error("Error loading " + uuid + " from S3: " + errorMessageOf(e), e);
            return new byte[0];
        }
    }

    /**
     * Distinguishes "this key does not exist" — an expected condition for users without a
     * photo — from a genuine S3 failure (permissions, throttling, network, missing bucket),
     * which must keep logging at ERROR.
     * <p>
     * Deliberately narrower than {@code ExpenseFileService.isMissingObject}: that one also
     * treats 403/AccessDenied as "missing" because the expenses bucket does not grant
     * {@code s3:ListBucket} and therefore masks 404 as 403. The files bucket does grant it and
     * answers a true {@link NoSuchKeyException}, so a 403 here is a real permission problem.
     */
    private static boolean isMissingObject(S3Exception e) {
        if (e instanceof NoSuchKeyException) {
            return true;
        }
        if (e.statusCode() != 404) {
            return false;
        }
        AwsErrorDetails details = e.awsErrorDetails();
        return details != null && NO_SUCH_KEY_ERROR_CODE.equals(details.errorCode());
    }

    /** Null-safe: {@code awsErrorDetails()} is absent for responses without an error body (e.g. HEAD). */
    private static String errorMessageOf(S3Exception e) {
        AwsErrorDetails details = e.awsErrorDetails();
        return details != null && details.errorMessage() != null ? details.errorMessage() : e.getMessage();
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
            // HEAD responses carry no error body, so the error code is usually absent — the
            // status alone is the only reliable "not there" signal here. No thumbnail yet is
            // the normal case on first request, so it stays out of the ERROR stream.
            if (e.statusCode() == 404 || isMissingObject(e)) return false;
            log.error("Error checking " + key + " on S3: " + errorMessageOf(e), e);
            return false;
        }
    }

    private String resizedKey(String uuid, int width) {
        return RESIZED_KEY_PREFIX + width + "/" + uuid;
    }

    /**
     * Invalidate the cached versions of <em>this</em> photo when it is updated.
     * Clears the matching Quarkus in-memory entries and the stale S3 thumbnails.
     *
     * @param supersededUuids S3 keys whose bytes this upload replaces: the key just written plus
     *                        any rows it supersedes (a re-upload gets a fresh uuid).
     */
    private void invalidateCachesForPhoto(String relateduuid, Set<String> supersededUuids) {
        log.info("Invalidating photo caches for relateduuid=" + relateduuid);

        // Evict only the entries this upload actually made stale. This used to be invalidateAll(),
        // so one employee changing their avatar flushed every cached photo and thumbnail in the
        // JVM — which re-armed the S3 refetch storm for everyone else on the next page view.
        Predicate<Object> affected = key -> isAffectedCacheKey(key, relateduuid, supersededUuids);
        cacheManager.getCache(PHOTO_CACHE).ifPresent(cache ->
                cache.invalidateIf(affected).await().indefinitely());
        cacheManager.getCache(PHOTO_RESIZE_CACHE).ifPresent(cache ->
                cache.invalidateIf(affected).await().indefinitely());

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

    /**
     * True when a cache entry holds bytes that updating {@code relateduuid} has made stale.
     * <p>
     * Two key shapes reach this predicate, because Quarkus derives the key from the number of
     * {@code @CacheKey} parameters. {@link #loadFromS3} has one, so {@code photo-cache} is keyed
     * by the raw S3 key verbatim — either a photo uuid or {@code resized/{width}/{relateduuid}}.
     * {@link #getResizedPhoto} has two, so {@code photo-resize-cache} is keyed by a
     * {@link CompositeCacheKey} wrapping {@code (relateduuid, width)}.
     * <p>
     * Matching on the uuid rather than on a width means every cached width is evicted, including
     * ad-hoc ones the frontend requests via {@code ?width=} that are absent from
     * {@link #COMMON_THUMBNAIL_WIDTHS}.
     */
    static boolean isAffectedCacheKey(Object cacheKey, String relateduuid, Set<String> supersededUuids) {
        if (cacheKey instanceof CompositeCacheKey composite) {
            for (Object element : composite.getKeyElements()) {
                if (isAffectedKeyElement(element, relateduuid, supersededUuids)) {
                    return true;
                }
            }
            return false;
        }
        return isAffectedKeyElement(cacheKey, relateduuid, supersededUuids);
    }

    private static boolean isAffectedKeyElement(Object element, String relateduuid, Set<String> supersededUuids) {
        // Non-String elements (the boxed width) can never identify a photo.
        if (!(element instanceof String key)) {
            return false;
        }
        return key.equals(relateduuid)
                || supersededUuids.contains(key)
                || isResizedKeyFor(key, relateduuid);
    }

    /** Matches {@code resized/{width}/{relateduuid}} for this relateduuid at any width. */
    private static boolean isResizedKeyFor(String key, String relateduuid) {
        if (!key.startsWith(RESIZED_KEY_PREFIX)) {
            return false;
        }
        int lastSeparator = key.lastIndexOf('/');
        return lastSeparator >= 0 && key.substring(lastSeparator + 1).equals(relateduuid);
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

    @CacheResult(cacheName = PHOTO_RESIZE_CACHE)
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

        // Every S3 key whose cached bytes this upload invalidates. The key being written is always
        // one; a replacing upload also carries a fresh uuid, so the rows it deletes below must be
        // collected too or their entries would linger in the unbounded photo-cache unreachable.
        Set<String> supersededUuids = new HashSet<>();
        supersededUuids.add(photo.getUuid());

        // If photo exists, update it instead of trying to insert
        if (existingPhoto != null) {
            // Use Panache's persist() for updates in Quarkus 3
            existingPhoto.setFilename(photo.getFilename());
            existingPhoto.setName(photo.getName());
            existingPhoto.setRelateduuid(photo.getRelateduuid());
            existingPhoto.setType(photo.getType());
            existingPhoto.setUploaddate(photo.getUploaddate());
        } else {
            // Delete old photos with same relateduuid (File.file is @Transient, so this reads
            // metadata rows only — no blobs are pulled just to learn the superseded uuids).
            File.<File>find("relateduuid = ?1", photo.getRelateduuid())
                    .list()
                    .forEach(superseded -> supersededUuids.add(superseded.getUuid()));
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
        invalidateCachesForPhoto(photo.getRelateduuid(), supersededUuids);
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