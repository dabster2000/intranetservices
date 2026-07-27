package dk.trustworks.intranet.documentservice.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The ONLY class that touches the {@code trustworks-employee-documents-{env}}
 * bucket (spec §6.3 — "one writer" tenet). Uses the Quarkus-managed
 * {@link S3Client} (CDI-produced by the quarkus-amazon-s3 extension), which
 * makes {@code quarkus.s3.endpoint-override} / {@code path-style-access}
 * actually work — local development runs against MinIO, production resolves
 * task-role credentials via the default chain.
 *
 * <p>Unlike the legacy {@code S3FileService}, every put sets a real
 * {@code Content-Type} plus identifying object metadata, and deletes remove
 * <b>all versions</b> (the bucket is versioned; GDPR erasure must not leave
 * noncurrent versions behind — spec §6.10).</p>
 */
@JBossLog
@ApplicationScoped
public class EmployeeDocumentStorageAdapter {

    @ConfigProperty(name = "bucket.employee-documents")
    String bucketName;

    @Inject
    S3Client s3;

    /** Bytes + content type of a stored object. */
    public record StoredObject(byte[] bytes, String contentType) { }

    /**
     * Store bytes under the given key with a real Content-Type and
     * identifying metadata. Overwrites are versioned, never destructive.
     */
    public void put(String key, byte[] bytes, String contentType, Map<String, String> metadata) {
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(contentType);
        if (metadata != null && !metadata.isEmpty()) {
            request.metadata(metadata);
        }
        s3.putObject(request.build(), RequestBody.fromBytes(bytes));
    }

    /** Fetch an object's bytes + stored content type. Throws S3Exception when missing. */
    public StoredObject get(String key) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GetObjectResponse response = s3.getObject(
                GetObjectRequest.builder().bucket(bucketName).key(key).build(),
                ResponseTransformer.toOutputStream(baos));
        return new StoredObject(baos.toByteArray(), response.contentType());
    }

    /** Head an object (size/content-type) without fetching bytes. */
    public HeadObjectResponse head(String key) {
        return s3.headObject(HeadObjectRequest.builder().bucket(bucketName).key(key).build());
    }

    /**
     * Server-side copy from another bucket into the employee-documents
     * bucket (promotion / legacy re-home — no byte shuffling through the
     * JVM, spec §6.3). Returns the copied object's size in bytes.
     */
    public long copyFromBucket(String srcBucket, String srcKey, String destKey,
                               String contentType, Map<String, String> metadata) {
        CopyObjectRequest.Builder request = CopyObjectRequest.builder()
                .sourceBucket(srcBucket)
                .sourceKey(srcKey)
                .destinationBucket(bucketName)
                .destinationKey(destKey)
                .contentType(contentType)
                .metadataDirective(MetadataDirective.REPLACE);
        if (metadata != null && !metadata.isEmpty()) {
            request.metadata(metadata);
        }
        s3.copyObject(request.build());
        return head(destKey).contentLength();
    }

    /**
     * Delete EVERY version of a single key (versioned bucket — a plain
     * DeleteObject would only add a delete marker). Idempotent.
     */
    public void deleteAllVersions(String key) {
        deleteAllVersionsUnderPrefixInternal(key, true);
    }

    /**
     * Delete every version of every object under a prefix — the GDPR
     * erasure sweep for {@code users/{uuid}/} (spec §6.10). Belt and
     * braces on top of the per-row deletes: catches objects whose DB row
     * was already removed. Returns the number of versions deleted.
     */
    public int deleteAllVersionsUnderPrefix(String prefix) {
        return deleteAllVersionsUnderPrefixInternal(prefix, false);
    }

    /** List current object keys under a prefix. */
    public List<String> listKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix);
            if (continuationToken != null) request.continuationToken(continuationToken);
            ListObjectsV2Response response = s3.listObjectsV2(request.build());
            response.contents().forEach(o -> keys.add(o.key()));
            continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
        } while (continuationToken != null);
        return keys;
    }

    public String bucketName() {
        return bucketName;
    }

    // ── internals ──────────────────────────────────────────────────────────

    private int deleteAllVersionsUnderPrefixInternal(String prefixOrKey, boolean exactKey) {
        int deleted = 0;
        String keyMarker = null;
        String versionIdMarker = null;
        do {
            ListObjectVersionsRequest.Builder request = ListObjectVersionsRequest.builder()
                    .bucket(bucketName)
                    .prefix(prefixOrKey);
            if (keyMarker != null) request.keyMarker(keyMarker);
            if (versionIdMarker != null) request.versionIdMarker(versionIdMarker);
            ListObjectVersionsResponse response = s3.listObjectVersions(request.build());

            List<ObjectIdentifier> identifiers = new ArrayList<>();
            response.versions().stream()
                    .filter(v -> !exactKey || v.key().equals(prefixOrKey))
                    .forEach(v -> identifiers.add(
                            ObjectIdentifier.builder().key(v.key()).versionId(v.versionId()).build()));
            response.deleteMarkers().stream()
                    .filter(m -> !exactKey || m.key().equals(prefixOrKey))
                    .forEach(m -> identifiers.add(
                            ObjectIdentifier.builder().key(m.key()).versionId(m.versionId()).build()));

            if (!identifiers.isEmpty()) {
                s3.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucketName)
                        .delete(Delete.builder().objects(identifiers).quiet(true).build())
                        .build());
                deleted += identifiers.size();
            }

            keyMarker = response.isTruncated() ? response.nextKeyMarker() : null;
            versionIdMarker = response.isTruncated() ? response.nextVersionIdMarker() : null;
        } while (keyMarker != null || versionIdMarker != null);
        return deleted;
    }
}
