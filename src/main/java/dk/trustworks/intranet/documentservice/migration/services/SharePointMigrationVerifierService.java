package dk.trustworks.intranet.documentservice.migration.services;

import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.FolderStatus;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationItem;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationItem.ItemStatus;
import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentStorageAdapter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * M5 — trust, then verify (runbook 2a-6 / spec §9.6). For every COPIED
 * item: S3 {@code Content-Length} must equal the recorded size; sha256
 * is recomputed for a deterministic 10% sample plus every file over
 * 10 MB. Passing items → VERIFIED; a COPYING folder whose non-skipped
 * items are all VERIFIED → VERIFIED. (The quickXorHash comparison
 * already happened at copy time — a mismatch never produced a document.)
 */
@JBossLog
@ApplicationScoped
public class SharePointMigrationVerifierService {

    static final long SHA_CHECK_SIZE_THRESHOLD = 10L * 1024 * 1024;

    @Inject
    EmployeeDocumentStorageAdapter storage;

    public record VerifySummary(
            int itemsChecked,
            int verified,
            int failed,
            int sha256Recomputed,
            int foldersVerified,
            List<String> errors) { }

    public VerifySummary verify() {
        List<Long> itemIds = QuarkusTransaction.requiringNew().call(() ->
                SharePointMigrationItem.findByStatus(ItemStatus.COPIED).stream()
                        .map(SharePointMigrationItem::getId).toList());

        int verified = 0;
        int failed = 0;
        int shaRecomputed = 0;
        List<String> errors = new ArrayList<>();

        for (Long itemId : itemIds) {
            record Facts(String docUuid, String name, long sizeBytes) { }
            Facts facts = QuarkusTransaction.requiringNew().call(() -> {
                SharePointMigrationItem item = SharePointMigrationItem.findById(itemId);
                return item == null ? null
                        : new Facts(item.getEmployeeDocumentUuid(), item.getName(), item.getSizeBytes());
            });
            if (facts == null) continue;

            try {
                record DocFacts(String s3Key, String sha256, long fileSize) { }
                DocFacts doc = QuarkusTransaction.requiringNew().call(() -> {
                    EmployeeDocument d = EmployeeDocument.findById(facts.docUuid());
                    return d == null ? null : new DocFacts(d.getS3Key(), d.getSha256(), d.getFileSizeBytes());
                });
                if (doc == null) {
                    fail(itemId, "employee_document row missing (" + facts.docUuid() + ")");
                    failed++;
                    continue;
                }

                long contentLength = storage.head(doc.s3Key()).contentLength();
                if (contentLength != facts.sizeBytes() || contentLength != doc.fileSize()) {
                    fail(itemId, "size mismatch: s3=" + contentLength
                            + " item=" + facts.sizeBytes() + " doc=" + doc.fileSize());
                    failed++;
                    continue;
                }

                boolean inSample = Math.abs(facts.docUuid().hashCode()) % 10 == 0
                        || facts.sizeBytes() > SHA_CHECK_SIZE_THRESHOLD;
                if (inSample && doc.sha256() != null) {
                    shaRecomputed++;
                    byte[] bytes = storage.get(doc.s3Key()).bytes();
                    String actual = HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(bytes));
                    if (!actual.equals(doc.sha256())) {
                        fail(itemId, "sha256 mismatch");
                        failed++;
                        continue;
                    }
                }

                QuarkusTransaction.requiringNew().run(() -> {
                    SharePointMigrationItem managed = SharePointMigrationItem.findById(itemId);
                    if (managed == null) return;
                    managed.setStatus(ItemStatus.VERIFIED);
                    managed.setError(null);
                    managed.persist();
                });
                verified++;
            } catch (Exception e) {
                log.errorf(e, "Verification errored for item %d (%s)", itemId, facts.name());
                fail(itemId, e.getMessage());
                failed++;
                if (errors.size() < 200) errors.add(facts.name() + ": " + e.getMessage());
            }
        }

        int foldersVerified = promoteVerifiedFolders();
        log.infof("Verify done: %d checked, %d verified, %d failed, %d sha256 recomputed, %d folders green",
                itemIds.size(), verified, failed, shaRecomputed, foldersVerified);
        return new VerifySummary(itemIds.size(), verified, failed, shaRecomputed, foldersVerified, errors);
    }

    private void fail(Long itemId, String error) {
        QuarkusTransaction.requiringNew().run(() -> {
            SharePointMigrationItem managed = SharePointMigrationItem.findById(itemId);
            if (managed == null) return;
            managed.setStatus(ItemStatus.FAILED);
            managed.setError(error != null && error.length() > 1024 ? error.substring(0, 1024) : error);
            managed.persist();
        });
    }

    private int promoteVerifiedFolders() {
        return QuarkusTransaction.requiringNew().call(() -> {
            int promoted = 0;
            for (SharePointMigrationFolder folder : SharePointMigrationFolder
                    .<SharePointMigrationFolder>list("status", FolderStatus.COPYING)) {
                List<SharePointMigrationItem> items = SharePointMigrationItem.findByFolder(folder.getId());
                boolean allDone = items.stream().allMatch(i ->
                        i.getStatus() == ItemStatus.VERIFIED || i.getStatus() == ItemStatus.SKIPPED);
                if (!items.isEmpty() && allDone) {
                    folder.setStatus(FolderStatus.VERIFIED);
                    folder.persist();
                    promoted++;
                }
            }
            return promoted;
        });
    }
}
