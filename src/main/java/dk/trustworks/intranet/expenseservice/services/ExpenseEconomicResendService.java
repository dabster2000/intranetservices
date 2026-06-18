package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.dto.ExpenseResendPrecheckDTO;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Manually re-pushes a "lost" expense to the e-conomic journal. Reuses the orphan-retry
 * idempotency path ({@code markAsOrphaned + incrementRetryCount}) so e-conomic creates a NEW
 * voucher, then {@link EconomicsService#sendVoucher} persists the new triple + re-attaches the
 * receipt. The expense's status/state are never changed.
 */
@ApplicationScoped
public class ExpenseEconomicResendService {

    @Inject EconomicsService economicsService;
    @Inject ExpenseFileService expenseFileService;
    @Inject ExpenseDecisionLogService decisionLog;

    @ConfigProperty(name = "dk.trustworks.expense.economics-upload.enabled", defaultValue = "true")
    boolean economicsUploadEnabled;

    /** Posted = already reached e-conomic (has a voucher or an upload-stage status). */
    boolean isPosted(Expense e) {
        if (e.getVouchernumber() > 0) return true;
        String s = e.getStatus();
        return "UPLOADED".equals(s) || "VOUCHER_CREATED".equals(s)
                || "VERIFIED_UNBOOKED".equals(s) || "VERIFIED_BOOKED".equals(s);
    }

    /** Throws BadRequestException with a skip reason when not re-sendable; returns the loaded expense otherwise. */
    Expense requireResendable(String uuid) {
        Expense e = Expense.findById(uuid);
        if (e == null) throw new NotFoundException();
        if ("DELETED".equals(e.getStatus())) throw new BadRequestException("deleted");
        if (!isPosted(e)) throw new BadRequestException("not posted yet");
        if (UserAccount.findById(e.getUseruuid()) == null) throw new BadRequestException("no e-conomic account");
        return e;
    }

    @Transactional
    public ExpenseResendPrecheckDTO precheckOne(String uuid) {
        Expense e = Expense.findById(uuid);
        if (e == null) return new ExpenseResendPrecheckDTO(uuid, false, "not found", false, "MISSING");
        if ("DELETED".equals(e.getStatus())) return new ExpenseResendPrecheckDTO(uuid, false, "deleted", false, "MISSING");
        if (!isPosted(e)) return new ExpenseResendPrecheckDTO(uuid, false, "not posted yet", false, "MISSING");
        if (UserAccount.findById(e.getUseruuid()) == null)
            return new ExpenseResendPrecheckDTO(uuid, false, "no e-conomic account", false, "MISSING");

        if (economicsService.verifyVoucherExists(e))
            return new ExpenseResendPrecheckDTO(uuid, true, null, true, "DRAFT");
        if (economicsService.voucherBookedInLedger(e))
            return new ExpenseResendPrecheckDTO(uuid, true, null, true, "BOOKED");
        return new ExpenseResendPrecheckDTO(uuid, true, null, false, "MISSING");
    }

    @Transactional
    public void resendOne(String uuid, String actorUuid) {
        if (!economicsUploadEnabled) {
            // Validate existence first so unknown uuids still report "not found".
            if (Expense.findById(uuid) == null) throw new NotFoundException();
            throw new BadRequestException("e-conomic upload disabled in this environment");
        }
        Expense e = requireResendable(uuid);
        UserAccount ua = UserAccount.findById(e.getUseruuid());

        ExpenseFile file;
        try {
            file = expenseFileService.getFileById(uuid);
        } catch (Exception ex) {
            throw new RuntimeException("receipt unavailable: " + ex.getMessage(), ex);
        }

        int oldVoucher = e.getVouchernumber();
        // Force a fresh idempotency key (orphan-retry path) so e-conomic creates a NEW voucher.
        e.markAsOrphaned();
        e.incrementRetryCount();
        try {
            economicsService.sendVoucher(e, file, ua); // persists new triple + re-attaches receipt; no status change
        } catch (Exception ex) {
            // RuntimeException ⇒ this @Transactional rolls back (orphan flag / retry bump reverted).
            throw new RuntimeException("re-send failed: "
                    + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()), ex);
        }
        e.clearOrphaned();
        decisionLog.recordEconomicResend(e, actorUuid,
                "Re-sent to e-conomic: voucher " + oldVoucher + " -> " + e.getVouchernumber());
    }
}
