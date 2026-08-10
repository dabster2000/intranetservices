package dk.trustworks.intranet.documentservice.migration.services;

import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentStorageAdapter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Backfills {@code employee_documents.sha256} for rows that never got one.
 *
 * <p><b>Why this exists.</b> The hash is computed on byte-level writes
 * only, so every server-side S3→S3 copy (the SharePoint migration, the
 * conversion promotion) left it null — roughly a quarter of the corpus.
 * That is invisible until something needs to prove two rows hold the same
 * document: duplicate detection then falls back to filename + exact byte
 * size, which is strong evidence but not proof, and proof is exactly what
 * an irreversible delete requires. This job converts "probably the same
 * file" into "provably the same file", and just as usefully proves some
 * look-alike pairs apart.</p>
 *
 * <p>The M5 verifier is not this: it re-hashes a <em>sample</em> to check
 * hashes that already exist, and never writes one.</p>
 *
 * <p>Read-only against S3 and idempotent — a row that already has a hash
 * is never re-read, so a re-run after an interruption resumes for free.
 * The read deliberately goes through the storage adapter rather than
 * {@code EmployeeDocumentService.download}, which would write a DOWNLOAD
 * audit row per document and flood the art. 30 trail with machine reads.</p>
 */
@JBossLog
@ApplicationScoped
public class EmployeeDocumentHashBackfillService {

    @Inject
    EmployeeDocumentStorageAdapter storage;

    public record BackfillSummary(
            boolean dryRun,
            int candidates,
            int hashed,
            int failed,
            long bytesRead,
            List<String> errors) { }

    /** Cap the error list so one systemic failure cannot balloon the response. */
    private static final int MAX_REPORTED_ERRORS = 25;

    /**
     * @param dryRun count and size only — no S3 reads, no writes
     */
    public BackfillSummary backfill(boolean dryRun) {
        record Candidate(String uuid, String s3Key, long size) { }

        List<Candidate> candidates = QuarkusTransaction.requiringNew().call(() ->
                EmployeeDocument.<EmployeeDocument>list("sha256 is null").stream()
                        .map(d -> new Candidate(d.getUuid(), d.getS3Key(), d.getFileSizeBytes()))
                        .toList());

        if (dryRun) {
            long bytes = candidates.stream().mapToLong(Candidate::size).sum();
            log.infof("Hash backfill dry run: %d documents, %d bytes to read", candidates.size(), bytes);
            return new BackfillSummary(true, candidates.size(), 0, 0, bytes, List.of());
        }

        int hashed = 0;
        int failed = 0;
        long bytesRead = 0;
        List<String> errors = new ArrayList<>();

        for (Candidate candidate : candidates) {
            try {
                byte[] bytes = storage.get(candidate.s3Key()).bytes();
                String sha256 = EmployeeDocumentService.sha256Hex(bytes);
                bytesRead += bytes.length;

                QuarkusTransaction.requiringNew().run(() -> {
                    EmployeeDocument doc = EmployeeDocument.findById(candidate.uuid());
                    // Only ever fills a gap: a hash written since we listed
                    // (a re-upload, a concurrent run) is left alone.
                    if (doc == null || doc.getSha256() != null) return;
                    doc.setSha256(sha256);
                    doc.persist();
                });
                hashed++;
            } catch (Exception e) {
                failed++;
                log.warnf("Hash backfill failed for %s: %s", candidate.uuid(), e.getMessage());
                if (errors.size() < MAX_REPORTED_ERRORS) {
                    errors.add(candidate.uuid() + ": " + e.getMessage());
                }
            }
        }

        log.infof("Hash backfill done: %d candidates, %d hashed, %d failed, %d bytes read",
                candidates.size(), hashed, failed, bytesRead);
        return new BackfillSummary(false, candidates.size(), hashed, failed, bytesRead, errors);
    }
}
