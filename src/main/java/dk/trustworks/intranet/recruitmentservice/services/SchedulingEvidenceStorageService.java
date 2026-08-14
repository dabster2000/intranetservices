package dk.trustworks.intranet.recruitmentservice.services;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * The Phase 13 evidence-image store (plan §13.1): calendar images live
 * under {@code recruitment/scheduling-evidence/{env}/{evidenceUuid}} in
 * the shared files bucket — NOT through {@code S3FileService}, whose
 * flat uuid keys and {@code files}-table rows would defeat the two
 * properties this prefix is load-bearing for:
 * <ul>
 *   <li>the bucket is SHARED staging↔prod, so the environment id
 *       ({@code dk.trustworks.environment.id}, the V-series staging
 *       kill-switch idiom) keys each environment's objects apart;</li>
 *   <li>the 7-day lifecycle rule (the D10 deletion backstop — a
 *       runbook-provisioned bucket rule, see the Phase 14 checklist)
 *       targets exactly this prefix and nothing else in the bucket.</li>
 * </ul>
 * SSE-S3 on every put; deletes are S3-idempotent. Objects are transient
 * by design (D10): confirmed/cancelled/timed-out evidence loses its
 * image, only normalized constraints + the sha256 survive.
 */
@JBossLog
@ApplicationScoped
public class SchedulingEvidenceStorageService {

    @ConfigProperty(name = "bucket.files")
    String bucketName;

    @ConfigProperty(name = "dk.trustworks.environment.id", defaultValue = "production")
    String environmentId;

    private final S3Client s3;

    public SchedulingEvidenceStorageService() {
        // The S3FileService client shape — one thread-safe singleton.
        this.s3 = S3Client.builder()
                .region(Region.EU_WEST_1)
                .httpClientBuilder(ApacheHttpClient.builder()
                        .proxyConfiguration(ProxyConfiguration.builder().build()))
                .build();
    }

    /** The object key of one evidence image — never logged with content. */
    public String keyOf(String evidenceUuid) {
        return "recruitment/scheduling-evidence/" + environmentId + "/" + evidenceUuid;
    }

    /** Store one validated image (bytes already magic-byte-gated). */
    public void store(String evidenceUuid, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(keyOf(evidenceUuid))
                        .contentType(contentType)
                        .serverSideEncryption(ServerSideEncryption.AES256)
                        .build(),
                RequestBody.fromBytes(bytes));
        log.infof("Stored scheduling evidence image evidence=%s size=%d", evidenceUuid, bytes.length);
    }

    /** Delete one evidence image (D10). Idempotent at the S3 layer. */
    public void delete(String evidenceUuid) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(keyOf(evidenceUuid))
                .build());
        log.infof("Deleted scheduling evidence image evidence=%s", evidenceUuid);
    }
}
