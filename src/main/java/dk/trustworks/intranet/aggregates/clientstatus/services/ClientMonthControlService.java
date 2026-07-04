package dk.trustworks.intranet.aggregates.clientstatus.services;

import dk.trustworks.intranet.aggregates.clientstatus.ClientStatusMath;
import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusCell;
import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusControlDto;
import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusResponse;
import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusRow;
import dk.trustworks.intranet.aggregates.clientstatus.model.ClientMonthControl;
import dk.trustworks.intranet.aggregates.clientstatus.model.ClientMonthControlHistory;
import dk.trustworks.intranet.aggregates.clientstatus.model.ClientMonthControlHistory.Action;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Orchestrates the client×month controlling workflow: single-cell upsert (approve / unapprove /
 * note) and month-wide bulk approval. Business decisions (drift, eligibility) live in the pure
 * {@link ClientStatusMath} helpers so they are unit-testable without a database; this service only
 * loads/persists the {@link ClientMonthControl} aggregate, applies the state transition, and appends
 * an audit row.
 */
@JBossLog
@ApplicationScoped
public class ClientMonthControlService {

    /** Bulk-approve reach. */
    public enum Scope {FULL_ONLY, ALL_REMAINING}

    @Inject
    ClientStatusService clientStatusService;

    /**
     * Upsert the controlling state of one client-month cell.
     *
     * @param clientUuid       client
     * @param month            first-of-month
     * @param approved         null = leave approval unchanged; true = approve/re-approve (snapshot
     *                         current values); false = clear the approval (keeps the note)
     * @param note             null = leave note unchanged; "" = clear; else set
     * @param currentExpected  live expected value (frozen into the snapshot when approving)
     * @param currentInvoiced  live invoiced value (frozen into the snapshot when approving)
     * @param actorUuid        user performing the change
     * @throws BadRequestException when both {@code approved} and {@code note} are null (no-op)
     */
    @Transactional
    public ClientStatusControlDto upsert(String clientUuid, LocalDate month,
                                         Boolean approved, String note,
                                         double currentExpected, double currentInvoiced,
                                         String actorUuid) {
        if (approved == null && note == null) {
            throw new BadRequestException("At least one of 'approved' or 'note' must be provided");
        }

        ClientMonthControl ctrl = ClientMonthControl.findByClientAndMonth(clientUuid, month);
        boolean isNew = ctrl == null;
        if (isNew) {
            ctrl = new ClientMonthControl();
            ctrl.clientUuid = clientUuid;
            ctrl.month = month;
            ctrl.createdBy = actorUuid;
        }
        boolean wasApproved = !isNew && ctrl.isApproved();

        ClientMonthControlUpsert.Decision decision =
                ClientMonthControlUpsert.decide(wasApproved, ctrl.note, approved, note);

        // Nothing actually changed (e.g. note set to its current value, or unapprove on a
        // never-approved cell): don't persist a new row or an empty audit entry.
        if (decision.isNoOp()) {
            return toDto(clientUuid, month, ctrl, currentExpected, currentInvoiced);
        }

        if (decision.noteChanged()) {
            ctrl.note = decision.newNote();
        }
        if (decision.approvalAction() == Action.APPROVED || decision.approvalAction() == Action.REAPPROVED) {
            ctrl.approvedAt = LocalDateTime.now(ZoneOffset.UTC);
            ctrl.approvedBy = actorUuid;
            ctrl.approvedExpected = currentExpected;
            ctrl.approvedInvoiced = currentInvoiced;
        } else if (decision.approvalAction() == Action.UNAPPROVED) {
            ctrl.approvedAt = null;
            ctrl.approvedBy = null;
            ctrl.approvedExpected = null;
            ctrl.approvedInvoiced = null;
        }

        if (!isNew) {
            ctrl.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
            ctrl.updatedBy = actorUuid;
        }
        ctrl.persist();

        // The approval transition (if any) is recorded as the history action; otherwise the note edit.
        appendHistory(ctrl, decision.historyAction(), actorUuid);
        return toDto(clientUuid, month, ctrl, currentExpected, currentInvoiced);
    }

