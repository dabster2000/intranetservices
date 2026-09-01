package dk.trustworks.intranet.documentservice.maintenance;

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
 * only, so every server-side S3→S3 copy (the completed migration into
 * the store, the conversion promotion) left it null — roughly a quarter
 * of the corpus at the time.
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

    /**
     * @param skippedEmpty zero-byte objects deliberately left un-hashed —
     *                     they stay candidates on every future run, and
     *                     this is what explains {@code hashed + failed}
     *                     falling short of {@code candidates}
     */
    public record BackfillSummary(
            boolean dryRun,
            int candidates,
            int hashed,
            int failed,
            int skippedEmpty,
            long bytesRead,
            List<String> errors) { }

    /** Cap the error list so one systemic failure cannot balloon the response. */
    private static final int MAX_REPORTED_ERRORS = 25;

    /**
     * SHA-256 of zero bytes — what an empty object hashes to.
     *
     * <p>Never written. The digest is real but it identifies emptiness, not
     * a document, so storing it turns "this file has no content" into
     * "these files are provably the same file": every empty row in one
     * employee's file then collapses into a single hash-proven duplicate
     * group, and hash-proven is exactly the condition under which the HR
     * console offers an irreversible delete. The migration left 34 such
     * objects, among them 8 contracts. Leaving {@code sha256} null keeps
     * them un-grouped and keeps deletion disarmed.</p>
     */
    static final String EMPTY_FILE_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

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
            int empty = (int) candidates.stream().filter(c -> c.size() == 0).count();
            log.infof("Hash backfill dry run: %d documents, %d empty (will be skipped), %d bytes to read",
                    candidates.size(), empty, bytes);
            return new BackfillSummary(true, candidates.size(), 0, 0, empty, bytes, List.of());
        }

        int hashed = 0;
        int failed = 0;
        int skippedEmpty = 0;
        long bytesRead = 0;
        List<String> errors = new ArrayList<>();

        for (Candidate candidate : candidates) {
            try {
                byte[] bytes = storage.get(candidate.s3Key()).bytes();
                bytesRead += bytes.length;

                // An empty object hashes to a constant. Writing it would
                // make every empty row in an employee's file look provably
                // identical to every other one, which is what unlocks the
                // console's irreversible delete. Left null on purpose.
                if (bytes.length == 0) {
                    skippedEmpty++;
                    log.warnf("Hash backfill: %s is 0 bytes in S3 (key=%s) — leaving sha256 null",
                            candidate.uuid(), candidate.s3Key());
                    continue;
                }
                String sha256 = EmployeeDocumentService.sha256Hex(bytes);

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

        log.infof("Hash backfill done: %d candidates, %d hashed, %d failed, %d empty skipped, %d bytes read",
                candidates.size(), hashed, failed, skippedEmpty, bytesRead);
        return new BackfillSummary(false, candidates.size(), hashed, failed, skippedEmpty, bytesRead, errors);
    }
}
