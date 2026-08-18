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
    @Inject ExpenseAccountSuggestionService accountSuggestions;

    /**
     * W3: assign the GL account and approve in one decision — the one-click action of
     * the "Assign account" micro-queue (classifier-fallback rows). Validates the
     * account against the employee's current company before touching anything.
     */
    @Transactional
    public void assignAccountAndApprove(String uuid, String actorUuid, int account) {
        Expense e = requireNeedsAttention(uuid);
        requireApprovable(e);
        var accountRow = accountSuggestions.requireAssignable(e.getUseruuid(), account);
        e.setAccount(String.valueOf(account));
        e.setAccountname(accountRow.getAccountName());
        logs.recordHRApprove(e, actorUuid,
                "Account assigned: " + account + " " + accountRow.getAccountName());
        if ("CREATED".equals(e.getStatus())) {
            e.setStatus("VALIDATED");
        }
        e.setState(ExpenseStateDeriver.APPROVED);
        e.setAttentionOwner(null);
        e.setAttentionKind(null);
        e.setDatemodified(LocalDate.now());
    }

    /** Approve a single expense. Throws if not found / not NEEDS_ATTENTION / TECHNICAL. */
    @Transactional
    public void approve(String uuid, String actorUuid, String reason) {
        Expense e = requireNeedsAttention(uuid);
        requireApprovable(e);
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
        requireRejectable(e);
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

    /**
     * P0 guard: approving a TECHNICAL row is structurally a no-op — the entity hook
     * re-derives NEEDS_ATTENTION from UP_FAILED/NO_FILE/NO_USER in this same transaction,
     * so only the audit row and a datemodified reset would survive (observed in prod:
     * four "Accounting approved" entries on one 2024 receipt still in the queue).
     * Thrown BEFORE the log write so no fictional approval is recorded.
     * Package-private static for plain unit tests.
     */
    static void requireApprovable(Expense e) {
        if (ExpenseStateDeriver.KIND_TECHNICAL.equals(e.getAttentionKind())) {
            throw new BadRequestException(
                    "technical pipeline failure — approve cannot fix it; use requeue or close instead");
        }
    }

    /**
     * P0 guard: rejecting a TECHNICAL row that already has an e-conomic voucher would
     * soft-delete our row while the draft voucher lives on in e-conomic (the DELETE
     * endpoint blocks the same case via ExpenseDeletePolicy). Close it instead, or
     * remove the voucher in e-conomic first. Package-private static for plain unit tests.
     */
    static void requireRejectable(Expense e) {
        if (ExpenseStateDeriver.KIND_TECHNICAL.equals(e.getAttentionKind()) && e.getVouchernumber() > 0) {
            throw new BadRequestException(
                    "a voucher already exists in e-conomic — close the expense (booked manually / written off) "
                    + "or delete the voucher in e-conomic first");
        }
    }
}
