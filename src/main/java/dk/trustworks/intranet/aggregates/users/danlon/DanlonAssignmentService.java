package dk.trustworks.intranet.aggregates.users.danlon;

import dk.trustworks.intranet.domain.user.entity.DanlonAssignmentProposal;
import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
}
