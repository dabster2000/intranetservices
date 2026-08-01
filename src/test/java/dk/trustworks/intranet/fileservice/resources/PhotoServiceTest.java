package dk.trustworks.intranet.fileservice.resources;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AccessDeniedException;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

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
