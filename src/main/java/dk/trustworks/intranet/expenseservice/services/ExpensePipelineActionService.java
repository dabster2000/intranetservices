package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.Set;

/**
 * P0 pipeline actions for NEEDS_ATTENTION rows the decision endpoints cannot help
 * (approve/send-back are structurally no-ops on TECHNICAL rows — the entity hook
 * re-derives NEEDS_ATTENTION from UP_FAILED/NO_FILE/NO_USER in the same transaction).
 *
 * <p>Two actions:
 * <ul>
 *   <li><b>requeue</b> — TECHNICAL rows only: reset the pipeline so the upload machinery
 *       retries, optionally with a corrected GL account (fixes the "Account(s) is not
 *       found or barred" class). Voucher-less rows go back to VALIDATED for a fresh
 *       upload; rows with a voucher stay UP_FAILED with retry counters cleared so
 *       {@code retryFailedWithVouchers} finishes the file upload against the existing
 *       voucher instead of creating a duplicate.</li>
 *   <li><b>close</b> — any NEEDS_ATTENTION row: an audited terminal
 *       ({@code CLOSED_MANUAL}) for expenses that will never post — booked manually in
 *       e-conomic, or written off. The exit the 2024-era graveyard never had.</li>
 * </ul>
 *
 * <p>Entity-level {@code apply*} methods hold the logic and are pure-unit-testable;
 * the uuid entry points add lookup + transaction.
 */
@JBossLog
@ApplicationScoped
public class ExpensePipelineActionService {

    public static final String RESOLUTION_BOOKED_MANUALLY = "BOOKED_MANUALLY";
    public static final String RESOLUTION_WRITTEN_OFF = "WRITTEN_OFF";
    private static final Set<String> RESOLUTIONS =
            Set.of(RESOLUTION_BOOKED_MANUALLY, RESOLUTION_WRITTEN_OFF);

    @Inject ExpenseDecisionLogService logs;

    @Transactional
    public void requeue(String uuid, String actorUuid, String account, String accountname, String reason) {
        Expense e = require(uuid);
        if (!ExpenseStateDeriver.KIND_TECHNICAL.equals(e.getAttentionKind())) {
            throw new BadRequestException("requeue requires a TECHNICAL item (pipeline failure)");
        }
        applyRequeue(e, actorUuid, account, accountname, reason);
    }

    @Transactional
    public void close(String uuid, String actorUuid, String resolution, String reference, String reason) {
        Expense e = require(uuid);
        applyClose(e, actorUuid, resolution, reference, reason);
    }

    /** Package-private for unit tests: pipeline reset on a TECHNICAL row. */
    void applyRequeue(Expense e, String actorUuid, String account, String accountname, String reason) {
        StringBuilder note = new StringBuilder(
                reason != null && !reason.isBlank() ? reason.trim() : "Re-queued for e-conomic upload");
        if (account != null && !account.isBlank()) {
            note.append(" (account ").append(e.getAccount()).append(" → ").append(account.trim()).append(")");
            e.setAccount(account.trim());
            if (accountname != null && !accountname.isBlank()) {
                e.setAccountname(accountname.trim());
            }
        }

        // A row with a voucher must NOT go back to VALIDATED: the item reader would post a
        // fresh voucher next to the existing one. Clearing the retry counters instead lets
        // the scheduled retryFailedWithVouchers finish the file upload against the voucher
        // it already has.
        String toStatus = e.getVouchernumber() > 0
                ? ExpenseService.STATUS_UP_FAILED
                : ExpenseService.STATUS_VALIDATED;

        // Log BEFORE mutating so the from-values are captured correctly.
        logs.recordPipelineRequeue(e, actorUuid, toStatus, note.toString());

        e.setStatus(toStatus);
        e.setErrorMessage(null);
        e.setRetryCount(0);
        e.setLastRetryAt(null);
        e.setDatemodified(LocalDate.now());
        // state/attention_owner/attention_kind/attention_since follow from the entity hook:
        // VALIDATED derives APPROVED (fresh upload); UP_FAILED stays NEEDS_ATTENTION with the
        // original attention_since preserved until the retry job resolves it.
        log.infof("Expense %s re-queued by %s -> status=%s%s",
                e.getUuid(), actorUuid, toStatus,
                account != null && !account.isBlank() ? " (account fixed)" : "");
    }

    /** Package-private for unit tests: audited manual terminal on any NEEDS_ATTENTION row. */
    void applyClose(Expense e, String actorUuid, String resolution, String reference, String reason) {
        if (resolution == null || !RESOLUTIONS.contains(resolution)) {
            throw new BadRequestException("resolution must be BOOKED_MANUALLY or WRITTEN_OFF");
        }
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("reason is required");
        }

        String note = "[Closed " + resolution
                + (reference != null && !reference.isBlank() ? ", ref " + reference.trim() : "")
                + "] " + reason.trim();

        // Log BEFORE mutating so the from-values are captured correctly.
        logs.recordPipelineClosed(e, actorUuid, resolution, note);

        e.setStatus(ExpenseStateDeriver.CLOSED_MANUAL);
        e.setAccountantNotes(e.getAccountantNotes() == null || e.getAccountantNotes().isBlank()
                ? note
                : e.getAccountantNotes() + "\n" + note);
        e.setDatemodified(LocalDate.now());
        // state=CLOSED_MANUAL + cleared attention fields follow from the entity hook.
        log.infof("Expense %s closed (%s) by %s", e.getUuid(), resolution, actorUuid);
    }

    private Expense require(String uuid) {
        Expense e = Expense.findById(uuid);
        if (e == null) throw new NotFoundException();
        if (!ExpenseStateDeriver.NEEDS_ATTENTION.equals(e.getState())) {
            throw new BadRequestException("pipeline actions require state=NEEDS_ATTENTION");
        }
        return e;
    }
}
