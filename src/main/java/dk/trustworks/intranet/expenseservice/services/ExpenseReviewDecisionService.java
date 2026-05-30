package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.time.LocalDate;

/**
 * The single source of expense override decisions (Phase 2). Both the single-decision
 * resource and the batch resource call these methods, so the state writes + decision-log
 * audit are identical everywhere. Each method requires state=NEEDS_ATTENTION.
 *
 * <p>Each method is {@code @Transactional} (REQUIRED → new tx), which makes per-row batch
 * processing safe: a batch resource can loop over uuids calling these methods, and one bad
 * row never rolls back the others because each call runs in its own transaction.
 */
@ApplicationScoped
public class ExpenseReviewDecisionService {

    @Inject ExpenseDecisionLogService logs;

    /** Approve a single expense. Throws if not found / not NEEDS_ATTENTION. */
    @Transactional
    public void approve(String uuid, String actorUuid, String reason) {
        Expense e = requireNeedsAttention(uuid);
        logs.recordHRApprove(e, actorUuid, reason);
        // Only advance CREATED → VALIDATED. Stranded rows whose status already moved past
        // CREATED (e.g. VERIFIED_UNBOOKED) already live in e-conomic; downgrading would
        // re-queue them and create a duplicate voucher.
        if ("CREATED".equals(e.getStatus())) {
            e.setStatus("VALIDATED");
        }
        e.setState(ExpenseStateDeriver.APPROVED);   // authoritative head write
        e.setAttentionOwner(null);
        e.setAttentionKind(null);
        e.setDatemodified(LocalDate.now());
    }

    /** Reject a single expense. Throws if not found / not NEEDS_ATTENTION. */
    @Transactional
    public void reject(String uuid, String actorUuid, String reason) {
        Expense e = requireNeedsAttention(uuid);
        logs.recordHRReject(e, actorUuid, reason);
        e.setStatus("DELETED");                      // excludes from pipelines (status<>DELETED)
        e.setState(ExpenseStateDeriver.REJECTED);    // authoritative terminal (survives hr_decision drop)
        e.setAttentionOwner(null);
        e.setAttentionKind(null);
        e.setDatemodified(LocalDate.now());
    }

    /** approve and reject are allowed on any item awaiting a decision. */
    private Expense requireNeedsAttention(String uuid) {
        Expense e = Expense.findById(uuid);
        if (e == null) throw new NotFoundException();
        if (!ExpenseStateDeriver.NEEDS_ATTENTION.equals(e.getState()))
            throw new BadRequestException("decision requires state=NEEDS_ATTENTION");
        return e;
    }
}
