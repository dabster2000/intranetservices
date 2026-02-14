package dk.trustworks.intranet.aggregates.invoice.services;

import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayOutputStream;

@JBossLog
@ApplicationScoped
public class InvoicePdfS3Service {

    @ConfigProperty(name = "bucket.invoices")
    String bucketName;

    private final S3Client s3;

    public InvoicePdfS3Service() {
        Region region = Region.EU_WEST_1;
        ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder()
                .proxyConfiguration(proxyConfig.build());

        this.s3 = S3Client.builder()
                .region(region)
                .httpClientBuilder(httpClientBuilder)
                .build();
    }

    private String buildKey(String invoiceUuid) {
        return "invoices/" + invoiceUuid + ".pdf";
    }

    public String savePdf(String invoiceUuid, byte[] pdfBytes) {
        String key = buildKey(invoiceUuid);
        log.infof("Uploading invoice PDF to S3: %s (%d bytes)", key, pdfBytes.length);
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .contentType("application/pdf")
                        .build(),
                RequestBody.fromBytes(pdfBytes));
        log.infof("Invoice PDF uploaded to S3: %s", key);
        return key;
    }

    public byte[] getPdf(String invoiceUuid) {
        String key = buildKey(invoiceUuid);
        log.debugf("Downloading invoice PDF from S3: %s", key);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            s3.getObject(
                    GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .build(),
                    ResponseTransformer.toOutputStream(baos));
            byte[] bytes = baos.toByteArray();
            log.debugf("Invoice PDF downloaded from S3: %s (%d bytes)", key, bytes.length);
            return bytes;
        } catch (S3Exception e) {
            log.errorf("Failed to download invoice PDF from S3: %s - %s", key, e.awsErrorDetails().errorMessage());
            throw e;
        }
    }

    public byte[] getPdfByKey(String storageKey) {
        log.debugf("Downloading invoice PDF from S3 by key: %s", storageKey);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            s3.getObject(
                    GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(storageKey)
                            .build(),
                    ResponseTransformer.toOutputStream(baos));
            return baos.toByteArray();
        } catch (S3Exception e) {
            log.errorf("Failed to download invoice PDF from S3 by key: %s - %s", storageKey, e.awsErrorDetails().errorMessage());
            throw e;
        }
    }
}
