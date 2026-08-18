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

    /**
     * How many images one evidence row may own. One Slack message now produces
     * ONE evidence row carrying every attached image (the model reads them
     * together), so the deleter has to sweep this many keys.
     * <p>
     * DERIVED, never a second literal. Security review 2026-08-18 (finding 3):
     * as two independent constants, lowering this one while the ingest cap
     * stayed at 3 would have left the higher-index S3 objects orphaned forever —
     * a GDPR deletion-sweep miss with no error, no log and no alert. The
     * invariant is pinned by {@code SchedulingEvidenceStorageKeyTest}.
     */
    public static final int MAX_IMAGES_PER_EVIDENCE =
            AvailabilityMessageService.IMAGES_PER_MESSAGE_MAX;

    /**
     * The object key of one evidence image — never logged with content. Index 0
     * keeps the historical unsuffixed key so objects written before
     * multi-image support stay addressable by the same deleter.
     */
    public String keyOf(String evidenceUuid) {
        return keyOf(evidenceUuid, 0);
    }

    /** The object key of the {@code index}-th image of one evidence row. */
    public String keyOf(String evidenceUuid, int index) {
        String base = "recruitment/scheduling-evidence/" + environmentId + "/" + evidenceUuid;
        return index <= 0 ? base : base + "-" + index;
    }

    /** Store one validated image (bytes already magic-byte-gated). */
    public void store(String evidenceUuid, byte[] bytes, String contentType) {
        store(evidenceUuid, 0, bytes, contentType);
    }

    /** Store the {@code index}-th image of one evidence row. */
    public void store(String evidenceUuid, int index, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(keyOf(evidenceUuid, index))
                        .contentType(contentType)
                        .serverSideEncryption(ServerSideEncryption.AES256)
                        .build(),
                RequestBody.fromBytes(bytes));
        log.infof("Stored scheduling evidence image evidence=%s index=%d size=%d",
                evidenceUuid, index, bytes.length);
    }

    /**
     * Delete EVERY image of one evidence row (D10). Idempotent at the S3 layer,
     * so sweeping all {@value #MAX_IMAGES_PER_EVIDENCE} slots is safe whether
     * the row owned one image or three — a delete of an absent key succeeds.
     * That idempotence is what lets the deleter stay keyed on the evidence uuid
     * alone; it never has to know how many images arrived.
     */
    public void delete(String evidenceUuid) {
        for (int index = 0; index < MAX_IMAGES_PER_EVIDENCE; index++) {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(keyOf(evidenceUuid, index))
                    .build());
        }
        log.infof("Deleted scheduling evidence images evidence=%s slots=%d",
                evidenceUuid, MAX_IMAGES_PER_EVIDENCE);
    }
}
