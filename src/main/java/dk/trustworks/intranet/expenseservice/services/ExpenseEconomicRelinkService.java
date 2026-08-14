package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.dto.ExpenseRelinkRequestDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseRelinkResultDTO;
import dk.trustworks.intranet.expenseservice.model.Expense;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.util.function.Supplier;

/**
 * Manually re-links a stranded expense to its moved/renumbered e-conomic voucher. Counterpart to
 * {@link ExpenseEconomicResendService}: re-send creates a NEW voucher, re-link points the expense
 * at an EXISTING one (the accountant's fiscal-year journal shuffle renumbers pre-marker vouchers,
 * which the nightly sync can never find again — see ExpenseSyncBatchlet's marker sweeps).
 *
 * <p>Every row is guarded three ways before anything is written: the expense must still be
 * VERIFIED_UNBOOKED, no other live expense may already claim the target voucher, and the target
 * must be PROVEN present in e-conomic via the three-state lookup (an UNKNOWN lookup fails the
 * row — never treated as presence or absence, same fail-closed rule as re-send).</p>
 */
@JBossLog
@ApplicationScoped
public class ExpenseEconomicRelinkService {

    @Inject EconomicsService economicsService;
    @Inject ExpenseDecisionLogService decisionLog;

    /** Outcome of the eligibility + verification decision for one re-link row. */
    enum Decision { APPLY, INELIGIBLE_STATUS, ALREADY_LINKED, TARGET_CLAIMED, TARGET_NOT_FOUND, ECONOMIC_UNKNOWN }

    /**
     * Pure decision core, cheapest check first; the e-conomic lookup only runs when every
     * local check has passed. Package-private so the DB-free tier can table-test it.
     */
    static Decision decide(String status, boolean alreadyAtTarget, boolean targetClaimed,
                           Supplier<EconomicsService.VoucherLookupResult> lookup) {
        if (!ExpenseService.STATUS_VERIFIED_UNBOOKED.equals(status)) return Decision.INELIGIBLE_STATUS;
        if (alreadyAtTarget) return Decision.ALREADY_LINKED;
        if (targetClaimed) return Decision.TARGET_CLAIMED;
        return switch (lookup.get()) {
            case FOUND -> Decision.APPLY;
            case NOT_FOUND -> Decision.TARGET_NOT_FOUND;
            case UNKNOWN -> Decision.ECONOMIC_UNKNOWN;
        };
    }

