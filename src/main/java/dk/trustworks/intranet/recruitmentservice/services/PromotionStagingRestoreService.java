package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentStorageAdapter;
import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.services.S3FileService;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;

/**
 * One-off repair for candidates promoted before 2026-08-11, when
 * {@code S3EmployeePromotionService} deleted each staging original as it
 * copied it. Those candidates' dossiers are left holding dangling file
 * references: the revision snapshots, the appendix rows and the candidate
 * Documents tab all point at {@code files} rows that no longer exist.
 *
 * <p>The bytes were never lost. {@code storeFromS3} performs a server-side
 * {@code CopyObject} into the employee bucket <em>before</em> the delete ran,
 * so every destroyed original still exists under the promoted document's
 * {@code s3_key}. This walks a candidate's {@code source=PROMOTION} rows and
 * writes the staging {@code files} row + object back under its original
 * {@code fileUuid} (taken from {@code migrated_from}), which is exactly the
 * key the snapshots point at.</p>
 *
 * <p>Idempotent: a fileUuid that still has a {@code files} row is skipped, so
 * a partial run can simply be repeated.</p>
 *
 * <h3>Delete this class once the affected candidates are repaired.</h3>
 * It exists to undo a specific incident, not as a supported operation. Note
 * that {@code bucket.files} has no per-environment override — staging and
 * production share {@code trustworksfiles} — so running this outside
 * production writes into the production bucket.
 */
@JBossLog
@ApplicationScoped
public class PromotionStagingRestoreService {

    private static final String PROVENANCE_PREFIX = "files:";

    @Inject
    EmployeeDocumentStorageAdapter storage;

    @Inject
    S3FileService s3FileService;

    /**
     * @param examined promoted documents inspected
     * @param restored staging files written back
     * @param skipped  sources that already had a {@code files} row
     * @param failed   sources whose bytes could not be read back
     */
    public record RestoreSummary(int examined, int restored, int skipped, int failed) { }

    /**
     * Rehydrate the staging originals of an already-promoted candidate.
     *
     * @throws IllegalArgumentException if the candidate does not exist or was
     *                                  never promoted to a user
     */
    public RestoreSummary restoreStaging(String candidateUuid) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid);
        if (candidate == null) {
            throw new IllegalArgumentException("Unknown candidate: " + candidateUuid);
        }
        String userUuid = candidate.getConvertedUserUuid();
        if (userUuid == null || userUuid.isBlank()) {
            throw new IllegalArgumentException("Candidate has no converted user: " + candidateUuid);
        }

        // IDENTITY is excluded by category, not by hr_only. The candidate
        // `files` channel this writes into is the dossier Documents tab, which
        // the whole hiring circle can read — a wider audience than the
        // employee record these documents sit in today. Restoring a passport,
        // sundhedskort or straffeattest there would re-expose it while
        // repairing an unrelated problem; the onboarding submission keeps a
        // dangling reference instead, which is the cheaper harm by a wide
        // margin.
        //
        // Filtering on hr_only would be wrong here: the drafts this exists to
        // put back were themselves filed hr_only by the same defective rule,
        // so it would skip most of what needs restoring.
        List<EmployeeDocument> promoted = EmployeeDocument
                .<EmployeeDocument>find("userUuid = ?1 AND source = ?2 AND category <> ?3 "
                                + "AND migratedFrom LIKE 'files:%'",
                        userUuid, EmployeeDocumentSource.PROMOTION, EmployeeDocumentCategory.IDENTITY)
                .list();

        int restored = 0;
        int skipped = 0;
        int failed = 0;
        for (EmployeeDocument doc : promoted) {
            String fileUuid = doc.getMigratedFrom().substring(PROVENANCE_PREFIX.length());
            if (fileUuid.isBlank() || File.findById(fileUuid) != null) {
                skipped++;
                continue;
            }
            try {
                EmployeeDocumentStorageAdapter.StoredObject object = storage.get(doc.getS3Key());
                File file = new File(
                        fileUuid,
                        candidateUuid,
                        "DOCUMENT",
                        doc.getOriginalFilename(),
                        doc.getOriginalFilename(),
                        LocalDate.now(),
                        object.bytes());
                s3FileService.save(file);
                restored++;
            } catch (RuntimeException e) {
                log.errorf(e, "Staging restore failed candidate=%s fileUuid=%s employeeDocument=%s",
                        candidateUuid, fileUuid, doc.getUuid());
                failed++;
            }
        }

        RestoreSummary summary = new RestoreSummary(promoted.size(), restored, skipped, failed);
        log.infof("Staging restore candidate=%s examined=%d restored=%d skipped=%d failed=%d",
                candidateUuid, summary.examined(), summary.restored(), summary.skipped(), summary.failed());
        return summary;
    }
}
