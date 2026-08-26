package dk.trustworks.intranet.fileservice.resources;

import dk.trustworks.intranet.fileservice.model.File;
import io.quarkus.cache.CompositeCacheKey;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.AccessDeniedException;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A missing avatar is an expected, benign condition — the user simply has no photo uploaded.
 * It used to be logged at ERROR with a full stack trace and grew to 79% of all backend ERROR
 * volume in production, because the count scales with page views rather than with the number
 * of affected users. These tests pin the split: key-not-found is quiet, every other S3 failure
 * still reaches ERROR.
 */
@ExtendWith(MockitoExtension.class)
class PhotoServiceTest {

    private static final String BUCKET = "trustworksfiles";
    private static final String UUID = "c4b38e78-1c88-4d95-b43b-5909bdd930ad";
    private static final String RELATED_UUID = "a1b2c3d4-e5f6-4708-9a0b-1c2d3e4f5a6b";

    @Mock
    S3Client s3;

    PhotoService service;

    private Logger photoServiceLogger;
    private RecordingHandler logHandler;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        service = new PhotoService(s3);
        service.bucketName = BUCKET;

        // Surefire installs org.jboss.logmanager.LogManager (see pom.xml), so the logger behind
        // @JBossLog is a java.util.logging.Logger and can be observed through the JUL API.
        photoServiceLogger = Logger.getLogger(PhotoService.class.getName());
        originalLevel = photoServiceLogger.getLevel();
        photoServiceLogger.setLevel(Level.ALL);
        logHandler = new RecordingHandler();
        logHandler.setLevel(Level.ALL);
        photoServiceLogger.addHandler(logHandler);
    }

    @AfterEach
    void tearDown() {
        photoServiceLogger.removeHandler(logHandler);
        photoServiceLogger.setLevel(originalLevel);
    }

    @Test
    void missingKeyIsNotLoggedAsError() {
        throwOnGetObject(NoSuchKeyException.builder()
                .statusCode(404)
                .message("The specified key does not exist.")
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("NoSuchKey")
                        .errorMessage("The specified key does not exist.")
                        .build())
                .build());

        byte[] result = service.loadFromS3(UUID);

        assertNotNull(result);
        assertEquals(0, result.length, "callers rely on the empty-payload contract");
        assertEquals(List.of(), errorMessages(), "a user without a photo must not raise an ERROR");
    }

    @Test
    void missingKeyIsLoggedAtDebugInstead() {
        throwOnGetObject(NoSuchKeyException.builder()
                .statusCode(404)
                .message("The specified key does not exist.")
                .build());

        service.loadFromS3(UUID);

        assertTrue(recordsAtMost(Level.FINE).stream().anyMatch(m -> m.contains(UUID)),
                "the benign case should still be traceable at DEBUG, got: " + allMessages());
    }

    @Test
    void permissionFailureIsStillLoggedAsError() {
        throwOnGetObject(AccessDeniedException.builder()
                .statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("AccessDenied")
                        .errorMessage("not authorized to perform: s3:GetObject")
                        .build())
                .build());

        byte[] result = service.loadFromS3(UUID);

        assertEquals(0, result.length);
        assertEquals(1, errorMessages().size(), "a real S3 denial must stay at ERROR");
        assertTrue(errorMessages().get(0).contains(UUID));
    }

    @Test
    void missingBucketIsStillLoggedAsErrorDespiteBeing404() {
        throwOnGetObject(NoSuchBucketException.builder()
                .statusCode(404)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("NoSuchBucket")
                        .errorMessage("The specified bucket does not exist")
                        .build())
                .build());

        service.loadFromS3(UUID);

        assertEquals(1, errorMessages().size(),
                "404 alone must not silence errors — only NoSuchKey is benign");
    }

    @Test
    void genuineFailureWithoutErrorDetailsIsLoggedAsErrorWithoutThrowing() {
        // awsErrorDetails() is absent on responses with no error body; reading it unguarded
        // would NPE inside the catch block and turn a logging path into a 500.
        S3Exception.Builder unavailable = S3Exception.builder();
        unavailable.statusCode(503);
        unavailable.message("Service unavailable");
        // S3Exception.Builder#build() is only declared to return the AwsServiceException base
        // type; the instance it produces is an S3Exception.
        throwOnGetObject((S3Exception) unavailable.build());

        byte[] result = service.loadFromS3(UUID);

        assertEquals(0, result.length);
        assertEquals(1, errorMessages().size());
    }

    // --- cache invalidation scope -------------------------------------------------------------
    // Updating one photo used to call invalidateAll(), flushing every cached photo and thumbnail
    // in the JVM. These pin the narrowed predicate: everything belonging to the updated photo is
    // evicted, and nothing else is. Key shapes mirror what Quarkus builds — one @CacheKey
    // parameter is used verbatim, two are wrapped in a CompositeCacheKey.

    private static final String OTHER_UUID = "99999999-8888-7777-6666-555555555555";
    private static final Set<String> SUPERSEDED = Set.of(UUID, "00000000-1111-2222-3333-444444444444");

    @Test
    void evictsThePhotoObjectAndEveryUuidItSupersedes() {
        for (String superseded : SUPERSEDED) {
            assertTrue(PhotoService.isAffectedCacheKey(superseded, RELATED_UUID, SUPERSEDED),
                    "a re-upload gets a fresh uuid, so replaced keys must be evicted too: " + superseded);
        }
    }

    @Test
    void evictsResizedEntriesAtEveryWidthIncludingAdHocOnes() {
        // 64 is in COMMON_THUMBNAIL_WIDTHS; 200 is not, but the frontend can request it via ?width=.
        for (int width : new int[]{32, 64, 200, 512, 1337}) {
            assertTrue(PhotoService.isAffectedCacheKey("resized/" + width + "/" + RELATED_UUID,
                            RELATED_UUID, SUPERSEDED),
                    "photo-cache resized entry at width " + width + " must be evicted");
            assertTrue(PhotoService.isAffectedCacheKey(new CompositeCacheKey(RELATED_UUID, width),
                            RELATED_UUID, SUPERSEDED),
                    "photo-resize-cache entry at width " + width + " must be evicted");
        }
    }

    @Test
    void leavesOtherPeoplesPhotosAlone() {
        assertFalse(PhotoService.isAffectedCacheKey(OTHER_UUID, RELATED_UUID, SUPERSEDED),
                "an unrelated photo must survive someone else's upload");
        assertFalse(PhotoService.isAffectedCacheKey("resized/64/" + OTHER_UUID, RELATED_UUID, SUPERSEDED),
                "an unrelated thumbnail must survive");
        assertFalse(PhotoService.isAffectedCacheKey(new CompositeCacheKey(OTHER_UUID, 512),
                        RELATED_UUID, SUPERSEDED),
                "a matching width is not a match — the uuid decides");
    }

    @Test
    void leavesTheSharedDefaultPlaceholderAlone() {
        // findPhotoByType / findPhotoByRelatedUUID fall back to this key for everyone; evicting it
        // on every upload would refetch it from S3 for the whole instance.
        assertFalse(PhotoService.isAffectedCacheKey("c297e216-e5cf-437d-9a1f-de840c7557e9",
                RELATED_UUID, SUPERSEDED));
    }

    @Test
    void toleratesKeyShapesItDoesNotRecognise() {
        assertFalse(PhotoService.isAffectedCacheKey(42, RELATED_UUID, SUPERSEDED));
        assertFalse(PhotoService.isAffectedCacheKey(null, RELATED_UUID, SUPERSEDED));
        assertFalse(PhotoService.isAffectedCacheKey(new CompositeCacheKey(42, 64), RELATED_UUID, SUPERSEDED));
        assertFalse(PhotoService.isAffectedCacheKey("resized/", RELATED_UUID, SUPERSEDED));
        assertFalse(PhotoService.isAffectedCacheKey("", RELATED_UUID, SUPERSEDED));
    }

    @Test
    void doesNotMatchOnASharedUuidSuffix() {
        // endsWith-style matching would wrongly evict a uuid that merely ends with the target.
        assertFalse(PhotoService.isAffectedCacheKey("resized/64/prefix-" + RELATED_UUID,
                RELATED_UUID, SUPERSEDED));
    }

    // --- resize failure levels ----------------------------------------------------------------
    // A photo this JVM cannot decode is not a system fault: resizeImage hands the original bytes
    // back and the caller serves them, so the request still succeeds. It was nevertheless logged at
    // ERROR with a stack trace and no mention of which photo or width. These pin the split — an
    // unreadable format is a quiet WARN carrying enough context to identify the object, everything
    // else stays ERROR.

    private static final String RESIZE_KEY = "resized/64/" + RELATED_UUID;

    /**
     * A WebP <em>container header</em> over 48 zero bytes — a damaged blob, not a picture. Tika
     * still calls it {@code image/webp} because it matches on magic alone.
     * <p>
     * This was named {@code webpBytes()} and stood in for "a webp avatar" while no WebP reader
     * existed, when the distinction could not be observed: nothing on the classpath could tell a
     * real webp from a broken one, so both produced the same
     * {@link net.coobird.thumbnailator.tasks.UnsupportedFormatException}. With
     * {@code imageio-webp} registered the two now diverge — the SPI claims these bytes and then
     * fails inside the decoder — so the fixture is renamed for what it actually is. Real webp
     * coverage is {@link #lossyWebp()} and {@link #losslessWebp()}.
     */
    private static byte[] damagedWebpHeader() {
        byte[] data = new byte[64];
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, data, 0, 4);
        System.arraycopy("WEBPVP8 ".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, data, 8, 8);
        return data;
    }

    private static byte[] realJpegBytes() throws Exception {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image, "jpg", baos), "test fixture must be a real JPEG");
        return baos.toByteArray();
    }

    @Test
    void undecodableFormatFallsBackToTheOriginalBytesWithoutAnError() {
        byte[] webp = damagedWebpHeader();

        byte[] result = service.resizeImage(webp, 64, 0, RESIZE_KEY);

        assertArrayEquals(webp, result, "the caller serves this, so the original must come back intact");
        assertEquals(List.of(), errorMessages(),
                "an avatar in a format ImageIO cannot read is a data condition, not a system fault");
    }

    @Test
    void undecodableFormatIsReportedAtWarnWithEnoughContextToFindThePhoto() {
        service.resizeImage(damagedWebpHeader(), 64, 0, RESIZE_KEY);

        List<String> warnings = messagesAt(Level.WARNING);
        assertEquals(1, warnings.size(), "expected exactly one WARN, got: " + allMessages());
        // Without these the message is unactionable — the one production occurrence named neither
        // the photo nor the width, which is why the offending object could not be identified.
        assertTrue(warnings.get(0).contains(RESIZE_KEY), "must name the S3 key: " + warnings.get(0));
        assertTrue(warnings.get(0).contains("64"), "must name the width: " + warnings.get(0));
        assertTrue(warnings.get(0).contains("image/webp"), "must name the detected type: " + warnings.get(0));
    }

    @Test
    void missingPhotoBytesAreNotReportedAsAResizeFailure() {
        // loadFromS3 answers byte[0] for an absent object, and an empty array raises the very same
        // UnsupportedFormatException as an exotic format. Decoding it would flood the WARN stream
        // with every user who has no photo — the exact regression that moved the S3 miss to DEBUG.
        byte[] result = service.resizeImage(new byte[0], 64, 0, RESIZE_KEY);

        assertEquals(0, result.length);
        assertEquals(List.of(), errorMessages());
        assertEquals(List.of(), messagesAt(Level.WARNING),
                "a user without a photo must not produce a resize warning");
    }

    @Test
    void aGenuineResizeFailureIsStillLoggedAsError() throws Exception {
        // A decodable JPEG with a rejected width: Thumbnailator raises IllegalArgumentException,
        // which is a real fault and must not be swept into the new WARN branch.
        byte[] result = service.resizeImage(realJpegBytes(), 0, 0, RESIZE_KEY);

        assertNotNull(result, "the fallback still applies");
        assertEquals(1, errorMessages().size(),
                "only unreadable formats were downgraded, got: " + allMessages());
        assertTrue(errorMessages().get(0).contains(RESIZE_KEY),
                "the error must identify the photo too: " + errorMessages().get(0));
        assertEquals(List.of(), messagesAt(Level.WARNING));
    }

    @Test
    void aDecodableImageIsActuallyResizedAndLogsNothing() throws Exception {
        byte[] result = service.resizeImage(realJpegBytes(), 4, 0, RESIZE_KEY);

        assertNotNull(result);
        assertTrue(result.length > 0);
        assertEquals(List.of(), errorMessages());
        assertEquals(List.of(), messagesAt(Level.WARNING), "the happy path must stay silent");
    }

    // --- square (cover-cropped) thumbnails ------------------------------------------------------
    // size(w, w) preserves aspect ratio and so bounds the LONG side. Every avatar frame in the app
    // is square/circular with object-cover and portraits are stored 2:1, so ?width=96 answered
    // 96x48 and the browser magnified it ~2x (the sidebar, at ?width=48 into a 48px box on a 2x
    // display, magnified 4x). That is the "some portraits are blurry, others are sharp" report:
    // sharp meant the stored photo happened to be square. These pin both shapes, because the same
    // endpoint serves client and team logos, which are legitimately wide and must NOT be cropped.

    /** A 2:1 portrait — the shape the upload cropper produces and the one that rendered blurry. */
    private static byte[] wideJpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                // Non-uniform: a flat image would survive any resize and prove nothing.
                image.setRGB(x, y, ((x * 7) % 256) << 16 | ((y * 11) % 256) << 8 | ((x + y) % 256));
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpg", baos), "test fixture must be a real JPEG");
        return baos.toByteArray();
    }

    private static int[] dimensionsOf(byte[] jpeg) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertNotNull(image, "resizeImage must answer something decodable");
        return new int[]{image.getWidth(), image.getHeight()};
    }

    @Test
    void aSquareRequestFillsTheFrameRatherThanBoundingTheLongSide() throws Exception {
        byte[] result = service.resizeImage(wideJpeg(1600, 800), 96, 96, RESIZE_KEY);

        assertArrayEquals(new int[]{96, 96}, dimensionsOf(result),
                "a 2:1 portrait must still deliver 96 rows of pixels to a 96px circular frame");
    }

    @Test
    void theWidthOnlyShapeStillFitsInsideSoWideLogosAreNeverCropped() throws Exception {
        // The regression guard for the fix itself: cover-cropping every request would centre-crop
        // client and team logos into squares, destroying them in the wide frames that render them.
        byte[] result = service.resizeImage(wideJpeg(1600, 800), 96, 0, RESIZE_KEY);

        assertArrayEquals(new int[]{96, 48}, dimensionsOf(result),
                "width alone must keep the legacy fit-inside behaviour");
    }

    @Test
    void aTallPortraitIsCoveredToo() throws Exception {
        byte[] result = service.resizeImage(wideJpeg(400, 1200), 96, 96, RESIZE_KEY);

        assertArrayEquals(new int[]{96, 96}, dimensionsOf(result),
                "cover must scale by the short side whichever side that is");
    }

    @Test
    void aSourceSmallerThanTheRequestIsNotEnlarged() throws Exception {
        // Thumbnailator's size() scales UP, which minted thumbnails claiming a resolution the
        // pixels do not carry: a 60x60 legacy avatar came back as a soft "256x256" that nothing
        // downstream could distinguish from a real one.
        byte[] square = service.resizeImage(wideJpeg(60, 60), 256, 256, RESIZE_KEY);
        assertArrayEquals(new int[]{60, 60}, dimensionsOf(square),
                "a small photo must answer small rather than being blown up server-side");

        byte[] fitted = service.resizeImage(wideJpeg(60, 60), 256, 0, RESIZE_KEY);
        assertArrayEquals(new int[]{60, 60}, dimensionsOf(fitted),
                "the width-only shape must not enlarge either");
    }

    @Test
    void aSourceLargerInOneDimensionOnlyIsStillCoveredExactly() throws Exception {
        // 200 wide is above the 96 request but 80 tall is below it. Cover scales by the larger
        // ratio, so the clamp must key off the SHORT side or this silently upscales.
        byte[] result = service.resizeImage(wideJpeg(200, 80), 96, 96, RESIZE_KEY);

        int[] size = dimensionsOf(result);
        assertEquals(size[0], size[1], "must stay square");
        assertTrue(size[0] <= 80, "must not exceed the 80px short side, got " + size[0] + "px");
    }

    @Test
    void anUndecodableFormatStillFallsBackWhenASquareIsRequested() {
        // naturalSize() answers null for webp; the request must then pass through untouched to the
        // same UnsupportedFormatException fallback rather than failing on a null dereference.
        byte[] webp = damagedWebpHeader();

        byte[] result = service.resizeImage(webp, 96, 96, RESIZE_KEY);

        assertArrayEquals(webp, result, "the caller serves this, so the original must come back");
        assertEquals(List.of(), errorMessages(), "an unreadable format is still not a system fault");
    }

    @Test
    void naturalSizeReadsTheHeaderAndAdmitsWhenItCannot() throws Exception {
        assertArrayEquals(new int[]{1600, 800}, PhotoService.naturalSize(wideJpeg(1600, 800)));
        assertNull(PhotoService.naturalSize(damagedWebpHeader()), "unknown must be null, never a zero size");
        assertNull(PhotoService.naturalSize(new byte[0]));
    }

    // --- the upload master ----------------------------------------------------------------------
    // resizeToUploadDimensions called size() unconditionally, which ENLARGES: the cropper hands
    // over an 800x400 canvas, so every portrait was stored as a 1600x800 upscale re-encoded at
    // q0.90 — no new detail, just doubled JPEG artefacts on the master every thumbnail derives from.

    @Test
    void anUploadSmallerThanTheBoundIsStoredAtItsOwnSizeNotEnlarged() throws Exception {
        byte[] stored = service.resizeToUploadDimensions(wideJpeg(800, 400), 1600, 800);

        assertArrayEquals(new int[]{800, 400}, dimensionsOf(stored),
                "upscaling the master adds no detail and only degrades every thumbnail below it");
    }

    @Test
    void anUploadLargerThanTheBoundIsStillShrunk() throws Exception {
        byte[] stored = service.resizeToUploadDimensions(wideJpeg(3000, 3000), 1024, 1024);

        assertArrayEquals(new int[]{1024, 1024}, dimensionsOf(stored), "the bound must still bite");
    }

    @Test
    void anUploadThatIsNotScaledIsStillReEncodedAsJpeg() throws Exception {
        // Load-bearing, not incidental: requireStorableImage runs on these bytes, and normalising
        // to JPEG is what collapses a polyglot upload into a plain raster image. Skipping the
        // encode for in-bounds images would reopen that hole.
        byte[] png = png();
        byte[] stored = service.resizeToUploadDimensions(png, 1600, 800);

        assertEquals("image/jpeg", service.detectMimeType(stored),
                "an in-bounds upload must still be normalised, not passed through verbatim");
    }

    // --- thumbnail keys -------------------------------------------------------------------------

    @Test
    void squareAndWidthOnlyThumbnailsDoNotShareAnS3Key() {
        // They hold different images at the same width. Sharing a key would make whichever was
        // generated first serve both the avatar frames and the logo frames.
        assertNotEquals(PhotoService.resizedKey(RELATED_UUID, 96, 96),
                PhotoService.resizedKey(RELATED_UUID, 96, 0));
        assertEquals("resized/96/" + RELATED_UUID, PhotoService.resizedKey(RELATED_UUID, 96, 0),
                "the width-only shape must keep its existing key, so no purge is needed to deploy");
        assertEquals("resized/96x96/" + RELATED_UUID, PhotoService.resizedKey(RELATED_UUID, 96, 96));
    }

    @Test
    void anUploadInvalidatesTheSquareThumbnailsToo() {
        // getResizedPhoto short-circuits on s3ObjectExists, so a square thumbnail left behind by an
        // upload serves the PREVIOUS photo forever — in the one shape the UI actually renders.
        assertTrue(PhotoService.isResizedKeyFor("resized/96x96/" + RELATED_UUID, RELATED_UUID));
        assertTrue(PhotoService.isResizedKeyFor("resized/128x128/" + RELATED_UUID, RELATED_UUID));
        assertFalse(PhotoService.isResizedKeyFor("resized/96x96/" + OTHER_UUID, RELATED_UUID));
    }

    private List<String> messagesAt(Level level) {
        return logHandler.snapshot().stream()
                .filter(r -> r.getLevel().intValue() == level.intValue())
                .map(PhotoServiceTest::render)
                .toList();
    }

    // --- superseding an upload must not delete the user's documents -----------------------------
    // The files table is shared across domains keyed on the same relateduuid. The upload path used
    // to supersede on "relateduuid = ?1" alone, so replacing an employee's portrait deleted every
    // DOCUMENT row that employee owned — on EVERY upload, because the callers always send a fresh
    // uuid and therefore always take the delete-and-insert branch. In production 632 of 802 document
    // rows are keyed on a user uuid, so the blast radius was most of the table.
    //
    // update() cannot be exercised here: it drives Panache statics and persist(), which need a
    // session, and the local DB is read-only. What IS pinned is the invariant that made the bug
    // possible — the supersede predicate must be type-scoped, and the lookup and the delete must
    // use the same one.

    @Test
    void supersedeQueryIsScopedByTypeSoItCannotDeleteDocuments() {
        assertTrue(PhotoService.SUPERSEDED_ROWS_QUERY.contains("type"),
                "without a type predicate this deletes the user's DOCUMENT rows too, got: "
                        + PhotoService.SUPERSEDED_ROWS_QUERY);
        assertTrue(PhotoService.SUPERSEDED_ROWS_QUERY.contains("relateduuid"),
                "must still be scoped to the one photo owner");
        // Two positional parameters — relateduuid and type. A query carrying only ?1 has lost the
        // type binding regardless of whether the word "type" survived in a comment or alias.
        assertTrue(PhotoService.SUPERSEDED_ROWS_QUERY.contains("?1")
                        && PhotoService.SUPERSEDED_ROWS_QUERY.contains("?2"),
                "type must be a bound parameter, got: " + PhotoService.SUPERSEDED_ROWS_QUERY);
    }

    // --- stale thumbnail cleanup ----------------------------------------------------------------
    // getResizedPhoto short-circuits on s3ObjectExists, so any thumbnail left behind by an upload
    // serves the PREVIOUS photo forever. Deleting only COMMON_THUMBNAIL_WIDTHS missed the ad-hoc
    // sizes the UI actually asks for (28, 36, 44, 56, 1), so those kept the old avatar permanently.

    @Test
    void everyResizedWidthIsRecognisedAsStaleNotJustTheCommonOnes() {
        // isResizedKeyFor is what selects keys out of the bucket listing, so it must match the
        // ad-hoc widths too — these are real values requested by the frontend today.
        for (int width : new int[]{1, 28, 36, 44, 56, 200, 1337}) {
            assertTrue(PhotoService.isResizedKeyFor("resized/" + width + "/" + RELATED_UUID, RELATED_UUID),
                    "width " + width + " is requested via ?width= and must be cleaned up");
        }
    }

    @Test
    void thumbnailCleanupIgnoresOtherPeoplesThumbnails() {
        assertFalse(PhotoService.isResizedKeyFor("resized/64/" + OTHER_UUID, RELATED_UUID),
                "an unrelated user's thumbnail must survive this upload");
        assertFalse(PhotoService.isResizedKeyFor("resized/64/prefix-" + RELATED_UUID, RELATED_UUID),
                "suffix matching would delete a uuid that merely ends with the target");
        assertFalse(PhotoService.isResizedKeyFor(RELATED_UUID, RELATED_UUID),
                "the original photo object is not a thumbnail and must not be deleted");
    }

    // --- upload format allowlist ----------------------------------------------------------------
    // The read path derives the response Content-Type from the stored bytes, so whatever gets
    // stored is what a browser is later told it is receiving. A stored SVG therefore came back as
    // image/svg+xml and executed script on the intranet origin when the URL was opened directly.
    // Nothing rejected it: the four REST entry points ran Tika only to pick a filename extension,
    // and resizeToUploadDimensions logs a decode failure at WARN and stores the original bytes.
    // These pin the allowlist and, just as importantly, pin that webp is still accepted — the
    // stricter "must be ImageIO-decodable" alternative would have rejected every webp and heic.

    /** A minimal SVG carrying script — the payload the allowlist exists to stop. */
    private static byte[] svgBytes() {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\">"
                + "<script>alert(document.cookie)</script>"
                + "<circle cx=\"50\" cy=\"50\" r=\"40\"/></svg>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static File photoOf(byte[] data) {
        File photo = new File(UUID, RELATED_UUID, "PHOTO");
        photo.setFile(data);
        return photo;
    }

    @Test
    void rasterFormatsAreStorable() {
        for (String mimeType : new String[]{"image/jpeg", "image/jpg", "image/png", "image/gif",
                "image/bmp", "image/tiff", "image/webp", "image/x-icon"}) {
            assertTrue(PhotoService.isStorableImageType(mimeType), mimeType + " must stay accepted");
        }
    }

    @Test
    void webpStaysStorableEvenThoughImageIoCannotDecodeIt() {
        // Load-bearing: gating on "Thumbnailator can decode it" instead of on the type would reject
        // every webp and heic upload, which succeed today and are simply stored unresized.
        assertTrue(PhotoService.isStorableImageType("image/webp"));
        assertDoesNotThrow(() -> service.requireStorableImage(photoOf(damagedWebpHeader())));
    }

    @Test
    void svgIsNotStorable() {
        assertFalse(PhotoService.isStorableImageType("image/svg+xml"),
                "SVG executes script when served under its own type — it must never be stored");
    }

    @Test
    void nonImageTypesAreNotStorable() {
        for (String mimeType : new String[]{"text/html", "application/xml", "application/pdf",
                "application/octet-stream", "text/plain", "application/xhtml+xml", ""}) {
            assertFalse(PhotoService.isStorableImageType(mimeType), mimeType + " must be rejected");
        }
        assertFalse(PhotoService.isStorableImageType(null));
    }

    @Test
    void allowlistMatchingIgnoresParametersAndCasing() {
        // Tika answers a bare type today, but an equality test against the raw header would break
        // silently the day it does not.
        assertTrue(PhotoService.isStorableImageType("image/jpeg; charset=ISO-8859-1"));
        assertTrue(PhotoService.isStorableImageType("IMAGE/PNG"));
        assertTrue(PhotoService.isStorableImageType("  image/png  "));
        assertFalse(PhotoService.isStorableImageType("image/svg+xml; charset=utf-8"),
                "parameters must not be a way past the allowlist either");
    }

    @Test
    void anSvgUploadIsRejectedBeforeAnythingIsStored() {
        WebApplicationException rejected = assertThrows(WebApplicationException.class,
                () -> service.requireStorableImage(photoOf(svgBytes())));

        assertEquals(400, rejected.getResponse().getStatus(),
                "a bad upload is the caller's fault, not a 500");
        // The guard runs before update() touches S3 or the files table.
        org.mockito.Mockito.verifyNoInteractions(s3);
    }

    @Test
    void anHtmlUploadIsRejected() {
        byte[] html = "<html><body><script>alert(1)</script></body></html>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(400, assertThrows(WebApplicationException.class,
                () -> service.requireStorableImage(photoOf(html))).getResponse().getStatus());
    }

    @Test
    void anEmptyUploadIsRejectedAsABadRequestRatherThanFailingInS3() {
        for (byte[] nothing : new byte[][]{new byte[0], null}) {
            assertEquals(400, assertThrows(WebApplicationException.class,
                    () -> service.requireStorableImage(photoOf(nothing))).getResponse().getStatus());
        }
        org.mockito.Mockito.verifyNoInteractions(s3);
    }

    @Test
    void aRealJpegUploadIsAccepted() throws Exception {
        assertDoesNotThrow(() -> service.requireStorableImage(photoOf(realJpegBytes())));
    }

    @Test
    void everyPublicEntryPointRejectsAnSvgBeforeReachingStorage() {
        // update() is where the guard lives, and it cannot be exercised directly here — it drives
        // Panache statics that need a session. These three call the real entry points instead and
        // pin that the guard is genuinely on that path: each must fail with a 400 rather than
        // reaching Panache (which, without a session, would fail with something else entirely).
        // Without this, deleting requireStorableImage() from update() would leave every other test
        // in this section green.
        for (java.util.function.Consumer<File> entryPoint : List.<java.util.function.Consumer<File>>of(
                service::updatePhoto, service::updateLogo, service::updatePortrait)) {
            WebApplicationException rejected = assertThrows(WebApplicationException.class,
                    () -> entryPoint.accept(photoOf(svgBytes())));
            assertEquals(400, rejected.getResponse().getStatus());
        }
        org.mockito.Mockito.verifyNoInteractions(s3);
    }

    @Test
    void svgNoLongerHasAFilenameExtensionMapping() {
        // PublicResource builds the stored filename from this map. Leaving the mapping in place
        // would keep the upload path reading as though storing an SVG were anticipated.
        assertEquals(".bin", service.extensionFromMimeType("image/svg+xml"));
        assertEquals(".jpg", service.extensionFromMimeType("image/jpeg"),
                "the raster mappings must be untouched");
        assertEquals(".webp", service.extensionFromMimeType("image/webp"));
    }

    private void throwOnGetObject(S3Exception exception) {
        doThrow(exception).when(s3).getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
    }

    private List<String> errorMessages() {
        return logHandler.snapshot().stream()
                .filter(r -> r.getLevel().intValue() >= Level.SEVERE.intValue())
                .map(LogRecord::getMessage)
                .toList();
    }

    private List<String> recordsAtMost(Level level) {
        return logHandler.snapshot().stream()
                .filter(r -> r.getLevel().intValue() <= level.intValue())
                .map(PhotoServiceTest::render)
                .toList();
    }

    private List<String> allMessages() {
        return logHandler.snapshot().stream().map(PhotoServiceTest::render).toList();
    }

    private static String render(LogRecord record) {
        String message = record.getMessage();
        Object[] parameters = record.getParameters();
        if (message == null || parameters == null || parameters.length == 0) {
            return String.valueOf(message);
        }
        // jboss-logging defers formatting to the handler, so debugf() arrives unformatted.
        return message + " " + java.util.Arrays.toString(parameters);
    }

    // --- the fallback must not write, and must keep working -----------------------------------
    // findPhotoByRelatedUUID used to persist a bare File(uuid, relateduuid, "PHOTO") on a miss and
    // return that. Two things went wrong. A plain GET wrote to the database, which by 2026-08-19
    // had left 7,304 of the 7,699 type='PHOTO' rows in production as these stubs. And because the
    // stub carries type='PHOTO', the NEXT read FOUND it and loaded S3 by its uuid — a key nothing
    // ever wrote — so the caller got byte[0]. The default image was served exactly once per
    // entity and never again. These pin both halves.
    //
    // How the "no write" half is enforced here: these run outside Arc, so the QuarkusTransaction
    // the persist needed cannot resolve a transaction manager. Reintroducing the write therefore
    // does not quietly pass — all three fallback tests below error on
    // `Arc.container()` being null. Verified by putting the persist back and watching them fail.

    /** A tiny real PNG, so Thumbnailator has something it can actually decode. */
    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /** Serves {@code contents} by S3 key; any other key behaves as a genuine 404. */
    private void s3Contains(Map<String, byte[]> contents) {
        doAnswer(invocation -> {
            String key = invocation.<GetObjectRequest>getArgument(0).key();
            byte[] bytes = contents.get(key);
            if (bytes == null) {
                throw NoSuchKeyException.builder().statusCode(404).build();
            }
            ResponseTransformer<GetObjectResponse, ?> transformer = invocation.getArgument(1);
            return transformer.transform(GetObjectResponse.builder().build(),
                    AbortableInputStream.create(new ByteArrayInputStream(bytes)));
        }).when(s3).getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
    }

    /** No thumbnail cached in S3 yet — the normal state on a first request. */
    private void noThumbnailInS3() {
        doThrow(NoSuchKeyException.builder().statusCode(404).build())
                .when(s3).headObject(any(HeadObjectRequest.class));
    }

    /** A PhotoService whose database lookup is stubbed, so no Panache enhancement is needed. */
    private PhotoService serviceWithStoredPhoto(Optional<File> stored) {
        PhotoService stubbed = new PhotoService(s3) {
            @Override
            Optional<File> findStoredPhoto(String relateduuid) {
                return stored;
            }
        };
        stubbed.bucketName = BUCKET;
        return stubbed;
    }

    @Test
    void aMissAnswersEmptyAndFetchesNothingFromS3() {
        File result = serviceWithStoredPhoto(Optional.empty()).findPhotoByRelatedUUID(RELATED_UUID);

        assertEquals(0, result.getFile().length,
                "an absent photo is an empty payload — that is what UserAvatar's DiceBear/initials "
                        + "fallback and the legacy client's length>0 guard both key off");
        assertEquals(RELATED_UUID, result.getRelateduuid());
        // Serving the shared silhouette would show one identical face for every photo-less person,
        // and would cost an S3 round trip on a path whose volume scales with page views.
        verify(s3, never()).getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
    }

    @Test
    void everyMissBehavesTheSame_notJustTheFirst() {
        PhotoService stubbed = serviceWithStoredPhoto(Optional.empty());

        // The old code's first call persisted a stub that its own second call then found, so the
        // two reads took different branches. They must now be indistinguishable.
        assertEquals(0, stubbed.findPhotoByRelatedUUID(RELATED_UUID).getFile().length);
        assertEquals(0, stubbed.findPhotoByRelatedUUID(RELATED_UUID).getFile().length);
    }

    @Test
    void aMissAtAWidthPublishesNothingAndCachesNothing() throws Exception {
        noThumbnailInS3();

        byte[] resized = serviceWithStoredPhoto(Optional.empty()).getResizedPhoto(RELATED_UUID, 64, 0);

        assertEquals(0, resized.length, "UserAvatar requests ?width= — this is the path it hits");
        // Publishing here would trade the placeholder ROWS this change removes for one S3 object
        // per entity per width, which no later upload would clean up at ad-hoc widths.
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void aStoredPhotoIsStillResizedAndCached() throws Exception {
        File stored = new File(UUID, RELATED_UUID, "PHOTO");
        s3Contains(Map.of(UUID, png()));
        noThumbnailInS3();

        byte[] resized = serviceWithStoredPhoto(Optional.of(stored)).getResizedPhoto(RELATED_UUID, 64, 0);

        assertNotEquals(0, resized.length, "a real photo must still resize");
    }

    // --- webp: the format production could not decode -------------------------------------------
    // Stock JDK 21 ImageIO ships readers for JPEG/PNG/GIF/BMP/TIFF only. Every webp avatar therefore
    // raised UnsupportedFormatException, resizeImage fell back to the original bytes, and
    // getResizedPhoto published those bytes to the thumbnail key — so production served full-size
    // webp portraits into 96px circles, 93 times across ~40 users in a single day, and the S3 object
    // written under resized/96x96/{uuid} made the condition permanent for each of them.
    //
    // The fixtures below are REAL webps, which matters twice over. The pre-existing fixture
    // (damagedWebpHeader) is a header over zero bytes, so it exercises the failure branch in both
    // worlds and could never have caught this. And webp has two independent decoder paths inside
    // TwelveMonkeys — lossy VP8 and lossless VP8L — so a fixture in one format proves nothing about
    // the other. Production's bytes are lossy VP8, the browser/CDN default.

    /**
     * A real 200x100 lossy (VP8) WebP — the encoding production's avatars actually arrive in.
     * <p>
     * Produced once with {@code cwebp 1.5.0 -q 40} from a 200x100 gradient with a filled ellipse
     * (non-uniform on purpose, so a resize cannot be mistaken for a solid colour). Inlined rather
     * than added as a binary resource because this suite has no binary fixtures and
     * {@code pom.xml} declares no {@code <testResources>} filtering rule to keep one safe.
     */
    private static byte[] lossyWebp() {
        return java.util.Base64.getDecoder().decode(
                "UklGRigCAABXRUJQVlA4IBwCAAAwEgCdASrIAGQAPu12tlQpqCUjJPpIGTAdiWNu3Vr+MZlUT/ngCaAlQPZlRAetAPp/LvzV"
                        + "35q781cmX5gerBDt0Fk3qawDwcAs7/LvzOyneqbynw/h35CDcQIUEUWYd8osy0yhFRM6CRTXcuXqGy2twCOQYa1MIN1PpRFe"
                        + "TUI6YOHouZ7Jgwz2W87Dd7iF/LtfYhHsQj2IR66wAP7oUKn/Vfq72xK/9enNCsZ2cKpNkVd+nB7Tw5qbz6g52DsS0htYTY7g"
                        + "OvJl5NqZlHcMkqHDF1UaIB59r/HcTxvzmBniK6fUf9vrnEw5HXU2xID38pcV7k3cr2g5NDP1j2w88Ok0952qQlYPbzQymqO6"
                        + "zhRM0DIa1gmfo2JhYoALFJG2MVBRn7ARbKnYzKgUDb2YH7nk/IkjD5oLIk6CUw3BOdUuHVaHgB3iZWxY69B2SqBEQbuVx8ua"
                        + "2n41x16KH1GjbQQ2g64P3/aViRstFnCvX+C+eNg0Ugsvfi0ijUO/OBJdBFC8RA3DQ5mt9oaakKQKlVP3+caPk/OPBBXxdBe4"
                        + "fggIE/cuea5V2QerXZSKXCKNEYyzYHK1LcFQg9PuSh5bnUQwS43Qj+TNTXla5/DSp40Yucsc9rHlVaizJw5H0I4Oc0SfYrg+"
                        + "2YwK18pIrEQzW9rvJqN+Y/L8DTfvQqOv2VlXXxsCwZ3tnzfcK+qDcsqY+bJf75y5KK2SdaRQAAA=");
    }

    /** The same 200x100 image as lossless VP8L — TwelveMonkeys decodes it through different code. */
    private static byte[] losslessWebp() {
        return java.util.Base64.getDecoder().decode(
                "UklGRuQBAABXRUJQVlA4TNcBAAAvx8AYABkyaRty17/kMQsR/U+bPBtFbRtJnoM/qIWzCFbznQWhtm0bRin/n5fZk+ELMitq"
                        + "2wYO+TkU9/o/ARRwv14SAAj0Gv+xJebA+sJh2zaSlBJPlarM3U0Ozv3nSJJs0zrRjzu08W2M3vjbtv3/DhluI0lSpFry9piZ"
                        + "/iW26M6QWSdex3CkRpIjxUDCuN1Y9lTmBTd6FbHFUVBrUOBlFQX+1z7yTJ/I/4/hT+KQ5bwQRNQQXNUCpAxJd6jBkhD/pksP"
                        + "/p/uxK/tVEQkWvgKeiV5kyt8hP2ifAkV3pIJVf5Ewks2Iwv9A514yqd0AXHCQzknjHVfo14oqDDhph3EXYl3j4KrftTZTUlw"
                        + "X6MOLagg4WwexkVJ/L5GX6Cg1qinC8TAwXkcRyXZ+xr9poJao09T4PmIEFh7rHFCmFt5vjAfeTRPqFwMm2moHGucFHg86wVa"
                        + "Z8vTLLT2CPVCkl9eUPcBvdX8Ogm9o6Ci/AsMJrPnORjsBR6O/t/dYFKmYPIO1Oc3LjMw+0SaHxmVCVh8Q83zDKQfVr9c81U9"
                        + "6YUNyearRehIJ+xIN7fX8l1wUAn3QTSkA0465X6Omr8KFxN1X0pFzsPNJd5fU/LH8HDv833bf/6PFAEA");
    }

    /** A real 40x40 lossy WebP — smaller than any thumbnail box, for the no-upscale clamp. */
    private static byte[] smallWebp() {
        return java.util.Base64.getDecoder().decode(
                "UklGRs4AAABXRUJQVlA4IMIAAAAwBgCdASooACgAPu1mqk6ppaOiLigBMB2JZADBEA108//wqSnHFOPl8rRuC3RFktGxT2km"
                        + "3/IknzQoAP6cUG2gTWzEPPof+e/8dTZZjQd2nwmvn6XyJFPNcMT6npEQbIkPHdFN8ubf2HwmGGzTHZSxcjid3D9sJiuW8qjy"
                        + "VepAZLNGGIStDymhR0c6eP0W52xQGMAxLqWwvbPl7zdsfQUu11dMDK3mD4LDWHYRPs3JaNxf65kjSaOvtsCgiq8kAAAAAA==");
    }

    @Test
    void aWebpImageReaderIsRegisteredInThisJvm() {
        // The whole fix in one assertion. This is a direct probe of the ImageIO reader registry, so
        // it cannot pass by accident: before com.twelvemonkeys.imageio:imageio-webp was added it
        // was false, and every other webp test below depends on it.
        assertTrue(ImageIOPluginRegistrar.hasReaderFor("image/webp"),
                "imageio-webp must be on the classpath — without it every webp avatar is served "
                        + "at full size and the thumbnail cache is poisoned with the original");
    }

    @Test
    void naturalSizeNowReadsWebpDimensions() {
        // naturalSize answered null for webp, which made coverBox/fitBox pass the request through
        // unclamped and left the caller unable to tell a thumbnail from an original.
        assertArrayEquals(new int[]{200, 100}, PhotoService.naturalSize(lossyWebp()));
        assertArrayEquals(new int[]{200, 100}, PhotoService.naturalSize(losslessWebp()));
    }

    @Test
    void aRealWebpIsGenuinelyResizedIntoASquareThumbnail() throws Exception {
        byte[] source = lossyWebp();

        byte[] result = service.resizeImage(source, 96, 96, RESIZE_KEY);

        // Four independent properties, because the fallback returns the input array verbatim and
        // any single check could in principle be satisfied by accident.
        assertFalse(java.util.Arrays.equals(source, result), "the fallback hands back its input");
        assertEquals("image/jpeg", service.detectMimeType(result), "a real resize re-encodes as JPEG");
        assertArrayEquals(new int[]{96, 96}, dimensionsOf(result), "a circular 96px frame needs 96 rows");
        // Pixels, not bytes. Byte count is the production symptom — a 37,882-byte master answering
        // a 96px frame where a real thumbnail is ~2.5KB — but it cannot be asserted on a fixture
        // this small: 200x100 of webp is 560 bytes, which JPEG does not beat at any quality.
        int[] master = PhotoService.naturalSize(source);
        int[] thumbnail = dimensionsOf(result);
        assertTrue(thumbnail[0] * thumbnail[1] < master[0] * master[1],
                "the thumbnail must carry fewer pixels than the master it came from");
        assertEquals(List.of(), errorMessages(), "decoding a webp is now the happy path");
        assertEquals(List.of(), messagesAt(Level.WARNING), "and must be silent");
    }

    @Test
    void aLosslessWebpIsResizedToo() throws Exception {
        // VP8L is a separate decoder inside TwelveMonkeys; passing on lossy proves nothing here.
        byte[] result = service.resizeImage(losslessWebp(), 96, 96, RESIZE_KEY);

        assertEquals("image/jpeg", service.detectMimeType(result));
        assertArrayEquals(new int[]{96, 96}, dimensionsOf(result));
    }

    @Test
    void theWidthOnlyShapeOfAWebpStillFitsInsideRatherThanCropping() throws Exception {
        // Client and team logos arrive as webp too, and centre-cropping them destroys them.
        byte[] result = service.resizeImage(lossyWebp(), 96, 0, RESIZE_KEY);

        assertArrayEquals(new int[]{96, 48}, dimensionsOf(result),
                "width alone must keep bounding the long side for the logo callers");
    }

    @Test
    void aWebpSmallerThanTheRequestedBoxIsNotEnlarged() throws Exception {
        // Now that naturalSize can read webp, the shrink-only clamp finally applies to it. Before
        // this it answered null, the box passed through unclamped, and a 40x40 webp would have been
        // blown up to a "256x256" thumbnail carrying 40px of real detail.
        byte[] result = service.resizeImage(smallWebp(), 256, 256, RESIZE_KEY);

        assertArrayEquals(new int[]{40, 40}, dimensionsOf(result),
                "upscaling claims a resolution the pixels do not carry");
    }

    @Test
    void aWebpUploadIsNormalisedToJpegInsteadOfBeingStoredRaw() throws Exception {
        // The write-path half of the same root cause: resizeToUploadDimensions logged
        // "Image resize failed, saving original" and stored the raw webp, so the master every
        // thumbnail derives from was itself undecodable.
        byte[] stored = service.resizeToUploadDimensions(lossyWebp(), 1024, 1024);

        assertEquals("image/jpeg", service.detectMimeType(stored),
                "a webp upload must be normalised like every other in-bounds upload");
        assertArrayEquals(new int[]{200, 100}, dimensionsOf(stored), "and must not be enlarged");
    }

    @Test
    void aDamagedWebpStaysAWarningRatherThanBecomingAnError() {
        // Registering a reader moves this blob from "no SPI claims it" to "claimed, then fails in
        // the decoder" — a different exception type on the same data condition. Left unhandled that
        // would have silently promoted this defect's own 93-a-day WARN stream to ERROR.
        service.resizeImage(damagedWebpHeader(), 96, 96, RESIZE_KEY);

        assertEquals(List.of(), errorMessages(),
                "a corrupt stored blob is a data condition, not a system fault: " + allMessages());
        assertEquals(1, messagesAt(Level.WARNING).size(), "still exactly one WARN: " + allMessages());
    }

    // --- the poisoned thumbnail cache -----------------------------------------------------------
    // getResizedPhoto published whatever resizeImage returned, and resizeImage returns its INPUT
    // when it cannot scale. So a format this JVM could not decode had its full-size original
    // written to resized/{size}/{uuid} — and because getResizedPhoto short-circuits on
    // s3ObjectExists, that copy was then served under the thumbnail key forever. This is why
    // registering the WebP reader on its own would not have repaired one existing user.

    /** A thumbnail object already present in S3 under its key. */
    private void thumbnailInS3() {
        org.mockito.Mockito
                .doReturn(software.amazon.awssdk.services.s3.model.HeadObjectResponse.builder().build())
                .when(s3).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void aPhotoThisJvmCannotDecodeIsNeverPublishedAsAThumbnail() {
        File stored = new File(UUID, RELATED_UUID, "PHOTO");
        s3Contains(Map.of(UUID, heicBytes()));
        noThumbnailInS3();

        serviceWithStoredPhoto(Optional.of(stored)).getResizedPhoto(RELATED_UUID, 96, 96);

        // after(...) rather than a bare never(): the publish is async, so an immediate assertion
        // would pass even if the write were still queued.
        verify(s3, org.mockito.Mockito.after(300).never())
                .putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void aPhotoRowThatIsNotAnImageAnswersEmptyRatherThanServingADocument() {
        // Four relateduuids in production hold text/plain under type='PHOTO' — rows predating
        // requireStorableImage. Handing those back made FileResource serve a text file as a
        // download named after a person; empty instead is the signal UserAvatar's DiceBear-then-
        // initials fallback keys off, so those four render a normal avatar.
        File stored = new File(UUID, RELATED_UUID, "PHOTO");
        s3Contains(Map.of(UUID, "not an image at all, just text".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        noThumbnailInS3();

        byte[] result = serviceWithStoredPhoto(Optional.of(stored)).getResizedPhoto(RELATED_UUID, 96, 96);

        assertEquals(0, result.length, "a non-image row must answer empty, not its bytes");
        verify(s3, org.mockito.Mockito.after(300).never())
                .putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void theStorableAllowlistIsWhatTheReadGuardUses() {
        // The read guard and the upload guard must stay the same set, or a format one accepts and
        // the other refuses becomes unreachable-but-stored.
        assertTrue(PhotoService.isStorableImageType("image/webp"), "webp is decodable now, and was always storable");
        assertTrue(PhotoService.isStorableImageType("image/jpeg"));
        assertFalse(PhotoService.isStorableImageType("text/plain"));
        assertFalse(PhotoService.isStorableImageType("image/svg+xml"));
    }

    @Test
    void aRealWebpIsPublishedAsAThumbnailEndToEnd() throws Exception {
        File stored = new File(UUID, RELATED_UUID, "PHOTO");
        s3Contains(Map.of(UUID, lossyWebp()));
        noThumbnailInS3();

        byte[] result = serviceWithStoredPhoto(Optional.of(stored)).getResizedPhoto(RELATED_UUID, 96, 96);

        assertArrayEquals(new int[]{96, 96}, dimensionsOf(result), "resize-on-read must work for webp");
        assertEquals("image/jpeg", service.detectMimeType(result));
        verify(s3, org.mockito.Mockito.timeout(2000))
                .putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void anUnresizedCopyCachedUnderAThumbnailKeyIsDiscardedAndRegenerated() throws Exception {
        // Exactly the state production is in for ~40 users: the thumbnail key holds the full-size
        // original, put there by the pre-fix fallback. Without this repair the s3ObjectExists
        // short-circuit means the working resize path is never reached again for that key.
        File stored = new File(UUID, RELATED_UUID, "PHOTO");
        String poisonedKey = PhotoService.resizedKey(RELATED_UUID, 96, 96);
        s3Contains(Map.of(UUID, lossyWebp(), poisonedKey, lossyWebp()));
        thumbnailInS3();

        byte[] result = serviceWithStoredPhoto(Optional.of(stored)).getResizedPhoto(RELATED_UUID, 96, 96);

        assertArrayEquals(new int[]{96, 96}, dimensionsOf(result),
                "the poisoned entry must be regenerated, not served");
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
        verify(s3, org.mockito.Mockito.timeout(2000))
                .putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void aGenuineThumbnailAlreadyInS3IsServedUntouched() throws Exception {
        // The counterpart guard: the repair must not fire on a healthy cache entry, or every avatar
        // request would delete and regenerate its own thumbnail.
        File stored = new File(UUID, RELATED_UUID, "PHOTO");
        String key = PhotoService.resizedKey(RELATED_UUID, 96, 96);
        byte[] realThumbnail = service.resizeImage(lossyWebp(), 96, 96, key);
        s3Contains(Map.of(UUID, lossyWebp(), key, realThumbnail));
        thumbnailInS3();

        byte[] result = serviceWithStoredPhoto(Optional.of(stored)).getResizedPhoto(RELATED_UUID, 96, 96);

        assertArrayEquals(realThumbnail, result, "a healthy thumbnail must be served as-is");
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void isUnresizedCopyRecognisesAnOriginalStoredUnderAThumbnailKey() throws Exception {
        // Larger than the box in either dimension — the signature of a published fallback.
        assertTrue(PhotoService.isUnresizedCopy(wideJpeg(1600, 800), 96, 96));
        assertTrue(PhotoService.isUnresizedCopy(wideJpeg(200, 100), 96, 0),
                "width-only keys bound the long side, so 200px wide is still oversized");
        // Undecodable bytes were never produced by this service's JPEG encoder.
        assertTrue(PhotoService.isUnresizedCopy(heicBytes(), 96, 96));
    }

    @Test
    void isUnresizedCopyLeavesGenuineThumbnailsAlone() throws Exception {
        assertFalse(PhotoService.isUnresizedCopy(wideJpeg(96, 96), 96, 96));
        assertFalse(PhotoService.isUnresizedCopy(wideJpeg(96, 48), 96, 0));
        // A clamped small source answers below the box and must not be mistaken for an original.
        assertFalse(PhotoService.isUnresizedCopy(wideJpeg(40, 40), 256, 256));
        assertFalse(PhotoService.isUnresizedCopy(new byte[0], 96, 96), "absent is not poisoned");
    }

    /** A format no reader on this classpath claims — HEIC and AVIF remain undecodable after the fix. */
    private static byte[] heicBytes() {
        byte[] data = new byte[64];
        data[3] = 0x20;
        System.arraycopy("ftypheic".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, data, 4, 8);
        return data;
    }

    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }

        List<LogRecord> snapshot() {
            synchronized (records) {
                return List.copyOf(records);
            }
        }
    }
}
