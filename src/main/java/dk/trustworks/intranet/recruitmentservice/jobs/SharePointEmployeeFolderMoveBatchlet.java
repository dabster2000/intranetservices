package dk.trustworks.intranet.recruitmentservice.jobs;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.documentservice.model.SharePointLocationEntity;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SharePointMoveStatus;
import dk.trustworks.intranet.recruitmentservice.notifications.RecruitmentHrSlackNotifier;
import dk.trustworks.intranet.recruitmentservice.services.SharePointEmployeeFolderService;
import dk.trustworks.intranet.recruitmentservice.services.SharePointEmployeeFolderService.CopyResult;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.UUID;

/**
 * Retry batchlet for SharePoint employee-folder moves whose synchronous
 * attempt during {@code CandidateConversionUseCase.execute} did not reach
 * {@link SharePointMoveStatus#COMPLETED}. Picks up rows in
 * {@code PENDING}, {@code PARTIAL}, or {@code FAILED} state and re-runs the
 * copy via {@link SharePointEmployeeFolderService#copyToEmployeeFolder}.
 * <p>
 * On {@code COMPLETED}, stamps {@code s3_retention_until = NOW + 30 days} on
 * every revision with non-null {@code generated_pdfs_snapshot} and every
 * appendix with non-null {@code file_uuid} for the candidate, mirroring the
 * use case.
 */
@JBossLog
@Dependent
@Named("sharepointEmployeeFolderMoveBatchlet")
public class SharePointEmployeeFolderMoveBatchlet extends MonitoredBatchlet {

    @Inject
    SharePointEmployeeFolderService folderService;

    @Inject
    RecruitmentHrSlackNotifier recruitmentHrSlackNotifier;

    @Override
    @Transactional
    protected String doProcess() throws Exception {
        List<RecruitmentCandidate> retryCandidates = RecruitmentCandidate.list(
                "status = ?1 AND sharepointMoveStatus IN (?2, ?3, ?4)",
                CandidateStatus.HIRED,
                SharePointMoveStatus.PENDING,
                SharePointMoveStatus.PARTIAL,
                SharePointMoveStatus.FAILED);

        int total = retryCandidates.size();
        int completed = 0;
        int stillFailing = 0;
        int skipped = 0;

        for (RecruitmentCandidate candidate : retryCandidates) {
            String username = resolveUsername(candidate);
            if (username == null) {
                log.warnf("Skipping candidate=%s — converted user username could not be resolved",
                        candidate.getUuid());
                skipped++;
                continue;
            }

            SharePointLocationEntity location = folderService.resolveEmployeeLocation(
                    candidate.getConvertedUserUuid());
            if (location == null) {
                log.debugf("Skipping candidate=%s — no active EMPLOYEE SharePointLocation for promoted user=%s",
                        candidate.getUuid(), candidate.getConvertedUserUuid());
                skipped++;
                continue;
            }

            CopyResult result;
            try {
                result = folderService.copyToEmployeeFolder(candidate, username, location);
            } catch (RuntimeException e) {
                log.warnf(e, "Retry threw for candidate=%s — leaving sharepoint_move_status unchanged",
                        candidate.getUuid());
                stillFailing++;
                continue;
            }
            SharePointMoveStatus status = result.status();
            candidate.setSharepointMoveStatus(status);

            if (status == SharePointMoveStatus.COMPLETED) {
                folderService.stampS3RetentionUntil(candidate);
                // The dedup set inside RecruitmentHrSlackNotifier ensures the
                // batchlet does not re-fire a notification that the original
                // Convert flow already sent.
                UUID recruiter = parseUuidOrNull(candidate.getCreatedByUseruuid());
                recruitmentHrSlackNotifier.notifyHire(candidate, recruiter, result.signedFilenames());
                completed++;
            } else {
                stillFailing++;
            }
        }

        String result = String.format(
                "COMPLETED: total=%d completed=%d stillFailing=%d skipped=%d",
                total, completed, stillFailing, skipped);
        log.info(result);
        return result;
    }

    private static String resolveUsername(RecruitmentCandidate candidate) {
        if (candidate.getConvertedUserUuid() == null) return null;
        User user = User.findById(candidate.getConvertedUserUuid());
        return user != null ? user.getUsername() : null;
    }

    private static UUID parseUuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
