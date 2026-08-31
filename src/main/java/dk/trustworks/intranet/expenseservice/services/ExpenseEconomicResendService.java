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
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Manually re-pushes a "lost" expense to the e-conomic journal. Reuses the orphan-retry
 * idempotency path ({@code markAsOrphaned + incrementRetryCount}) so e-conomic creates a NEW
 * voucher, then {@link EconomicsService#sendVoucher} persists the new triple + re-attaches the
 * receipt. The expense's status/state are never changed.
 * <p>
 * "Lost" is the whole point: the voucher must be gone. When it is still in e-conomic — draft or
 * booked — {@link #resendOne} refuses unless the caller passes {@code confirmDuplicate}, so a
 * duplicate is always a deliberate, audited choice rather than a side effect of a bulk select.
 */
@JBossLog
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

    /**
     * Where the expense's stored voucher currently sits in e-conomic. {@code DRAFT} and
     * {@code BOOKED} both mean "re-sending duplicates it"; {@code UNKNOWN} means the lookup
     * could not tell and must never be read as absence.
     */
    record VoucherState(EconomicsService.VoucherLookupResult draft,
                        EconomicsService.VoucherLookupResult booked) {

        private static final EconomicsService.VoucherLookupResult FOUND = EconomicsService.VoucherLookupResult.FOUND;
        private static final EconomicsService.VoucherLookupResult UNKNOWN = EconomicsService.VoucherLookupResult.UNKNOWN;

        /** The voucher is still in e-conomic — a re-send mints a second one for the same cost. */
        boolean exists() { return draft == FOUND || booked == FOUND; }

        /** At least one lookup failed, so absence is NOT proven. */
        boolean indeterminate() { return draft == UNKNOWN || booked == UNKNOWN; }

        String location() {
            if (draft == FOUND) return "DRAFT";
            if (booked == FOUND) return "BOOKED";
            return indeterminate() ? "UNKNOWN" : "MISSING";
        }
    }

    /**
     * Both lookups, run once. The booked check is skipped when the draft check already proved a
     * hit — a draft voucher cannot simultaneously be in the booked ledger, so the second call
     * would only cost an e-conomic round trip per row of a 500-row batch.
     */
    VoucherState lookupVoucherState(Expense e) {
        EconomicsService.VoucherLookupResult draft = economicsService.checkVoucherExists(e);
        if (draft == EconomicsService.VoucherLookupResult.FOUND) {
            return new VoucherState(draft, EconomicsService.VoucherLookupResult.NOT_FOUND);
        }
        return new VoucherState(draft, economicsService.checkVoucherBooked(e));
    }

    @Transactional
    public ExpenseResendPrecheckDTO precheckOne(String uuid) {
        Expense e = Expense.findById(uuid);
        if (e == null) return new ExpenseResendPrecheckDTO(uuid, false, "not found", false, "MISSING");
        if ("DELETED".equals(e.getStatus())) return new ExpenseResendPrecheckDTO(uuid, false, "deleted", false, "MISSING");
        if (!isPosted(e)) return new ExpenseResendPrecheckDTO(uuid, false, "not posted yet", false, "MISSING");
        if (UserAccount.findById(e.getUseruuid()) == null)
            return new ExpenseResendPrecheckDTO(uuid, false, "no e-conomic account", false, "MISSING");

        VoucherState state = lookupVoucherState(e);
        // Eligible, but voucherExists=true: the caller MUST confirm before resendOne will run
        // (see the confirmDuplicate gate below) — DRAFT and BOOKED both duplicate the cost.
        if (state.exists()) return new ExpenseResendPrecheckDTO(uuid, true, null, true, state.location());
        if (state.indeterminate())
            // e-conomic lookup failed — "MISSING" here would invite a duplicate re-send
            // (2026-08-12: 503 outage made a booked voucher precheck as MISSING).
            return new ExpenseResendPrecheckDTO(uuid, false, "e-conomic unavailable — voucher state unknown", false, "UNKNOWN");
        return new ExpenseResendPrecheckDTO(uuid, true, null, false, "MISSING");
    }

    /**
     * @param confirmDuplicate the caller has seen the precheck's duplicate warning and still wants
     *                         a second voucher. Without it a voucher that is still in e-conomic is
     *                         refused outright — no confirmation, no duplicate.
     */
    @Transactional
    public void resendOne(String uuid, String actorUuid, boolean confirmDuplicate) {
        if (!economicsUploadEnabled) {
            // Validate existence first so unknown uuids still report "not found".
            if (Expense.findById(uuid) == null) throw new NotFoundException();
            throw new BadRequestException("e-conomic upload disabled in this environment");
        }
        Expense e = requireResendable(uuid);
        UserAccount ua = UserAccount.findById(e.getUseruuid());

        VoucherState state = lookupVoucherState(e);

        // Fail closed on an indeterminate voucher state: when a lookup errors (5xx/network),
        // the voucher may well still exist, and re-sending would create a duplicate. Matches
        // precheckOne, so the precheck can never say UNKNOWN while a re-send sails through.
        if (state.indeterminate() && !state.exists()) {
            throw new BadRequestException("e-conomic unavailable — cannot verify voucher state, try again later");
        }

        // The voucher is still there. Re-sending mints a SECOND voucher for a cost e-conomic
        // already carries — and if the expense was booked, booking the duplicate credits the
        // employee's clearing account again (2026-08-07: 93 already-booked expenses re-sent in
        // one batch, 849,50 kr left sitting as an unbooked duplicate draft). Server-side gate,
        // not a UI dialog: an unconfirmed request must never be able to create one.
        if (state.exists() && !confirmDuplicate) {
            throw new BadRequestException("voucher still in e-conomic (" + state.location()
                    + ") — re-sending creates a duplicate; confirm to proceed");
        }

        ExpenseFile file;
        try {
            file = expenseFileService.getFileById(uuid);
        } catch (ExpenseFileNotFoundException ex) {
            throw new NotFoundException("receipt unavailable: " + uuid, ex);
        } catch (Exception ex) {
            log.errorf(ex, "Could not load receipt for e-conomic re-send: %s", uuid);
            throw new RuntimeException("receipt unavailable", ex);
        }

        int oldVoucher = e.getVouchernumber();
        // Force a fresh idempotency key (orphan-retry path) so e-conomic creates a NEW voucher.
        e.markAsOrphaned();
        e.incrementRetryCount();
        try {
            economicsService.sendVoucher(e, file, ua); // persists new triple + re-attaches receipt; no status change
        } catch (Exception ex) {
            // RuntimeException ⇒ this @Transactional rolls back (orphan flag / retry bump reverted).
            log.errorf(ex, "E-conomic re-send failed for expense: %s", uuid);
            throw new RuntimeException("re-send failed", ex);
        }
        e.clearOrphaned();
        decisionLog.recordEconomicResend(e, actorUuid,
                "Re-sent to e-conomic: voucher " + oldVoucher + " -> " + e.getVouchernumber());
    }
}
