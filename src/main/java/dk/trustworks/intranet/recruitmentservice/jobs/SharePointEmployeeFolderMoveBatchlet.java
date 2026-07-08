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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * JVM-lifetime record of candidate UUIDs whose converted-user username
     * currently cannot be resolved. The retry poll runs every ~5 minutes; a
     * HIRED candidate whose converted user cannot be resolved — a null
     * {@code convertedUserUuid} (a HIRED row that never reached
     * {@code markHired}'s invariant), a missing {@code user} row, or a null
     * username — can <em>never</em> be resolved by re-running, so without
     * throttling it emitted one WARN on every run (288/day in prod for a single
     * stuck candidate, drowning real signal).
     * <p>
     * We keep the per-run skip log at DEBUG and surface a single aggregated WARN
     * only when this set <em>grows</em> — i.e. a genuinely new data defect
     * appears. The set is reconciled to the live unresolvable set each run, so a
     * candidate that later resolves (or leaves the retry query) is dropped and
     * would WARN again if the condition ever recurs. Mirrors the static
     * dedup-set convention used by
     * {@link RecruitmentHrSlackNotifier} ({@code NOTIFIED_CANDIDATE_UUIDS}).
     */
    private static final Set<String> WARNED_UNRESOLVED_USERNAME_UUIDS =
            ConcurrentHashMap.newKeySet();

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
        Set<String> unresolvedUsernameThisRun = new HashSet<>();

        for (RecruitmentCandidate candidate : retryCandidates) {
            String username = resolveUsername(candidate);
            if (username == null) {
                // Permanent data condition — null convertedUserUuid, a missing
                // user row, or a null username — that re-running every poll cannot
                // fix. Keep the per-run line at DEBUG — mirroring the sibling "no active
                // EMPLOYEE SharePointLocation" skip below — and surface a single
                // aggregated WARN after the loop only when this set grows.
                unresolvedUsernameThisRun.add(candidate.getUuid());
                log.debugf("Skipping candidate=%s — converted user username could not be resolved",
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

        // Emit a single WARN listing only candidates that became unresolvable
        // since the previous run; steady-state (same stuck candidate every run)
        // stays silent. Then reconcile the tracking set to the live set.
        List<String> newlyUnresolved = newlyUnresolved(
                WARNED_UNRESOLVED_USERNAME_UUIDS, unresolvedUsernameThisRun);
        if (!newlyUnresolved.isEmpty()) {
            log.warnf("Skipping %d hired candidate(s) — converted user username could not be "
                            + "resolved (retried silently each run until the user record is fixed): %s",
                    newlyUnresolved.size(), String.join(", ", newlyUnresolved));
        }
        WARNED_UNRESOLVED_USERNAME_UUIDS.clear();
        WARNED_UNRESOLVED_USERNAME_UUIDS.addAll(unresolvedUsernameThisRun);

        String result = String.format(
                "COMPLETED: total=%d completed=%d stillFailing=%d skipped=%d",
                total, completed, stillFailing, skipped);
        log.info(result);
        return result;
    }

    /**
     * The UUIDs in {@code unresolvedThisRun} not already present in
     * {@code previouslyWarned}, in stable sorted order. Pure function — no
     * logging, no mutation of either argument — so the "WARN only when the skip
     * set grows" policy is unit-testable without a live DB (the batchlet's
     * Panache static finders cannot be mocked in a plain unit test).
     */
    static List<String> newlyUnresolved(Set<String> previouslyWarned,
                                        Set<String> unresolvedThisRun) {
        return unresolvedThisRun.stream()
                .filter(uuid -> !previouslyWarned.contains(uuid))
                .sorted()
                .toList();
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
