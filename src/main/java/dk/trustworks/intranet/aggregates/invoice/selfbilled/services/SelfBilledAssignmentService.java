package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.AssignmentInput;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.AssignmentSourceType;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledAssignment;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledLine;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledLineStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The human decision layer (spec §5.1): create/replace/clear assignments per voucher,
 * sticky marks (same-company / ignore), voucher-status projection, and the §6.3
 * attribution mirror on EVERY mutation. Authority lives in selfbilled_assignment —
 * the parsed columns on selfbilled_line are suggestions only.
 */
@ApplicationScoped
public class SelfBilledAssignmentService {

    private static final Logger log = Logger.getLogger(SelfBilledAssignmentService.class);
    private static final BigDecimal SUM_TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal SETTLE_THRESHOLD = BigDecimal.ONE;

    @Inject SelfBilledCodeResolver codeResolver;          // issuer-as-of-period resolution
    @Inject SelfBilledDeltaQuery deltaQuery;
    @Inject SelfBilledAttributionMirror mirror;

    /** Replace the voucher's assignment set (1 = assign/edit, N = split). Cross-company allowed — this IS the human gate. */
    @Transactional
    public void assignVoucher(String lineUuid, List<AssignmentInput> inputs, String actor,
                              AssignmentSourceType source) {
        SelfBilledLine anchor = require(lineUuid);
        if (inputs == null || inputs.isEmpty()) {
            throw new WebApplicationException("At least one assignment is required", Response.Status.BAD_REQUEST);
        }
        List<SelfBilledLine> siblings = SelfBilledLine.findVoucherSiblings(anchor.accountNumber, anchor.voucherNumber);
        BigDecimal voucherNet = net(siblings);
        if (voucherNet.signum() == 0) {
            throw new WebApplicationException("Net-zero voucher needs no assignment", Response.Status.BAD_REQUEST);
        }

        // Signed shares: UI sends normalized positives; a single null share means the whole net.
        boolean isSplit = inputs.size() > 1;
        List<SelfBilledAssignment> next = new ArrayList<>(inputs.size());
        Set<String> seenTuples = new HashSet<>(inputs.size());
        BigDecimal signedSum = BigDecimal.ZERO;
        for (AssignmentInput in : inputs) {
            if (in.consultantUuid() == null || in.consultantUuid().isBlank()
                    || in.workYear() == null || in.workMonth() == null
                    || in.workMonth() < 1 || in.workMonth() > 12) {
                throw new WebApplicationException("consultantUuid, workYear and workMonth are required",
                        Response.Status.BAD_REQUEST);
            }
            if (isSplit && in.shareAmount() != null && in.shareAmount().signum() <= 0) {
                throw new WebApplicationException("shareAmount must be positive on a split",
                        Response.Status.BAD_REQUEST);
            }
            // Same consultant in two DIFFERENT periods is allowed; the exact (consultant, period)
            // tuple twice is not — it would double-count silently.
            if (!seenTuples.add(in.consultantUuid() + "|" + in.workYear() + "|" + in.workMonth())) {
                throw new WebApplicationException("duplicate assignment for consultant + period",
                        Response.Status.BAD_REQUEST);
            }
            BigDecimal signedShare = (in.shareAmount() == null && inputs.size() == 1)
                    ? voucherNet
                    : normalizedToSigned(in.shareAmount(), voucherNet);
            SelfBilledAssignment a = new SelfBilledAssignment();
            a.uuid = UUID.randomUUID().toString();
            a.selfbilledLineUuid = anchor.uuid;
            a.consultantUuid = in.consultantUuid();
            a.workYear = in.workYear();
            a.workMonth = in.workMonth();
            a.shareAmount = signedShare;
            a.assignedBy = actor;
            a.assignedAt = LocalDateTime.now();
            a.source = source;
            next.add(a);
            signedSum = signedSum.add(signedShare);
        }
        // Coverage invariant (AC3): a split must place exactly the voucher net.
        if (signedSum.subtract(voucherNet).abs().compareTo(SUM_TOLERANCE) > 0) {
            throw new WebApplicationException("Assignment shares (" + signedSum.negate()
                    + ") must sum to the voucher net (" + voucherNet.negate() + ")", Response.Status.BAD_REQUEST);
        }

        deleteVoucherAssignments(siblings);
        for (SelfBilledAssignment a : next) a.persist();

        refreshVoucher(anchor, siblings, next, voucherNet);
        log.infof("assignVoucher voucher=%d assignments=%d by=%s source=%s",
                anchor.voucherNumber, next.size(), actor, source);
    }

    /** Remove all assignments of the voucher; status falls back; mirror rows are cleared. */
    @Transactional
    public void clearAssignments(String lineUuid, String actor) {
        SelfBilledLine anchor = require(lineUuid);
        List<SelfBilledLine> siblings = SelfBilledLine.findVoucherSiblings(anchor.accountNumber, anchor.voucherNumber);
        deleteVoucherAssignments(siblings);
        refreshVoucher(anchor, siblings, List.of(), net(siblings));
        log.infof("clearAssignments voucher=%d by=%s", anchor.voucherNumber, actor);
    }

    /** Sticky human mark: needs no internal. Reversible until settled (spec §4.1). */
    @Transactional
    public void markSameCompany(String lineUuid, String actor) { mark(lineUuid, SelfBilledLineStatus.SAME_COMPANY, actor); }

    /** Sticky human mark: out of scope. Reversible until settled. */
    @Transactional
    public void markIgnored(String lineUuid, String actor) { mark(lineUuid, SelfBilledLineStatus.IGNORED, actor); }