    @Transactional
    public ExpenseRelinkResultDTO.Applied relinkOne(ExpenseRelinkRequestDTO.Row row, String actorUuid, boolean dryRun) {
        Expense e = Expense.findById(row.uuid());
        if (e == null) throw new NotFoundException();

        boolean booked = row.target() == ExpenseRelinkRequestDTO.Target.BOOKED;
        // BOOKED targets keep the stored journal number (booked ledger is year+voucher addressed),
        // mirroring the sync's booked-marker heal which also only rewrites voucher + year.
        Integer targetJournal = booked ? e.getJournalnumber() : row.journalnumber();
        if (!booked && targetJournal == null) throw new BadRequestException("journalnumber required for DRAFT target");

        boolean alreadyAtTarget = !booked
                && targetJournal.equals(e.getJournalnumber())
                && row.vouchernumber() == e.getVouchernumber()
                && row.accountingyear().equals(e.getAccountingyear());
        Expense claimant = findClaimant(row, targetJournal, e.getUuid());

        Decision d = decide(e.getStatus(), alreadyAtTarget, claimant != null,
                () -> booked ? economicsService.checkVoucherBooked(probeFor(e, row, targetJournal))
                             : economicsService.checkVoucherExists(probeFor(e, row, targetJournal)));
        switch (d) {
            case INELIGIBLE_STATUS -> throw new BadRequestException("status is " + e.getStatus() + ", expected VERIFIED_UNBOOKED");
            case ALREADY_LINKED -> throw new BadRequestException("already linked to the target voucher");
            case TARGET_CLAIMED -> throw new BadRequestException("target voucher already linked to expense " + claimant.getUuid());
            case TARGET_NOT_FOUND -> throw new BadRequestException("target voucher not found in e-conomic — proposal stale?");
            case ECONOMIC_UNKNOWN -> throw new IllegalStateException("e-conomic lookup inconclusive — voucher state unknown, try again later");
            case APPLY -> { /* fall through */ }
        }

        String from = triple(e.getJournalnumber(), e.getAccountingyear(), e.getVouchernumber());
        String to = triple(targetJournal, row.accountingyear(), row.vouchernumber());
        String newStatus = booked ? ExpenseService.STATUS_VERIFIED_BOOKED : ExpenseService.STATUS_VERIFIED_UNBOOKED;
        if (dryRun) {
            return new ExpenseRelinkResultDTO.Applied(row.uuid(), from, to, newStatus);
        }

        // Audit first (reads the pre-mutation status), then mutate the managed entity — one
        // transaction, so the log row and the re-link commit or roll back together. The entity's
        // @PreUpdate hook re-derives `state` from the new status at flush.
        decisionLog.recordEconomicRelink(e, actorUuid, newStatus, "Re-linked to e-conomic voucher: " + from + " -> " + to);
        if (booked) {
            e.setVouchernumber(row.vouchernumber());
            e.setAccountingyear(row.accountingyear());
            e.setStatus(ExpenseService.STATUS_VERIFIED_BOOKED);
        } else {
            e.setJournalnumber(targetJournal);
            e.setVouchernumber(row.vouchernumber());
            e.setAccountingyear(row.accountingyear());
        }
        e.setSyncMissCount(0);
        e.clearOrphaned();
        log.infof("Expense %s re-linked %s -> %s (status %s) by %s", e.getUuid(), from, to, newStatus, actorUuid);
        return new ExpenseRelinkResultDTO.Applied(row.uuid(), from, to, newStatus);
    }

    /**
     * Another live expense already holding the target voucher. Draft vouchers are unique per
     * journal; booked ones per accounting year. A hit skips the row — wrongly relinking two
     * expenses to one voucher is silent double-claim corruption, a false-positive skip is just
     * a reported row the operator re-checks.
     */
    private Expense findClaimant(ExpenseRelinkRequestDTO.Row row, Integer targetJournal, String selfUuid) {
        if (row.target() == ExpenseRelinkRequestDTO.Target.BOOKED) {
            return Expense.find("vouchernumber = ?1 and accountingyear = ?2 and uuid <> ?3 and status <> ?4",
                    row.vouchernumber(), row.accountingyear(), selfUuid, ExpenseService.STATUS_DELETED).firstResult();
        }
        return Expense.find("journalnumber = ?1 and vouchernumber = ?2 and accountingyear = ?3 and uuid <> ?4 and status <> ?5",
                targetJournal, row.vouchernumber(), row.accountingyear(), selfUuid, ExpenseService.STATUS_DELETED).firstResult();
    }

    /**
     * Transient carrier for the TARGET triple so the three-state lookups (which read the
     * expense's stored triple) can verify the destination without mutating the managed row.
     * Never persisted; useruuid + expensedate ride along for company/token resolution.
     */
    private Expense probeFor(Expense e, ExpenseRelinkRequestDTO.Row row, Integer targetJournal) {
        Expense probe = new Expense();
        probe.setUuid(e.getUuid());
        probe.setUseruuid(e.getUseruuid());
        probe.setExpensedate(e.getExpensedate());
        probe.setJournalnumber(targetJournal);
        probe.setVouchernumber(row.vouchernumber());
        probe.setAccountingyear(row.accountingyear());
        return probe;
    }

    private static String triple(Integer journal, String year, int voucher) {
        return "j" + journal + "/" + year + "/v" + voucher;
    }
}
