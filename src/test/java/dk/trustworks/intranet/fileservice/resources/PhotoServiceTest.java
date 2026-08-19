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

    /** WebP header. Stock JDK ImageIO has no reader for it, so Thumbnailator cannot decode it. */
    private static byte[] webpBytes() {
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
        byte[] webp = webpBytes();

        byte[] result = service.resizeImage(webp, 64, RESIZE_KEY);

        assertArrayEquals(webp, result, "the caller serves this, so the original must come back intact");
        assertEquals(List.of(), errorMessages(),
                "an avatar in a format ImageIO cannot read is a data condition, not a system fault");
    }

    @Test
    void undecodableFormatIsReportedAtWarnWithEnoughContextToFindThePhoto() {
        service.resizeImage(webpBytes(), 64, RESIZE_KEY);

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
        byte[] result = service.resizeImage(new byte[0], 64, RESIZE_KEY);

        assertEquals(0, result.length);
        assertEquals(List.of(), errorMessages());
        assertEquals(List.of(), messagesAt(Level.WARNING),
                "a user without a photo must not produce a resize warning");
    }

    @Test
    void aGenuineResizeFailureIsStillLoggedAsError() throws Exception {
        // A decodable JPEG with a rejected width: Thumbnailator raises IllegalArgumentException,
        // which is a real fault and must not be swept into the new WARN branch.
        byte[] result = service.resizeImage(realJpegBytes(), 0, RESIZE_KEY);

        assertNotNull(result, "the fallback still applies");
        assertEquals(1, errorMessages().size(),
                "only unreadable formats were downgraded, got: " + allMessages());
        assertTrue(errorMessages().get(0).contains(RESIZE_KEY),
                "the error must identify the photo too: " + errorMessages().get(0));
        assertEquals(List.of(), messagesAt(Level.WARNING));
    }

    @Test
    void aDecodableImageIsActuallyResizedAndLogsNothing() throws Exception {
        byte[] result = service.resizeImage(realJpegBytes(), 4, RESIZE_KEY);

        assertNotNull(result);
        assertTrue(result.length > 0);
        assertEquals(List.of(), errorMessages());
        assertEquals(List.of(), messagesAt(Level.WARNING), "the happy path must stay silent");
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
        assertDoesNotThrow(() -> service.requireStorableImage(photoOf(webpBytes())));
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

        byte[] resized = serviceWithStoredPhoto(Optional.empty()).getResizedPhoto(RELATED_UUID, 64);

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

        byte[] resized = serviceWithStoredPhoto(Optional.of(stored)).getResizedPhoto(RELATED_UUID, 64);

        assertNotEquals(0, resized.length, "a real photo must still resize");
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