    /** Reverse a sticky mark -> recompute from assignments. */
    @Transactional
    public void unmark(String lineUuid, String actor) {
        SelfBilledLine anchor = require(lineUuid);
        List<SelfBilledLine> siblings = SelfBilledLine.findVoucherSiblings(anchor.accountNumber, anchor.voucherNumber);
        stamp(siblings, SelfBilledLineStatus.UNASSIGNED);
        stampMark(siblings, actor);
        List<SelfBilledAssignment> current = SelfBilledAssignment.findByLines(siblings.stream().map(l -> l.uuid).toList());
        refreshVoucher(anchor, siblings, current, net(siblings));
        log.infof("unmark voucher=%d by=%s", anchor.voucherNumber, actor);
    }

    /** Recompute + stamp status for every voucher holding assignments of one settled group. */
    @Transactional
    public void recomputeForGroup(String clientUuid, String consultantUuid, int workYear, int workMonth) {
        List<String> lineUuids = SelfBilledAssignment
                .<SelfBilledAssignment>list("consultantUuid = ?1 and workYear = ?2 and workMonth = ?3", consultantUuid, workYear, workMonth)
                .stream().map(a -> a.selfbilledLineUuid).distinct().toList();
        for (String lu : lineUuids) {
            SelfBilledLine anchor = SelfBilledLine.findById(lu);
            if (anchor == null || !clientUuid.equals(anchor.clientUuid)) continue;
            List<SelfBilledLine> siblings = SelfBilledLine.findVoucherSiblings(anchor.accountNumber, anchor.voucherNumber);
            List<SelfBilledAssignment> current = SelfBilledAssignment.findByLines(siblings.stream().map(l -> l.uuid).toList());
            refreshVoucher(anchor, siblings, current, net(siblings));
        }
    }

    // ── internals ────────────────────────────────────────────────────

    private void mark(String lineUuid, SelfBilledLineStatus target, String actor) {
        SelfBilledLine anchor = require(lineUuid);
        List<SelfBilledLine> siblings = SelfBilledLine.findVoucherSiblings(anchor.accountNumber, anchor.voucherNumber);
        if (siblings.stream().anyMatch(l -> l.status == SelfBilledLineStatus.SETTLED)) {
            throw new WebApplicationException("Voucher is settled — edit its assignment instead",
                    Response.Status.CONFLICT);
        }
        deleteVoucherAssignments(siblings);   // a sticky mark supersedes any assignment
        List<SelfBilledAssignment> none = List.of();
        mirror.syncVoucher(siblings, none, net(siblings));
        stamp(siblings, target);
        stampMark(siblings, actor);
        log.infof("mark voucher=%d -> %s by=%s", anchor.voucherNumber, target, actor);
    }

    /** Status projection (pure rules) + §6.3 mirror sync, after any mutation. */
    private void refreshVoucher(SelfBilledLine anchor, List<SelfBilledLine> siblings,
                                List<SelfBilledAssignment> assignments, BigDecimal voucherNet) {
        boolean hasAssignments = !assignments.isEmpty();
        boolean allSameCompany = hasAssignments && assignments.stream().allMatch(a ->
                anchor.debtorCompanyUuid.equals(
                        codeResolver.resolveIssuerCompany(a.consultantUuid, a.workYear, a.workMonth)));
        boolean allSettled = hasAssignments && !allSameCompany && assignments.stream()
                .filter(a -> !anchor.debtorCompanyUuid.equals(
                        codeResolver.resolveIssuerCompany(a.consultantUuid, a.workYear, a.workMonth)))
                .allMatch(a -> deltaQuery.delta(anchor.clientUuid, anchor.debtorCompanyUuid,
                        a.consultantUuid, a.workYear, a.workMonth).abs().compareTo(SETTLE_THRESHOLD) <= 0);
        // current = the ANCHOR line's status — deterministic. Never siblings.get(0): the list is
        // unordered, and late-arriving voucher lines may carry a diverging (machine) status while
        // the anchor holds the human mark (Phase 1 amendment #2).
        SelfBilledLineStatus nextStatus = SelfBilledStatusRules.recompute(
                anchor.status, hasAssignments, allSameCompany, allSettled);
        stamp(siblings, nextStatus);
        mirror.syncVoucher(siblings, assignments, voucherNet);
    }

    private static void stamp(List<SelfBilledLine> siblings, SelfBilledLineStatus status) {
        for (SelfBilledLine l : siblings) l.status = status;   // managed entities — dirty-checked
    }

    /** Stamp the mark actor and timestamp on every sibling (Amendment #3 — AC7 durable audit). */
    private static void stampMark(List<SelfBilledLine> siblings, String actor) {
        LocalDateTime now = LocalDateTime.now();
        for (SelfBilledLine l : siblings) {
            l.markedBy = actor;
            l.markedAt = now;
        }
    }

    private static void deleteVoucherAssignments(List<SelfBilledLine> siblings) {
        SelfBilledAssignment.delete("selfbilledLineUuid in ?1", siblings.stream().map(l -> l.uuid).toList());
    }

    private static BigDecimal net(List<SelfBilledLine> siblings) {
        return siblings.stream().map(l -> l.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** UI positives -> stored signed: flip onto the voucher net's sign. */
    private static BigDecimal normalizedToSigned(BigDecimal normalized, BigDecimal voucherNet) {
        if (normalized == null) {
            throw new WebApplicationException("shareAmount is required on a split", Response.Status.BAD_REQUEST);
        }
        return voucherNet.signum() < 0 ? normalized.negate() : normalized;
    }

    private static SelfBilledLine require(String lineUuid) {
        SelfBilledLine line = SelfBilledLine.findById(lineUuid);
        if (line == null) throw new WebApplicationException("Unknown document line: " + lineUuid,
                Response.Status.NOT_FOUND);
        return line;
    }
}
