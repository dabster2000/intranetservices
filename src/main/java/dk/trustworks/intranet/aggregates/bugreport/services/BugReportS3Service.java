package dk.trustworks.intranet.aggregates.bugreport.services;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.ByteArrayOutputStream;
import java.time.Duration;

/**
 * S3 storage for bug report screenshots.
 * Follows the pattern of InvoicePdfS3Service.
 */
@JBossLog
@ApplicationScoped
public class BugReportS3Service {

    @ConfigProperty(name = "bucket.files")
    String bucketName;

    private final S3Client s3;
    private final S3Presigner s3Presigner;

    public BugReportS3Service() {
        ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder()
                .proxyConfiguration(proxyConfig.build());
        this.s3 = S3Client.builder()
                .region(Region.EU_WEST_1)
                .httpClientBuilder(httpClientBuilder)
                .build();
        this.s3Presigner = S3Presigner.builder()
                .region(Region.EU_WEST_1)
                .build();
    }

    private String buildKey(String reportUuid) {
        return "bug-reports/" + reportUuid + ".png";
    }

    /**
     * Stores a screenshot image in S3.
     *
     * @return the S3 key where the screenshot was stored
     */
    public String saveScreenshot(String reportUuid, byte[] imageBytes) {
        String key = buildKey(reportUuid);
        log.infof("Uploading bug report screenshot to S3: %s (%d bytes)", key, imageBytes.length);
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .contentType("image/png")
                        .build(),
                RequestBody.fromBytes(imageBytes));
        log.infof("Bug report screenshot uploaded to S3: %s", key);
        return key;
    }

    /**
     * Retrieves a screenshot image from S3.
     */
    public byte[] getScreenshot(String reportUuid) {
        String key = buildKey(reportUuid);
        log.debugf("Downloading bug report screenshot from S3: %s", key);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            s3.getObject(
                    GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .build(),
                    ResponseTransformer.toOutputStream(baos));
            byte[] bytes = baos.toByteArray();
            log.debugf("Bug report screenshot downloaded from S3: %s (%d bytes)", key, bytes.length);
            return bytes;
        } catch (S3Exception e) {
            log.errorf("Failed to download bug report screenshot from S3: %s - %s", key, e.awsErrorDetails().errorMessage());
            throw e;
        }
    }

    /**
     * Generates a pre-signed URL for downloading a screenshot from S3.
     * Used by the auto-fix worker to fetch the screenshot without direct S3 credentials.
     *
     * @param reportUuid UUID of the bug report
     * @param validity   duration the URL remains valid
     * @return pre-signed URL string
     */
    public String generatePresignedUrl(String reportUuid, Duration validity) {
        String key = buildKey(reportUuid);
        log.debugf("Generating pre-signed URL for screenshot: %s (validity: %s)", key, validity);
        var presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(validity)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .build())
                .build();
        var presignedUrl = s3Presigner.presignGetObject(presignRequest);
        return presignedUrl.url().toString();
    }

    /**
     * Deletes a screenshot from S3 (used for hard-delete of DRAFT reports).
     */
    public void deleteScreenshot(String reportUuid) {
        String key = buildKey(reportUuid);
        log.infof("Deleting bug report screenshot from S3: %s", key);
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
            log.infof("Bug report screenshot deleted from S3: %s", key);
        } catch (S3Exception e) {
            log.warnf("Failed to delete bug report screenshot from S3: %s - %s", key, e.awsErrorDetails().errorMessage());
        }
    }
}
