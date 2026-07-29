package dk.trustworks.intranet.documentservice.migration.services;

import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.FolderStatus;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationItem;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationItem.ItemStatus;
import dk.trustworks.intranet.documentservice.migration.util.QuickXorHash;
import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService.MigrationStoreCommand;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService.MigrationStoreResult;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService.PromoteCommand;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentsParameters;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.sharepoint.client.GraphApiClient;
import dk.trustworks.intranet.sharepoint.client.GraphResponseExceptionMapper.SharePointException;
import dk.trustworks.intranet.sharepoint.dto.DriveItem;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M3 — the copier (runbook 2a-4 / spec §9.4). Two idempotent sub-jobs:
 *
 * <p><b>(a) SharePoint items:</b> for each MAPPED folder's DISCOVERED
 * items — fresh metadata by id (webUrl, lastModifiedDateTime, hashes),
 * Graph download (any size), local QuickXorHash recompute compared
 * against Graph's value, then
 * {@link EmployeeDocumentService#storeMigrated}. SharePoint is never
 * mutated. Files over the interactive cap migrate anyway (counted);
 * unknown content types get sniffed + {@code needs_review=1}.</p>
 *
 * <p><b>(b) Legacy re-home:</b> {@code files} rows with
 * {@code type='DOCUMENT'} whose {@code relateduuid} is a real user —
 * server-side CopyObject into the employee bucket
 * ({@code migrated_from='files:{uuid}'}), then delete the old object +
 * row. Candidate-linked rows are untouched (live recruitment staging).</p>
 *
 * <p>{@code dryRun=true} walks the working tables and produces the
 * counts without a single S3 write or Graph download.</p>
 */
@JBossLog
@ApplicationScoped
public class SharePointMigrationCopyService {

    static final long POLITENESS_DELAY_MS = 100;
    static final int MAX_RETRIES = 5;

    @Inject
    SharePointService sharePointService;

    @Inject
    @RestClient
    GraphApiClient graphClient;

    @Inject
    EmployeeDocumentService employeeDocumentService;

    @Inject
    EmployeeDocumentsParameters parameters;

    @Inject
    S3Client s3;

    @ConfigProperty(name = "bucket.files")
    String legacyFilesBucket;

    // The legacy files bucket is SHARED between staging and production (bucket.files
    // has no per-env override), and the re-home DELETES source objects that
    // production's `files` rows still reference. Default OFF so a staging rehearsal
    // Copy can never destroy prod data; production arms it via
    // DK_TRUSTWORKS_EMPLOYEE_DOCUMENTS_MIGRATION_LEGACY_REHOME_ENABLED in
    // deploy-production.yml.
    @ConfigProperty(name = "dk.trustworks.employee-documents.migration.legacy-rehome.enabled",
            defaultValue = "false")
    boolean legacyRehomeEnabled;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record CopySummary(
            boolean dryRun,
            int spCandidates,
            int spCopied,
            int spSkippedProvenance,
            int spFailed,
            int spOversize,
            int spTypeSniffed,
            long spBytes,
            int legacyCandidates,
            int legacyCopied,
            int legacySkippedProvenance,
            int legacyFailed,
            boolean legacyRehomeDisabled,
            List<String> errors) { }

    public CopySummary copy(boolean dryRun) {
        Counters counters = new Counters(dryRun);
        copySharePointItems(dryRun, counters);
        rehomeLegacyFiles(dryRun, counters);
        log.infof("Copy done (dryRun=%s): SP %d/%d copied (%d failed, %d oversize), legacy %d/%d",
                dryRun, counters.spCopied, counters.spCandidates, counters.spFailed,
                counters.spOversize, counters.legacyCopied, counters.legacyCandidates);
        return counters.toSummary();
    }

    // ── (a) SharePoint items ───────────────────────────────────────────────

    private void copySharePointItems(boolean dryRun, Counters counters) {
        List<SharePointMigrationFolder> folders =
                QuarkusTransaction.requiringNew().call(SharePointMigrationFolder::findMapped);

        // One settings read per run, in its own transaction — the runner
        // thread has no request context of its own (prod Match defect 1).
        long cap = QuarkusTransaction.requiringNew().call(parameters::uploadMaxSizeBytes);

        // Resolve each distinct site once per run.
        Map<String, String> driveIdBySiteUrl = new HashMap<>();

        for (SharePointMigrationFolder folder : folders) {
            List<SharePointMigrationItem> pending = QuarkusTransaction.requiringNew().call(() ->
                    SharePointMigrationItem.findByFolderAndStatus(folder.getId(), ItemStatus.DISCOVERED));
            if (pending.isEmpty()) continue;

            counters.spCandidates += pending.size();

            if (dryRun) {
                for (SharePointMigrationItem item : pending) {
                    counters.spBytes += item.getSizeBytes();
                    if (item.getSizeBytes() > cap) counters.spOversize++;
                }
                continue;
            }

            String driveId;
            try {
                driveId = driveIdBySiteUrl.computeIfAbsent(folder.getSiteUrl(), this::resolveDriveId);
            } catch (Exception e) {
                counters.error("Site resolution failed for " + folder.getSiteUrl() + ": " + e.getMessage());
                continue;
            }

            QuarkusTransaction.requiringNew().run(() -> {
                SharePointMigrationFolder managed = SharePointMigrationFolder.findById(folder.getId());
                if (managed != null && managed.getStatus() == FolderStatus.MAPPED) {
                    managed.setStatus(FolderStatus.COPYING);
                    managed.persist();
                }
            });

            for (SharePointMigrationItem item : pending) {
                copyOneItem(driveId, folder, item, cap, counters);
            }
        }
    }

    private void copyOneItem(String driveId, SharePointMigrationFolder folder,
                             SharePointMigrationItem item, long cap, Counters counters) {
        try {
            pause(POLITENESS_DELAY_MS);
            DriveItem fresh = politeGetItem(driveId, item.getDriveItemId());
            byte[] bytes = downloadBytes(driveId, fresh);

            String graphHash = fresh.file() != null && fresh.file().hashes() != null
                    ? fresh.file().hashes().quickXorHash() : item.getQuickxorHash();
            if (graphHash != null && !graphHash.isBlank()) {
                String localHash = QuickXorHash.base64(bytes);
                if (!graphHash.equals(localHash)) {
                    failItem(item.getId(), "quickXorHash mismatch (graph=" + graphHash
                            + ", local=" + localHash + ")");
                    counters.spFailed++;
                    return;
                }
            }

            LocalDateTime originalTimestamp = fresh.lastModifiedDateTime() != null
                    ? fresh.lastModifiedDateTime().toLocalDateTime() : null;
            MigrationStoreResult result = employeeDocumentService.storeMigrated(new MigrationStoreCommand(
                    folder.getMatchedUserUuid(),
                    bytes,
                    item.getName(),
                    fresh.file() != null ? fresh.file().mimeType() : null,
                    null,
                    item.getRelativePath(),
                    fresh.webUrl(),
                    originalTimestamp));

            String freshEtag = fresh.eTag();
            String freshHash = graphHash;
            QuarkusTransaction.requiringNew().run(() -> {
                SharePointMigrationItem managed = SharePointMigrationItem.findById(item.getId());
                if (managed == null) return;
                managed.setStatus(ItemStatus.COPIED);
                managed.setEmployeeDocumentUuid(result.document().getUuid());
                // Refresh metadata to what was actually copied, so the
                // verifier compares against reality even if the file changed
                // between crawl and copy.
                managed.setSizeBytes(bytes.length);
                if (freshEtag != null) managed.setEtag(freshEtag);
                if (freshHash != null) managed.setQuickxorHash(freshHash);
                managed.setError(null);
                managed.persist();
            });

            if (!result.created()) {
                counters.spSkippedProvenance++;
            } else {
                counters.spCopied++;
                counters.spBytes += bytes.length;
                if (bytes.length > cap) counters.spOversize++;
                if (result.typeSniffed()) counters.spTypeSniffed++;
            }
        } catch (Exception e) {
            log.errorf(e, "Copy failed for item %d (%s)", item.getId(), item.getName());
            failItem(item.getId(), e.getMessage());
            counters.spFailed++;
            counters.error("item " + item.getName() + ": " + e.getMessage());
        }
    }

    private void failItem(Long itemId, String error) {
        QuarkusTransaction.requiringNew().run(() -> {
            SharePointMigrationItem managed = SharePointMigrationItem.findById(itemId);
            if (managed == null) return;
            managed.setStatus(ItemStatus.FAILED);
            managed.setError(error != null && error.length() > 1024 ? error.substring(0, 1024) : error);
            managed.persist();
        });
    }

    private String resolveDriveId(String siteUrl) {
        // Drive name comes from the matching sharepoint_locations row so the
        // copier resolves exactly what the crawler crawled.
        String driveName = QuarkusTransaction.requiringNew().call(() -> {
            dk.trustworks.intranet.documentservice.model.SharePointLocationEntity location =
                    dk.trustworks.intranet.documentservice.model.SharePointLocationEntity
                            .find("siteUrl", siteUrl).firstResult();
            return location == null ? null : location.getDriveName();
        });
        String siteId = sharePointService.resolveSiteId(siteUrl);
        return sharePointService.resolveDriveId(siteId, driveName);
    }

    /**
     * Download the file's bytes: prefer the pre-authenticated
     * {@code @microsoft.graph.downloadUrl} from the fresh metadata, fall
     * back to the {@code /content} 302-follow. Any size (spec §9.4a).
     */
    byte[] downloadBytes(String driveId, DriveItem item) throws Exception {
        if (item.downloadUrl() != null && !item.downloadUrl().isBlank()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(item.downloadUrl())).GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 == 2) {
                return response.body();
            }
            throw new IllegalStateException("downloadUrl fetch failed with HTTP " + response.statusCode());
        }
        try (Response response = graphClient.downloadContent(driveId, item.id())) {
            String location = response.getHeaderString("Location");
            if (location != null) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(location)).GET().build();
                HttpResponse<byte[]> redirected = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (redirected.statusCode() / 100 == 2) return redirected.body();
                throw new IllegalStateException("redirected download failed with HTTP " + redirected.statusCode());
            }
            if (response.hasEntity()) {
                Object entity = response.getEntity();
                if (entity instanceof byte[] bytes) return bytes;
                if (entity instanceof InputStream stream) return stream.readAllBytes();
            }
            throw new IllegalStateException("Graph returned neither redirect nor content");
        }
    }

    private DriveItem politeGetItem(String driveId, String itemId) {
        int attempt = 0;
        while (true) {
            try {
                return graphClient.getItem(driveId, itemId);
            } catch (SharePointException e) {
                attempt++;
                if ((e.getStatusCode() == 429 || e.getStatusCode() == 503) && attempt <= MAX_RETRIES) {
                    long backoffMs = 5000L * (1L << (attempt - 1));
                    log.warnf("Graph %d on getItem (attempt %d/%d) — backing off %d ms",
                            e.getStatusCode(), attempt, MAX_RETRIES, backoffMs);
                    pause(backoffMs);
                    continue;
                }
                throw e;
            }
        }
    }

    // ── (b) Legacy files re-home ───────────────────────────────────────────

    private void rehomeLegacyFiles(boolean dryRun, Counters counters) {
        record LegacyRow(String uuid, String userUuid, String filename, java.time.LocalDate uploadDate) { }
        List<LegacyRow> rows = QuarkusTransaction.requiringNew().call(() ->
                File.<File>list("type", "DOCUMENT").stream()
                        .filter(f -> f.getRelateduuid() != null
                                && User.findById(f.getRelateduuid()) != null)
                        .map(f -> new LegacyRow(f.getUuid(), f.getRelateduuid(),
                                f.getFilename() != null && !f.getFilename().isBlank()
                                        ? f.getFilename()
                                        : (f.getName() != null && !f.getName().isBlank() ? f.getName() : f.getUuid()),
                                f.getUploaddate()))
                        .toList());

        counters.legacyCandidates = rows.size();
        if (!legacyRehomeEnabled) {
            counters.legacyRehomeDisabled = true;
            if (!dryRun) {
                log.warnf("Legacy re-home DISABLED in this environment "
                        + "(dk.trustworks.employee-documents.migration.legacy-rehome.enabled=false) — "
                        + "%d candidate files rows left untouched in bucket %s",
                        rows.size(), legacyFilesBucket);
            }
            return;
        }
        if (dryRun) return;

        for (LegacyRow row : rows) {
            try {
                String provenance = "files:" + row.uuid();
                boolean existedBefore = QuarkusTransaction.requiringNew().call(() ->
                        EmployeeDocument.findByProvenance(provenance) != null);

                EmployeeDocument doc = employeeDocumentService.storeFromS3(new PromoteCommand(
                        row.userUuid(), legacyFilesBucket, row.uuid(), row.filename(),
                        null, null, EmployeeDocumentSource.MIGRATION, null, null, provenance));

                if (!existedBefore && row.uploadDate() != null) {
                    // Preserve the legacy upload date in created_at (report-only
                    // nicety; same native-update route as the SharePoint path).
                    LocalDateTime timestamp = row.uploadDate().atStartOfDay();
                    QuarkusTransaction.requiringNew().run(() ->
                            EmployeeDocument.getEntityManager()
                                    .createNativeQuery("UPDATE employee_documents SET created_at = ?1 WHERE uuid = ?2")
                                    .setParameter(1, timestamp)
                                    .setParameter(2, doc.getUuid())
                                    .executeUpdate());
                }

                // Old object first, then the row — a failure between the two is
                // healed by a re-run (provenance skip + idempotent S3 delete).
                s3.deleteObject(DeleteObjectRequest.builder()
                        .bucket(legacyFilesBucket).key(row.uuid()).build());
                QuarkusTransaction.requiringNew().run(() -> File.deleteById(row.uuid()));

                if (existedBefore) counters.legacySkippedProvenance++;
                else counters.legacyCopied++;
            } catch (Exception e) {
                log.errorf(e, "Legacy re-home failed for files row %s", row.uuid());
                counters.legacyFailed++;
                counters.error("legacy " + row.uuid() + ": " + e.getMessage());
            }
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Copy interrupted", e);
        }
    }

    private static final class Counters {
        final boolean dryRun;
        int spCandidates;
        int spCopied;
        int spSkippedProvenance;
        int spFailed;
        int spOversize;
        int spTypeSniffed;
        long spBytes;
        int legacyCandidates;
        int legacyCopied;
        int legacySkippedProvenance;
        int legacyFailed;
        boolean legacyRehomeDisabled;
        final List<String> errors = new ArrayList<>();

        Counters(boolean dryRun) {
            this.dryRun = dryRun;
        }

        void error(String message) {
            if (errors.size() < 200) errors.add(message);
        }

        CopySummary toSummary() {
            return new CopySummary(dryRun, spCandidates, spCopied, spSkippedProvenance, spFailed,
                    spOversize, spTypeSniffed, spBytes, legacyCandidates, legacyCopied,
                    legacySkippedProvenance, legacyFailed, legacyRehomeDisabled, errors);
        }
    }
}
