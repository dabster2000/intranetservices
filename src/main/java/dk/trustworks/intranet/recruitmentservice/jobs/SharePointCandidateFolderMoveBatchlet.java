package dk.trustworks.intranet.recruitmentservice.jobs;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SharePointMoveStatus;
import dk.trustworks.intranet.recruitmentservice.services.SharePointCandidateFolderService;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Optional;

/**
 * Periodic post-commit job that drains the SharePoint move queue.
 * <p>
 * The convert use case ({@code CandidateConversionUseCase}) flips the
 * candidate's {@code sharepoint_move_status} to {@link SharePointMoveStatus#PENDING}
 * inside its transaction. This batchlet picks up rows in that state and
 * delegates to
 * {@link SharePointCandidateFolderService#copyAndDeleteOnHire(RecruitmentCandidate, String)}
 * to copy the candidate's SharePoint folder to the new employee's home
 * folder and delete the recruitment-side folder. The Graph API is slow and
 * cannot be rolled back, which is why this work runs out-of-band from the
 * convert HTTP transaction (spec §11.6, AC #12).
 *
 * <h3>Idempotency</h3>
 * <ul>
 *   <li>The query targets {@code status = HIRED AND sharepoint_move_status = PENDING}
 *       only. Rows that have already transitioned out of {@code PENDING}
 *       (to {@code COMPLETED}, {@code PARTIAL}, or {@code FAILED}) are
 *       ignored on subsequent runs — re-running the batchlet is a no-op
 *       for those.</li>
 *   <li>If a row is in {@code COPIED} after a previous run that succeeded
 *       at copy but failed at delete, an admin retry would set the row
 *       back to {@code PENDING}; this batchlet then re-invokes the service
 *       which decides whether to skip the copy and only retry the delete
 *       (responsibility of {@link SharePointCandidateFolderService}).</li>
 * </ul>
 *
 * <h3>Schedule</h3>
 * Wired in {@code BatchScheduler.scheduleSharePointCandidateFolderMove()}
 * to run every 5 minutes — same cadence as
 * {@code NextSignStatusSyncBatchlet}, which is the closest analogue.
 */
@JBossLog
@Dependent
@Named("sharepointCandidateFolderMoveBatchlet")
public class SharePointCandidateFolderMoveBatchlet extends MonitoredBatchlet {

    @Inject
    SharePointCandidateFolderService folderService;

    @Override
    @Transactional
    protected String doProcess() throws Exception {
        log.debug("SharePointCandidateFolderMoveBatchlet: starting");

        List<RecruitmentCandidate> pending = RecruitmentCandidate
                .<RecruitmentCandidate>find(
                        "status = ?1 AND sharepointMoveStatus = ?2",
                        CandidateStatus.HIRED, SharePointMoveStatus.PENDING)
                .list();

        if (pending.isEmpty()) {
            log.debug("No pending SharePoint moves; skipping cycle");
            return "COMPLETED: 0 candidates processed";
        }

        log.infof("Found %d candidate(s) needing SharePoint folder move", pending.size());
        int completed = 0;
        int partial = 0;
        int failed = 0;
        int skipped = 0;

        for (RecruitmentCandidate candidate : pending) {
            try {
                Optional<String> targetUsername = resolveTargetUsername(candidate);
                if (targetUsername.isEmpty()) {
                    log.warnf("Skipping candidate uuid=%s: converted_user_uuid=%s has no resolvable username; marking FAILED",
                            candidate.getUuid(), candidate.getConvertedUserUuid());
                    candidate.setSharepointMoveStatus(SharePointMoveStatus.FAILED);
                    skipped++;
                    continue;
                }

                SharePointMoveStatus result = folderService.copyAndDeleteOnHire(
                        candidate, targetUsername.get());

                // Defensive: copyAndDeleteOnHire returning PENDING means it
                // did not finish — treat that as no-op for now (the entity
                // remains PENDING and will be retried next cycle). This
                // matches the spec's column-as-queue idempotency contract.
                if (result == SharePointMoveStatus.PENDING) {
                    log.infof("candidate uuid=%s still PENDING after move attempt; will retry next cycle",
                            candidate.getUuid());
                    continue;
                }

                candidate.setSharepointMoveStatus(result);
                switch (result) {
                    case COMPLETED -> completed++;
                    case PARTIAL -> partial++;
                    case FAILED -> failed++;
                    case COPIED -> {
                        // Copied but not deleted — admin can retry by
                        // resetting the row to PENDING. We log and count
                        // as partial for visibility.
                        partial++;
                        log.warnf("Candidate uuid=%s in COPIED state — folder duplicated, source not removed",
                                candidate.getUuid());
                    }
                    default -> log.warnf("Unexpected status returned for candidate uuid=%s: %s",
                            candidate.getUuid(), result);
                }
            } catch (RuntimeException e) {
                log.errorf(e, "SharePoint move failed for candidate uuid=%s", candidate.getUuid());
                candidate.setSharepointMoveStatus(SharePointMoveStatus.FAILED);
                failed++;
                reportNonFatalError(
                        "Move failed for candidate=" + candidate.getUuid() + ": " + e.getMessage(),
                        e);
            }
        }

        String summary = String.format(
                "COMPLETED: total=%d, completed=%d, partial=%d, failed=%d, skipped=%d",
                pending.size(), completed, partial, failed, skipped);
        log.info("SharePointCandidateFolderMoveBatchlet finished: " + summary);
        return summary;
    }

    /**
     * Look up the {@code username} of the user the candidate was converted
     * into. Returns {@link Optional#empty()} if the candidate has no
     * {@code converted_user_uuid} (which would indicate an invariant
     * violation upstream — a HIRED candidate without a user link) or the
     * resolved {@link User} has no username.
     */
    private static Optional<String> resolveTargetUsername(RecruitmentCandidate candidate) {
        String convertedUuid = candidate.getConvertedUserUuid();
        if (convertedUuid == null || convertedUuid.isBlank()) {
            return Optional.empty();
        }
        User user = User.findById(convertedUuid);
        if (user == null) {
            return Optional.empty();
        }
        String username = user.getUsername();
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(username);
    }
}