    /**
     * Approve every eligible cell in {@code month} (recomputed server-side from the grid). Eligible
     * cells are non-provisional, have activity, and are not already effectively approved (drifted
     * approvals are re-eligible); {@code FULL_ONLY} additionally requires the cell to be FULL. Each
     * approval snapshots the current values and records a {@link Action#BULK_APPROVED} audit row.
     *
     * @return the clients whose cells were approved (distinct names), in grid order
     */
    @Transactional
    public BulkResult bulkApprove(YearMonth month, Scope scope, String actorUuid) {
        boolean fullOnly = scope == Scope.FULL_ONLY;
        String monthKey = String.format("%04d%02d", month.getYear(), month.getMonthValue());
        LocalDate monthDate = month.atDay(1);

        ClientStatusResponse grid = clientStatusService.getClientStatus(month);
        boolean provisional = grid.provisionalMonths().contains(monthKey);

        List<String> clientNames = new ArrayList<>();
        LinkedHashSet<String> distinctNames = new LinkedHashSet<>();
        int approvedCount = 0;

        for (ClientStatusRow row : grid.clients()) {
            ClientStatusCell cell = row.cells().stream()
                    .filter(c -> c.monthKey().equals(monthKey))
                    .findFirst().orElse(null);
            if (cell == null) continue;
            if (!ClientStatusMath.isBulkApprovable(cell.status(), provisional,
                    cell.approved(), cell.drift(), fullOnly)) {
                continue;
            }
            approveCell(row.clientUuid(), monthDate, cell.expected(), cell.invoiced(), actorUuid);
            approvedCount++;
            if (distinctNames.add(row.clientName())) clientNames.add(row.clientName());
        }
        return new BulkResult(approvedCount, clientNames);
    }

    /** Approve one cell (used by bulk approve): snapshot current values + BULK_APPROVED audit row. */
    private void approveCell(String clientUuid, LocalDate month,
                             double expected, double invoiced, String actorUuid) {
        ClientMonthControl ctrl = ClientMonthControl.findByClientAndMonth(clientUuid, month);
        boolean isNew = ctrl == null;
        if (isNew) {
            ctrl = new ClientMonthControl();
            ctrl.clientUuid = clientUuid;
            ctrl.month = month;
            ctrl.createdBy = actorUuid;
        } else {
            ctrl.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
            ctrl.updatedBy = actorUuid;
        }
        ctrl.approvedAt = LocalDateTime.now(ZoneOffset.UTC);
        ctrl.approvedBy = actorUuid;
        ctrl.approvedExpected = expected;
        ctrl.approvedInvoiced = invoiced;
        ctrl.persist();
        appendHistory(ctrl, Action.BULK_APPROVED, actorUuid);
    }

    private void appendHistory(ClientMonthControl ctrl, Action action, String actorUuid) {
        new ClientMonthControlHistory(ctrl.uuid, ctrl.clientUuid, ctrl.month, action,
                ctrl.note, ctrl.approvedExpected, ctrl.approvedInvoiced, actorUuid).persist();
    }

    private ClientStatusControlDto toDto(String clientUuid, LocalDate month, ClientMonthControl ctrl,
                                         double currentExpected, double currentInvoiced) {
        boolean approved = ctrl.isApproved();
        boolean drift = ClientStatusMath.isDrifted(approved,
                ctrl.approvedExpected, ctrl.approvedInvoiced, currentExpected, currentInvoiced);
        String approvedByName = ctrl.approvedBy == null ? null
                : clientStatusService.resolveUserName(ctrl.approvedBy);
        String monthKey = String.format("%04d%02d", month.getYear(), month.getMonthValue());
        return new ClientStatusControlDto(
                clientUuid, monthKey, approved, ctrl.approvedBy, approvedByName,
                ctrl.approvedAt == null ? null : ctrl.approvedAt.atOffset(ZoneOffset.UTC).toString(),
                ctrl.note, ctrl.approvedExpected, ctrl.approvedInvoiced, drift);
    }

    /** Bulk-approve outcome. */
    public record BulkResult(int approvedCount, List<String> clientNames) {}
}
