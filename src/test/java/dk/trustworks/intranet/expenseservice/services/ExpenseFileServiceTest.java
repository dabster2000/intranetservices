package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.dto.ExpenseFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AccessDeniedException;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ExpenseFileServiceTest {

    private static final String BUCKET = "expensefiles";
    private static final String UUID = "11111111-2222-3333-4444-555555555555";

    @Mock
    S3Client s3;

    ExpenseFileService service;

    @BeforeEach
    void setUp() {
        service = new ExpenseFileService(s3);
        service.bucketName = BUCKET;
    }

    @Test
    void getFileByIdReadsTheKnownKeyDirectly() {
        ExpenseFile result = service.getFileById(UUID);

        ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(requestCaptor.capture(), any(ResponseTransformer.class));
        verifyNoMoreInteractions(s3);

        assertNotNull(result);
        assertEquals(BUCKET, requestCaptor.getValue().bucket());
        assertEquals(UUID, requestCaptor.getValue().key());
    }

    @Test
    void getFileByIdMapsNoSuchKeyToNotFound() {
        NoSuchKeyException missing = NoSuchKeyException.builder()
                .statusCode(404)
                .message("The specified key does not exist")
                .build();
        doThrow(missing).when(s3).getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));

        assertThrows(ExpenseFileNotFoundException.class, () -> service.getFileById(UUID));
    }

    @Test
    void getFileByIdMapsListBucketDeniedForMissingKeyToNotFound() {
        AccessDeniedException maskedMissing = accessDenied("s3:ListBucket");
        doThrow(maskedMissing).when(s3)
                .getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));

        assertThrows(ExpenseFileNotFoundException.class, () -> service.getFileById(UUID));
    }

    @Test
    void getFileByIdRethrowsNoSuchBucket() {
        NoSuchBucketException bucketMissing = NoSuchBucketException.builder()
                .statusCode(404)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("NoSuchBucket")
                        .errorMessage("The specified bucket does not exist")
                        .build())
                .build();
        doThrow(bucketMissing).when(s3)
                .getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));

        S3Exception thrown = assertThrows(S3Exception.class, () -> service.getFileById(UUID));

        assertSame(bucketMissing, thrown);
    }

    @Test
    void getFileByIdRethrowsActualGetObjectDenial() {
        AccessDeniedException getDenied = accessDenied("s3:GetObject");
        doThrow(getDenied).when(s3)
                .getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));

        S3Exception thrown = assertThrows(S3Exception.class, () -> service.getFileById(UUID));

        assertSame(getDenied, thrown);
    }

    private static AccessDeniedException accessDenied(String action) {
        return AccessDeniedException.builder()
                .statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("AccessDenied")
                        .errorMessage("not authorized to perform: " + action)
                        .build())
                .build();
    }
}
