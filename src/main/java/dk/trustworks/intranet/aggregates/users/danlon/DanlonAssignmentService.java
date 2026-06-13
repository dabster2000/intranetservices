package dk.trustworks.intranet.aggregates.users.danlon;

import dk.trustworks.intranet.aggregates.users.danlon.dto.DanlonProposalView;
import dk.trustworks.intranet.domain.user.entity.DanlonAssignmentProposal;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The single funnel for Danløn-number lifecycle changes (spec §5).
 * Detection calls {@link #proposeIfNeeded}; HR approval calls
 * approveProposal/rejectProposal (Phase 06). This class is the ONLY code
 * that may write user_danlon_history (invariant #2). proposeIfNeeded is
 * idempotent and never throws into the caller's business transaction
 * (invariant: a Danløn glitch must not roll back a salary/status save).
 */
@JBossLog
@ApplicationScoped
public class DanlonAssignmentService {

    static final String DETECTOR = "system-detector";

    @Inject
    DanlonNumberSequenceService numberSequenceService;

    /** Public contract (spec §5): detection delegates here. */
    @Transactional
    public ProposalOutcome proposeIfNeeded(String useruuid, LocalDate effectiveMonth,
                                           DanlonEventType eventType, String companyUuid) {
        return proposeIfNeeded(useruuid, effectiveMonth, eventType, companyUuid, DETECTOR);
    }

    /** Internal overload carrying the audit source (e.g. "system-reconciliation"). */
    @Transactional
    ProposalOutcome proposeIfNeeded(String useruuid, LocalDate effectiveMonth,
                                    DanlonEventType eventType, String companyUuid, String detectedBy) {
        LocalDate month = effectiveMonth.withDayOfMonth(1);

        UserDanlonHistory row = UserDanlonHistory.findRowForMonth(useruuid, month);
        if (row != null) {
            boolean matches = slotMatches(row, companyUuid, eventType);
            if (!row.isClosed()) {
                if (matches) {
                    log.debugf("ALREADY_MINTED: user=%s month=%s event=%s — OPEN row %s exists",
                            useruuid, month, eventType, row.getDanlon());
                    return ProposalOutcome.ALREADY_MINTED;
                }
                log.warnf("CONFLICT: user=%s month=%s already has an OPEN Danløn row for a different slot "
                                + "(row company=%s event=%s) vs detected company=%s event=%s — not minting (Approach-A one-row-per-month bound, spec §4.4)",
                        useruuid, month, row.getCompanyUuid(), row.getEventType(), companyUuid, eventType);
                return ProposalOutcome.CONFLICT;
            }
            // CLOSED row for the month
            if (matches) {
                DanlonAssignmentProposal existing =
                        DanlonAssignmentProposal.findPendingForSlot(useruuid, companyUuid, month, eventType);
                if (existing != null) {
                    log.debugf("ALREADY_PROPOSED (reopen): user=%s month=%s event=%s — pending reopen proposal already exists", useruuid, month, eventType);
                    return ProposalOutcome.ALREADY_PROPOSED;
                }
                raiseProposal(useruuid, companyUuid, month, eventType, ProposalIntent.REOPEN,
                        row.getUuid(), row.getDanlon(), detectedBy);
                log.infof("REOPEN_PROPOSED: user=%s month=%s reopen number=%s", useruuid, month, row.getDanlon());
                return ProposalOutcome.REOPEN_PROPOSED;
            }
            log.warnf("CONFLICT: user=%s month=%s has a CLOSED row for a different slot "
                            + "(row company=%s event=%s) vs detected company=%s event=%s — cannot mint a second row (Approach-A bound)",
                    useruuid, month, row.getCompanyUuid(), row.getEventType(), companyUuid, eventType);
            return ProposalOutcome.CONFLICT;
        }

        // No history row this month → look for an existing PENDING proposal for the slot.
        DanlonAssignmentProposal pending =
                DanlonAssignmentProposal.findPendingForSlot(useruuid, companyUuid, month, eventType);
        if (pending != null) {
            log.debugf("ALREADY_PROPOSED: user=%s month=%s event=%s", useruuid, month, eventType);
            return ProposalOutcome.ALREADY_PROPOSED;
        }

        raiseProposal(useruuid, companyUuid, month, eventType, ProposalIntent.MINT,
                null, numberSequenceService.nextSuggestedNumber(), detectedBy);
        log.infof("CREATED MINT proposal: user=%s month=%s event=%s company=%s", useruuid, month, eventType, companyUuid);
        return ProposalOutcome.CREATED;
    }

    /** Lenient slot match: legacy NULL company/event matches any slot (spec §4.4). */
    private boolean slotMatches(UserDanlonHistory row, String companyUuid, DanlonEventType eventType) {
        boolean companyOk = row.getCompanyUuid() == null || row.getCompanyUuid().equals(companyUuid);
        boolean eventOk = row.getEventType() == null || row.getEventType().equals(eventType.name());
        return companyOk && eventOk;
    }

    private void raiseProposal(String useruuid, String companyUuid, LocalDate month, DanlonEventType eventType,
                               ProposalIntent intent, String targetHistoryUuid, String suggestedNumber, String detectedBy) {
        DanlonAssignmentProposal p = new DanlonAssignmentProposal();
        p.setUuid(UUID.randomUUID().toString());
        p.setUseruuid(useruuid);
        p.setCompanyUuid(companyUuid);
        p.setEffectiveMonth(month);
        p.setEventType(eventType);
        p.setIntent(intent);
        p.setStatus(ProposalStatus.PENDING);
        p.setSuggestedNumber(suggestedNumber);
        p.setTargetHistoryUuid(targetHistoryUuid);
        p.setDetectedDate(LocalDateTime.now());
        p.setDetectedBy(detectedBy);
        p.persist();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HR minting authority (Phase 06) — the ONLY code that writes user_danlon_history
    // ─────────────────────────────────────────────────────────────────────────

    /** HR mints/reopens/closes via an approved proposal — the ONLY write path to user_danlon_history. */
    @Transactional
    public UserDanlonHistory approveProposal(String proposalUuid, String confirmedNumber, String resolvedBy) {
        DanlonAssignmentProposal p = DanlonAssignmentProposal.findById(proposalUuid);
        if (p == null) throw new DanlonProposalException("Proposal not found: " + proposalUuid);
        if (p.getStatus() != ProposalStatus.PENDING)
            throw new DanlonProposalException("Proposal " + proposalUuid + " is not PENDING (is " + p.getStatus() + ")");

        UserDanlonHistory result;
        switch (p.getIntent()) {
            case MINT -> {
                String number = (confirmedNumber != null && !confirmedNumber.isBlank())
                        ? confirmedNumber.trim() : p.getSuggestedNumber();
                if (number == null || number.isBlank())
                    throw new DanlonProposalException("No confirmed or suggested number for MINT proposal " + proposalUuid);
                // Dup-guard (invariant #5): the number must not be OPEN for a different user.
                for (UserDanlonHistory open : UserDanlonHistory.findOpenByDanlon(number)) {
                    if (!open.getUseruuid().equals(p.getUseruuid()))
                        throw new DanlonProposalException("Danløn number " + number
                                + " is already OPEN for another user (" + open.getUseruuid() + "); choose a different number.");
                }
                UserDanlonHistory row = new UserDanlonHistory(p.getUseruuid(), p.getEffectiveMonth(), number, resolvedBy);
                row.setCompanyUuid(p.getCompanyUuid());
                row.setEventType(p.getEventType() != null ? p.getEventType().name() : null);
                row.persist();
                p.setMintedHistoryUuid(row.getUuid());
                result = row;
            }
            case REOPEN -> {
                UserDanlonHistory target = requireTarget(p);
                target.setClosedDate(null);
                target.setClosedReason(null);
                result = target;
            }
            case CLOSE -> {
                UserDanlonHistory target = requireTarget(p);
                target.setClosedDate(LocalDateTime.now());
                target.setClosedReason(p.getResolutionNote() != null ? p.getResolutionNote()
                        : "closed via proposal " + proposalUuid);
                result = target;
            }
            default -> throw new DanlonProposalException("Unknown intent " + p.getIntent());
        }
        p.setStatus(ProposalStatus.APPROVED);
        p.setResolvedDate(LocalDateTime.now());
        p.setResolvedBy(resolvedBy);
        log.infof("APPROVED proposal %s intent=%s user=%s number=%s by=%s",
                proposalUuid, p.getIntent(), p.getUseruuid(), result.getDanlon(), resolvedBy);
        return result;
    }

    @Transactional
    public void rejectProposal(String proposalUuid, String reason, String resolvedBy) {
        DanlonAssignmentProposal p = DanlonAssignmentProposal.findById(proposalUuid);
        if (p == null) throw new DanlonProposalException("Proposal not found: " + proposalUuid);
        if (p.getStatus() != ProposalStatus.PENDING)
            throw new DanlonProposalException("Proposal " + proposalUuid + " is not PENDING (is " + p.getStatus() + ")");
        p.setStatus(ProposalStatus.REJECTED);
        p.setResolvedDate(LocalDateTime.now());
        p.setResolvedBy(resolvedBy);
        p.setResolutionNote(reason);
        log.infof("REJECTED proposal %s by=%s reason=%s", proposalUuid, resolvedBy, reason);
    }

    /** Delete-path entry point: raise a PENDING CLOSE proposal for a minted row. Idempotent; never hard-deletes. */
    @Transactional
    public ProposalOutcome proposeClose(String targetHistoryUuid, String reason) {
        UserDanlonHistory target = UserDanlonHistory.findById(targetHistoryUuid);
        if (target == null) {
            log.warnf("proposeClose: target row %s not found — SKIPPED", targetHistoryUuid);
            return ProposalOutcome.SKIPPED;
        }
        if (target.isClosed()) return ProposalOutcome.SKIPPED;
        DanlonEventType eventType;
        try {
            eventType = DanlonEventType.valueOf(target.getEventType());
        } catch (Exception e) {
            log.warnf("proposeClose: row %s has no recognised event_type (%s) — SKIPPED (manual/legacy row, never auto-closed)",
                    targetHistoryUuid, target.getEventType());
            return ProposalOutcome.SKIPPED;
        }
        if (DanlonAssignmentProposal.findPendingCloseForTarget(targetHistoryUuid) != null)
            return ProposalOutcome.ALREADY_PROPOSED;

        DanlonAssignmentProposal p = new DanlonAssignmentProposal();
        p.setUuid(UUID.randomUUID().toString());
        p.setUseruuid(target.getUseruuid());
        if (target.getCompanyUuid() == null) {
            log.warnf("proposeClose: target row %s (number %s) has NULL company_uuid (legacy/backfilled row); "
                    + "CLOSE proposal will use 'unknown' company and won't appear in any company panel until a real company is derived (Phase 14 follow-up)",
                    targetHistoryUuid, target.getDanlon());
        }
        p.setCompanyUuid(target.getCompanyUuid() != null ? target.getCompanyUuid() : "unknown");
        p.setEffectiveMonth(target.getActiveDate());
        p.setEventType(eventType);
        p.setIntent(ProposalIntent.CLOSE);
        p.setStatus(ProposalStatus.PENDING);
        p.setSuggestedNumber(target.getDanlon());
        p.setTargetHistoryUuid(targetHistoryUuid);
        p.setDetectedDate(LocalDateTime.now());
        p.setDetectedBy("system-delete-detector");
        p.setResolutionNote(reason);
        p.persist();
        log.infof("CLOSE_PROPOSED %s for row %s (number %s): %s", p.getUuid(), targetHistoryUuid, target.getDanlon(), reason);
        return ProposalOutcome.CLOSE_PROPOSED;
    }

    /** Panel data: PENDING proposals for a company+month as views. */
    @Transactional
    public List<DanlonProposalView> listPending(String companyUuid, LocalDate month) {
        LocalDate m = month.withDayOfMonth(1);
        return DanlonAssignmentProposal.findPendingByCompanyMonth(companyUuid, m).stream().map(this::toView).toList();
    }

    private UserDanlonHistory requireTarget(DanlonAssignmentProposal p) {
        if (p.getTargetHistoryUuid() == null)
            throw new DanlonProposalException("Proposal " + p.getUuid() + " has no target history row");
        UserDanlonHistory target = UserDanlonHistory.findById(p.getTargetHistoryUuid());
        if (target == null)
            throw new DanlonProposalException("Target history row not found: " + p.getTargetHistoryUuid());
        return target;
    }

    private DanlonProposalView toView(DanlonAssignmentProposal p) {
        User user = User.findById(p.getUseruuid());
        String name = (user != null) ? user.getFullname() : p.getUseruuid();
        String currentNumber = UserDanlonHistory.findDanlonAsOf(p.getUseruuid(), p.getEffectiveMonth());
        return new DanlonProposalView(
                p.getUuid(), p.getUseruuid(), name, p.getCompanyUuid(), p.getEffectiveMonth(),
                p.getEventType(), p.getIntent(), p.getSuggestedNumber(), p.getStatus(),
                p.getTargetHistoryUuid(), currentNumber, p.getDetectedDate(), p.getDetectedBy());
    }
}
