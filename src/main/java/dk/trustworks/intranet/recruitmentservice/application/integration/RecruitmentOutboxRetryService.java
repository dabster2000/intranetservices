package dk.trustworks.intranet.recruitmentservice.application.integration;

import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxStatus;
import dk.trustworks.intranet.recruitmentservice.domain.integration.RecruitmentOutboxRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Re-arms a single FAILED outbox row of a given {@link OutboxKind} for an
 * interview so the {@code RecruitmentOutboxWorker} will pick it up on the next
 * drain. Resets {@code attemptCount} so the operator gets the full backoff
 * runway again, and clears the previous error message.
 *
 * <p>{@link #findFailed(String, OutboxKind)} is a package-private seam so unit
 * tests can override it — Mockito cannot stub Panache statics inherited from
 * {@code PanacheEntityBase}.
 */
@ApplicationScoped
public class RecruitmentOutboxRetryService {

    @Transactional
    public boolean retryFailedRow(String interviewUuid, OutboxKind kind) {
        Optional<RecruitmentOutboxRow> opt = findFailed(interviewUuid, kind);
        if (opt.isEmpty()) return false;
        RecruitmentOutboxRow row = opt.get();
        LocalDateTime now = LocalDateTime.now();
        row.status = OutboxStatus.PENDING;
        row.attemptCount = 0;
        row.lastError = null;
        row.nextRetryAt = now;
        row.updatedAt = now;
        return true;
    }

    Optional<RecruitmentOutboxRow> findFailed(String interviewUuid, OutboxKind kind) {
        return RecruitmentOutboxRow.<RecruitmentOutboxRow>find(
                        "relatedUuid = ?1 AND kind = ?2 AND status = ?3 ORDER BY createdAt DESC",
                        interviewUuid, kind, OutboxStatus.FAILED)
                .firstResultOptional();
    }
}
