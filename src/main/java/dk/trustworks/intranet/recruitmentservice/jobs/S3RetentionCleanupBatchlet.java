package dk.trustworks.intranet.recruitmentservice.jobs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierAppendix;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.services.GeneratedPdfRef;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentS3StorageService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Daily reaper for S3-stored recruitment PDFs and appendices whose
 * {@code s3_retention_until} has elapsed. Idempotent: a partial failure
 * leaves the timestamp populated so the next run retries.
 * <p>
 * Runs at 02:10 every day (offset from the 02:00 cluster of cleanup jobs).
 */
@JBossLog
@ApplicationScoped
public class S3RetentionCleanupBatchlet {

    @Inject
    RecruitmentS3StorageService s3StorageService;

    @Inject
    ObjectMapper objectMapper;

    @Scheduled(cron = "0 10 2 * * ?")
    public void scheduledRun() {
        try {
            int reaped = run();
            log.infof("S3RetentionCleanupBatchlet reaped %d items", reaped);
        } catch (RuntimeException e) {
            log.errorf(e, "S3RetentionCleanupBatchlet failed unexpectedly");
        }
    }

    @Transactional
    public int run() {
        return reapRevisions() + reapAppendices();
    }

    private int reapRevisions() {
        LocalDateTime now = LocalDateTime.now();
        List<CandidateDossierRevision> due = CandidateDossierRevision.list(
                "s3RetentionUntil IS NOT NULL AND s3RetentionUntil < ?1 " +
                "AND generatedPdfsSnapshot IS NOT NULL", now);

        int count = 0;
        for (CandidateDossierRevision rev : due) {
            try {
                List<GeneratedPdfRef> refs = objectMapper.readValue(
                        rev.getGeneratedPdfsSnapshot(),
                        new TypeReference<List<GeneratedPdfRef>>() {});
                for (GeneratedPdfRef ref : refs) {
                    if (ref.fileUuid() != null) {
                        s3StorageService.deleteGeneratedPdf(ref.fileUuid());
                    }
                }
                // generatedPdfsSnapshot is @Column(updatable=false) — leave the
                // audit trail intact. Only the lifecycle timestamp is nulled.
                rev.setS3RetentionUntil(null);
                count++;
            } catch (Exception e) {
                log.errorf(e,
                        "Failed to reap revision uuid=%s — leaving retention timestamp for retry",
                        rev.getUuid());
            }
        }
        return count;
    }

    private int reapAppendices() {
        LocalDateTime now = LocalDateTime.now();
        List<CandidateDossierAppendix> due = CandidateDossierAppendix.list(
                "s3RetentionUntil IS NOT NULL AND s3RetentionUntil < ?1 AND fileUuid IS NOT NULL", now);

        int count = 0;
        for (CandidateDossierAppendix a : due) {
            try {
                s3StorageService.deleteGeneratedPdf(a.getFileUuid());
                // fileUuid is NOT NULL in the DB — leave it pointing at the
                // (now-deleted) S3 key. Future fetches return 404 from S3,
                // and the lifecycle signal is s3RetentionUntil being NULL
                // combined with sharepoint_move_status=COMPLETED.
                a.setS3RetentionUntil(null);
                count++;
            } catch (Exception e) {
                log.errorf(e,
                        "Failed to reap appendix uuid=%s — leaving retention timestamp for retry",
                        a.getUuid());
            }
        }
        return count;
    }
}
