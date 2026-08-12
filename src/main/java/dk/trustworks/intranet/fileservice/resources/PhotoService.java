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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.tasks.UnsupportedFormatException;
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
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

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

    /**
     * Selects the rows a new upload replaces. Used for BOTH the lookup of superseded uuids and the
     * delete, so the two cannot drift apart.
     * <p>
     * The {@code type} predicate is load-bearing, not decoration. The {@code files} table is shared
     * across domains keyed on the same {@code relateduuid}: employee documents
     * ({@code UserDocumentResource}: {@code "relateduuid like ?1 AND type like 'DOCUMENT'"}) and
     * recruitment attachments live there too. Without it, replacing an employee's portrait deleted
     * every document that employee owned — on every upload, because callers always send a fresh
     * uuid and so always take the delete-and-insert branch.
     */
    static final String SUPERSEDED_ROWS_QUERY = "relateduuid = ?1 AND type = ?2";

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
        // Deliberately no image/svg+xml entry. SVG is a script-bearing document rather than a
        // raster image, {@link #update} now rejects it, and an extension mapping here would make
        // the upload path read as though storing one were still anticipated.
        MIME_TO_EXTENSION.put("image/x-icon", ".ico");

        // Default binary
        MIME_TO_EXTENSION.put("application/octet-stream", ".bin");
    }

    /**
     * The only MIME types {@link #update} will store, and the only ones {@code FileResource} will
     * serve back under their own content type.
     * <p>
     * An allowlist rather than an SVG denylist, because the read path derives the response
     * {@code Content-Type} from the stored bytes: whatever gets past here is what a browser is
     * later told it is receiving. SVG is the concrete danger — it is a script-bearing XML document,
     * so a stored one comes back as {@code image/svg+xml} and executes on the intranet origin when
     * navigated to directly. {@code X-Content-Type-Options: nosniff} does not mitigate that,
     * because the declared type is honest.
     * <p>
     * Nothing upstream stopped it before: all four REST entry points that reach this service
     * ({@code PUT /files/photos}, {@code /logo}, {@code /portrait} and {@code /public/client}) ran
     * Tika purely to pick a filename extension, and {@link #resizeToUploadDimensions} reports a
     * decode failure at WARN and then stores the original bytes verbatim.
     * <p>
     * webp and ico stay on the list even though stock JDK 21 ImageIO cannot decode either — they
     * are inert raster formats that browsers render, they arrive from real clients today, and they
     * are already stored unresized. Dropping them would reject working uploads for no security
     * gain, since neither can carry script.
     */
    private static final Set<String> STORABLE_IMAGE_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/gif",
            "image/bmp",
            "image/tiff",
            "image/webp",
            "image/x-icon");

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

    /**
     * Whether bytes detected as {@code mimeType} may be stored, and served back under that type.
     * <p>
     * Shared with the read path ({@code FileResource#getImage}) on purpose: the set of things safe
     * to keep and the set safe to declare to a browser are the same set, and rows predating this
     * guard still have to pass the reader's check.
     * <p>
     * Tika answers a bare type today, but a parameterised one ({@code image/jpeg; charset=...})
     * would slip past a plain equality test, so parameters and casing are normalised away first.
     *
     * @see #STORABLE_IMAGE_MIME_TYPES
     */
    public static boolean isStorableImageType(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        int parameterStart = mimeType.indexOf(';');
        String bareType = (parameterStart >= 0 ? mimeType.substring(0, parameterStart) : mimeType).trim();
        return STORABLE_IMAGE_MIME_TYPES.contains(bareType.toLowerCase(Locale.ROOT));
    }

    /**
     * Rejects anything {@link #update} must not store, before a byte of it reaches S3 or the
     * {@code files} table.
     * <p>
     * Runs on the post-resize bytes rather than on the request body, so what it clears is exactly
     * what gets persisted: {@link #resizeToUploadDimensions} re-encodes anything ImageIO can read
     * as JPEG, which also collapses a polyglot upload into a plain raster image.
     *
     * @throws WebApplicationException 400, mapped to a JSON error body by
     *                                 {@code WebApplicationExceptionMapper}
     */
    void requireStorableImage(File photo) {
        byte[] data = photo.getFile();
        if (data == null || data.length == 0) {
            // Previously this reached s3.putObject and failed there as a 500.
            throw new WebApplicationException("photo file is required", Response.Status.BAD_REQUEST);
        }
        String mimeType = detectMimeType(data);
        if (!isStorableImageType(mimeType)) {
            log.warnf("Rejected upload for relateduuid=%s type=%s: %s is not a storable image format (%d bytes)",
                    photo.getRelateduuid(), photo.getType(), mimeType, data.length);
            throw new WebApplicationException("Unsupported image format: " + mimeType,
                    Response.Status.BAD_REQUEST);
        }
    }

    private long getFileSize(byte[] fileData) {
        return fileData.length / 1024; // Convert bytes to kilobytes
    }

    /**
     * Scales {@code data} down to {@code width}, falling back to the untouched bytes when it cannot.
     * <p>
     * The fallback is deliberate and load-bearing: callers serve whatever comes back, so an image
     * this JVM cannot decode is still delivered to the browser rather than turning into an error.
     * What the level split below decides is only how loudly that gets reported.
     * <p>
     * {@link UnsupportedFormatException} means no {@code ImageReader} SPI recognised the leading
     * bytes. Stock JDK 21 ImageIO reads JPEG/PNG/GIF/BMP/TIFF only — no plugin is on the classpath —
     * so every webp, heic, avif or svg avatar lands here. That is a property of the uploaded data,
     * not a fault of this service, and it matches how the upload path already reports the identical
     * condition ({@link #resizeToUploadDimensions} logs at WARN).
     * <p>
     * It is <em>not</em> a clean proxy for "unsupported format", which is why the context below is
     * not optional: the same exception is thrown for severely truncated data of a supported format
     * (a JPEG cut to 1 byte, a PNG cut to under 8) and for an empty array. Recording the byte length
     * and the detected type is what keeps a genuine corrupt-blob case distinguishable from a webp
     * avatar now that the two no longer share a log level.
     *
     * @param key S3 key being resized, for diagnostics — the previous message named neither the
     *            photo nor the width, which left the one production occurrence untraceable.
     */
    byte[] resizeImage(byte[] data, int width, String key) {
        log.debug("Resizing image locally to width=" + width);
        // An absent S3 object arrives here as an empty array (loadFromS3 returns byte[0] for both a
        // missing key and a hard S3 failure). Decoding that is guaranteed to raise the same
        // UnsupportedFormatException as a genuinely exotic format, so short-circuit it instead:
        // there is nothing to resize, and the attempt would otherwise dominate the new WARN stream.
        if (data == null || data.length == 0) {
            log.debugf("Nothing to resize for %s width=%d — no bytes stored", key, width);
            return data;
        }
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
        } catch (UnsupportedFormatException e) {
            log.warnf("No ImageReader for %s width=%d (detected %s, %d bytes) — serving original unresized",
                    key, width, detectMimeType(data), data.length);
            return data;
        } catch (Exception e) {
            // Anything else — a decode error on a recognised format, an I/O fault, a rejected
            // width — is a real failure and keeps its stack trace.
            log.error("Local resize failed for " + key + " width=" + width, e);
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
            for (String key : staleThumbnailKeys(relateduuid)) {
                try {
                    s3.deleteObject(DeleteObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .build());
                    log.debug("Deleted stale S3 thumbnail: " + key);
                } catch (Exception e) {
                    // Widened from S3Exception: an SdkClientException (connection reset, DNS) used
                    // to escape this lambda, and CompletableFuture.runAsync swallows it silently —
                    // the remaining widths were then never attempted.
                    log.debugf("Could not delete S3 thumbnail %s: %s", key, e.toString());
                }
            }
        });
    }

    /**
     * Every thumbnail key a new upload for {@code relateduuid} has made stale.
     * <p>
     * The frontend requests ad-hoc sizes via {@code ?width=} — 28, 36, 44, 56 and 1 all appear in
     * the UI — so deleting only {@link #COMMON_THUMBNAIL_WIDTHS} left those objects behind, and
     * because {@link #getResizedPhoto} short-circuits on {@link #s3ObjectExists} they kept serving
     * the <em>previous</em> photo forever. Listing the prefix is what makes the set complete.
     * <p>
     * The listing is best-effort: the hardcoded widths are always returned, so losing
     * {@code s3:ListBucket} degrades this back to the old behaviour instead of skipping cleanup
     * entirely. Uploads are rare (single digits per month), so scanning the prefix is cheap.
     */
    private Set<String> staleThumbnailKeys(String relateduuid) {
        Set<String> keys = new LinkedHashSet<>();
        for (int width : COMMON_THUMBNAIL_WIDTHS) {
            keys.add(resizedKey(relateduuid, width));
        }
        try {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(RESIZED_KEY_PREFIX);
            for (ListObjectsV2Response page : s3.listObjectsV2Paginator(request.build())) {
                for (S3Object object : page.contents()) {
                    if (isResizedKeyFor(object.key(), relateduuid)) {
                        keys.add(object.key());
                    }
                }
            }
        } catch (Exception e) {
            log.warnf("Could not list %s to find every stale thumbnail for %s (%s) — "
                            + "falling back to the common widths only",
                    RESIZED_KEY_PREFIX, relateduuid, e.toString());
        }
        return keys;
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

    /**
     * Matches {@code resized/{width}/{relateduuid}} for this relateduuid at any width — which is
     * what lets the cleanup cover the ad-hoc {@code ?width=} sizes, not just the common ones.
     */
    static boolean isResizedKeyFor(String key, String relateduuid) {
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
            byte[] resized = resizeImage(photo.getFile(), width, key);
            // Never publish an empty thumbnail. resizeImage hands back what it was given when it
            // cannot scale, so a photo whose S3 object is missing would otherwise persist a 0-byte
            // object under this key — and s3ObjectExists would then serve those 0 bytes for this
            // width forever, outliving the repair of the underlying photo.
            if (resized != null && resized.length > 0) {
                saveToS3Async(key, resized);
            }
            return resized;
        } catch (Exception e) {
            log.error("Error resizing photo", e);
            return photo.getFile();
        }
    }

    private void update(File photo) {
        // The one chokepoint every upload passes through — updatePhoto, updateLogo and
        // updatePortrait all land here, and those three are the whole of the write surface exposed
        // by FileResource and PublicResource. Validating here rather than per-resource is what
        // stops a fifth entry point from being added without the check.
        requireStorableImage(photo);

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
            // Supersede only rows of the SAME type. The files table is shared: employee documents
            // live in it keyed on the same relateduuid (UserDocumentResource queries
            // "relateduuid like ?1 AND type like 'DOCUMENT'"), as do recruitment attachments.
            // Matching on relateduuid alone therefore deleted every document belonging to the user
            // whose portrait was being replaced — and because the callers always send a fresh uuid,
            // existingPhoto is always null and this branch runs on every single upload.
            // The reader is type-scoped too (findPhotoByRelatedUUID filters type = 'PHOTO'), so
            // scoping the delete the same way supersedes exactly what the reader could return.
            // (File.file is @Transient, so this reads metadata rows only — no blobs are pulled
            // just to learn the superseded uuids.)
            File.<File>find(SUPERSEDED_ROWS_QUERY, photo.getRelateduuid(), photo.getType())
                    .list()
                    .forEach(superseded -> supersededUuids.add(superseded.getUuid()));
            File.delete(SUPERSEDED_ROWS_QUERY, photo.getRelateduuid(), photo.getType());
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

    /**
     * Stores a conference email image immutably. Deliberately NOT routed through
     * {@link #update}: that method supersedes (deletes) every row sharing the same
     * (relateduuid, type) — correct for portraits, catastrophic here, where every
     * uploaded image is referenced from already-sent emails and must stay fetchable
     * indefinitely. No resize either: the designer controls display width, and the
     * bytes served back must be the bytes the author approved.
     */
    @Transactional
    public void storeEmailImage(File image) {
        image.persist();
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(image.getUuid())
                        .build(),
                RequestBody.fromBytes(image.getFile()));
    }

    /** Loads an email image row and its bytes by uuid; null when unknown or not an EMAIL_IMAGE row. */
    public File findEmailImage(String uuid) {
        File image = File.<File>find("uuid = ?1 AND type = 'EMAIL_IMAGE'", uuid).firstResult();
        if (image == null) return null;
        image.setFile(loadFromS3(image.getUuid()));
        return image;
    }
}