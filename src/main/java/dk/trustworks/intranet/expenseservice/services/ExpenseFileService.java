package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.dto.ExpenseFile;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

@JBossLog
@ApplicationScoped  // Changed from @RequestScoped to fix race condition with shared S3Client
public class ExpenseFileService {

    private static final String LIST_BUCKET_ACTION = "s3:ListBucket";
    private static final String ACCESS_DENIED_ERROR_CODE = "AccessDenied";
    private static final String NO_SUCH_KEY_ERROR_CODE = "NoSuchKey";

    @ConfigProperty(name = "bucket.expenses")
    String bucketName;

    // S3Client is thread-safe for operations, initialized once in constructor
    private final S3Client s3;

    public ExpenseFileService() {
        this(createS3Client());
    }

    ExpenseFileService(S3Client s3) {
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

    public PutObjectResponse saveFile(ExpenseFile expenseFile) {
        log.info("Uploading expense file to S3: " + expenseFile.getUuid());
        PutObjectResponse response = s3.putObject(
                PutObjectRequest.builder().bucket(bucketName).key(expenseFile.getUuid()).build(),
                RequestBody.fromString(expenseFile.getExpensefile()));
        log.info("S3 upload response: " + response);
        return response;
    }

    public ExpenseFile getFileById(String uuid) throws S3Exception {
        ExpenseFile file = new ExpenseFile(uuid,"");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try{
            log.info("Downloading expense file from S3: " + uuid);
            s3.getObject(GetObjectRequest.builder().bucket(bucketName).key(uuid).build(), ResponseTransformer.toOutputStream(baos));

            String str = baos.toString(StandardCharsets.UTF_8);
            file.setExpensefile(str);

            log.info("Loaded expense file from S3: " + uuid);

        } catch (S3Exception e) {
            if (isMissingObject(e)) {
                log.warnf("Expense file not found in S3: %s", uuid);
                throw new ExpenseFileNotFoundException(uuid, e);
            }
            log.error("Could not load file from S3", e);
            throw e;
        }
        return file;
    }

    private static boolean isMissingObject(S3Exception exception) {
        if (exception instanceof NoSuchKeyException) {
            return true;
        }

        String errorCode = exception.awsErrorDetails() != null
                ? exception.awsErrorDetails().errorCode()
                : null;
        if (exception.statusCode() == 404) {
            return NO_SUCH_KEY_ERROR_CODE.equals(errorCode);
        }
        if (exception.statusCode() != 403 || !ACCESS_DENIED_ERROR_CODE.equals(errorCode)) {
            return false;
        }

        String message = exception.getMessage();
        if (message != null && message.contains(LIST_BUCKET_ACTION)) {
            return true;
        }

        return exception.awsErrorDetails() != null
                && exception.awsErrorDetails().errorMessage() != null
                && exception.awsErrorDetails().errorMessage().contains(LIST_BUCKET_ACTION);
    }
}

