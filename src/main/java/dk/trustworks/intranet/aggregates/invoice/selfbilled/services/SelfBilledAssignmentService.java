package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.AssignmentInput;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.SelfBilledAssignmentDTO;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.SelfBilledCoverage;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.SelfBilledDocumentDTO;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.SelfBilledDocumentsResponse;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.SelfBilledSourceDTO;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.SelfBilledTieOut;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.AssignmentSourceType;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledAssignment;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledLine;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledLineStatus;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.work.ConsultantWorkRevenue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    @Inject EntityManager em;
    @Inject WorkService workService;

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

    // ── reads (worklist) ─────────────────────────────────────────────

    /** Workbench roster. */
    public List<SelfBilledSourceDTO> sources() {
        return dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledSource.listEnabled().stream()
                .map(s -> new SelfBilledSourceDTO(s.clientUuid, s.label, s.accountNumber, s.agreementCompanyUuid))
                .toList();
    }

    /**
     * Worklist for client + BOOKING window [from,to]. Lines without booking_date
     * (captured before V365) are excluded — run a capture first (runbook step 1).
     */
    @Transactional
    public SelfBilledDocumentsResponse documents(String clientUuid, LocalDate from, LocalDate to) {
        List<SelfBilledLine> lines = SelfBilledLine.list(
                "clientUuid = ?1 and bookingDate >= ?2 and bookingDate <= ?3 order by voucherNumber, entryNumber",
                clientUuid, from, to);

        // Group by voucher (account|voucher) preserving order.
        Map<String, List<SelfBilledLine>> byVoucher = new LinkedHashMap<>();
        for (SelfBilledLine l : lines) {
            byVoucher.computeIfAbsent(l.accountNumber + "|" + l.voucherNumber, k -> new ArrayList<>()).add(l);
        }

        Map<String, String> priorByCode = priorConsultantByCode(clientUuid);
        Map<Integer, Map<String, BigDecimal>> workCache = new HashMap<>();   // ym -> consultant -> value
        List<SelfBilledDocumentDTO> docs = new ArrayList<>(byVoucher.size());
        Set<String> nameLookups = new HashSet<>();

        for (List<SelfBilledLine> siblings : byVoucher.values()) {
            SelfBilledLine anchor = siblings.stream().filter(l -> l.code != null).findFirst()
                    .orElse(siblings.get(0));
            BigDecimal voucherNet = net(siblings);
            List<SelfBilledAssignment> assignments =
                    SelfBilledAssignment.findByLines(siblings.stream().map(l -> l.uuid).toList());

            // Suggestion (only meaningful while unplaced; harmless otherwise).
            AssignmentSuggester.Suggestion best = null;
            if (anchor.workYear != null && anchor.workMonth != null) {
                Integer anchorWorkYear = anchor.workYear;
                Integer anchorWorkMonth = anchor.workMonth;
                Map<String, BigDecimal> work = workCache.computeIfAbsent(
                        anchorWorkYear * 100 + anchorWorkMonth,
                        ym -> workValues(clientUuid, anchorWorkYear, anchorWorkMonth));
                List<AssignmentSuggester.Suggestion> ranked = AssignmentSuggester.suggest(
                        new AssignmentSuggester.SuggesterInput(anchor.code, anchor.workYear, anchor.workMonth,
                                anchor.consultantUuid, voucherNet.negate(), work, priorByCode));
                best = ranked.isEmpty() ? null : ranked.get(0);
            }
            if (best != null) nameLookups.add(best.consultantUuid());
            assignments.forEach(a -> nameLookups.add(a.consultantUuid));

            boolean crossCompany = isCrossCompany(anchor, assignments, best);
            docs.add(new SelfBilledDocumentDTO(
                    anchor.uuid, anchor.voucherNumber,
                    anchor.bookingDate == null ? null : anchor.bookingDate.format(DateTimeFormatter.ISO_DATE),
                    voucherNet.negate().doubleValue(), anchor.sourceText, anchor.fakturaNumber,
                    anchor.code, anchor.workYear, anchor.workMonth,
                    best == null ? null : best.consultantUuid(), null /* name filled below */,
                    best == null ? 0 : best.confidence(), best == null ? null : best.reason(),
                    anchor.status.name(), crossCompany, siblings.size(),
                    assignments.stream().map(a -> new SelfBilledAssignmentDTO(a.uuid, a.consultantUuid,
                            null, a.workYear, a.workMonth, a.shareAmount.negate().doubleValue(),
                            a.source.name(), a.assignedBy,
                            a.assignedAt == null ? null : a.assignedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))).toList()));
        }

        Map<String, String> names = consultantNames(nameLookups);
        List<SelfBilledDocumentDTO> withNames = docs.stream().map(d -> new SelfBilledDocumentDTO(
                d.lineUuid(), d.voucherNumber(), d.bookingDate(), d.amount(), d.sourceText(), d.fakturaNumber(),
                d.suggestedCode(), d.suggestedWorkYear(), d.suggestedWorkMonth(),
                d.suggestedConsultantUuid(), names.get(d.suggestedConsultantUuid()),
                d.suggestionConfidence(), d.suggestionReason(), d.status(), d.crossCompany(), d.entryCount(),
                d.assignments().stream().map(a -> new SelfBilledAssignmentDTO(a.uuid(), a.consultantUuid(),
                        names.get(a.consultantUuid()), a.workYear(), a.workMonth(), a.shareAmount(),
                        a.source(), a.assignedBy(), a.assignedAt())).toList())).toList();

        return new SelfBilledDocumentsResponse(withNames, coverage(lines), tieOut(lines));
    }

    /**
     * Bulk-accept high-confidence SAME-COMPANY suggestions (AC2: cross-company is
     * never auto-assigned). Returns the number of vouchers placed.
     */
    @Transactional
    public int acceptSuggestedSameCompany(String clientUuid, LocalDate from, LocalDate to, String actor) {
        SelfBilledDocumentsResponse current = documents(clientUuid, from, to);
        int accepted = 0;
        for (SelfBilledDocumentDTO d : current.documents()) {
            if (!SelfBilledLineStatus.UNASSIGNED.name().equals(d.status())) continue;
            if (d.suggestedConsultantUuid() == null
                    || d.suggestionConfidence() < AssignmentSuggester.HIGH_CONFIDENCE) continue;
            String issuer = codeResolver.resolveIssuerCompany(
                    d.suggestedConsultantUuid(), d.suggestedWorkYear(), d.suggestedWorkMonth());
            SelfBilledLine anchor = SelfBilledLine.findById(d.lineUuid());
            if (issuer == null || !issuer.equals(anchor.debtorCompanyUuid)) continue;   // cross-company -> human
            assignVoucher(d.lineUuid(), List.of(new AssignmentInput(
                    d.suggestedConsultantUuid(), d.suggestedWorkYear(), d.suggestedWorkMonth(), null)),
                    actor, AssignmentSourceType.AUTO_SAMECOMPANY);
            accepted++;
        }
        log.infof("acceptSuggestedSameCompany client=%s window=%s..%s accepted=%d by=%s",
                clientUuid, from, to, accepted, actor);
        return accepted;
    }

    // ── read helpers ─────────────────────────────────────────────────

    private SelfBilledCoverage coverage(List<SelfBilledLine> lines) {
        double captured = 0, assigned = 0, same = 0, unassigned = 0, ignored = 0;
        Set<String> vouchers = new HashSet<>(), placed = new HashSet<>();
        for (SelfBilledLine l : lines) {
            double v = l.amount.negate().doubleValue();
            captured += v;
            switch (l.status) {
                case ASSIGNED, SETTLED -> assigned += v;
                case SAME_COMPANY -> same += v;
                case IGNORED -> ignored += v;
                default -> unassigned += v;
            }
            String key = l.accountNumber + "|" + l.voucherNumber;
            vouchers.add(key);
            if (l.status != SelfBilledLineStatus.UNASSIGNED) placed.add(key);
        }
        return new SelfBilledCoverage(captured, assigned, same, unassigned, ignored,
                vouchers.size(), placed.size());
    }

    /** AC9: Σ captured net vs PHANTOM-imported item totals for the SAME entry numbers. */
    private SelfBilledTieOut tieOut(List<SelfBilledLine> lines) {
        if (lines.isEmpty()) return new SelfBilledTieOut(0, 0, 0, 0, 0, true);
        List<Long> entries = lines.stream().map(l -> l.entryNumber).toList();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT COUNT(DISTINCT p.uuid), COALESCE(SUM(ii.hours*ii.rate), 0)
                FROM invoices p JOIN invoiceitems ii ON ii.invoiceuuid = p.uuid
                WHERE p.type = 'PHANTOM' AND p.economics_entry_number IN (:entries)
                """).setParameter("entries", entries).getResultList();
        double phantomImported = ((Number) rows.get(0)[1]).doubleValue();
        int matched = ((Number) rows.get(0)[0]).intValue();
        double capturedNet = lines.stream().map(l -> l.amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .negate().doubleValue();
        double delta = capturedNet - phantomImported;
        return new SelfBilledTieOut(capturedNet, phantomImported, delta,
                entries.size(), matched, Math.abs(delta) <= 1.0);
    }

    /** Most recent human assignment per code (suggester signal 4). */
    private Map<String, String> priorConsultantByCode(String clientUuid) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT l.code, a.consultant_uuid
                FROM selfbilled_assignment a
                JOIN selfbilled_line l ON l.uuid = a.selfbilled_line_uuid
                WHERE l.client_uuid = :c AND l.code IS NOT NULL
                ORDER BY a.assigned_at ASC
                """).setParameter("c", clientUuid).getResultList();
        Map<String, String> out = new HashMap<>();
        for (Object[] r : rows) out.put((String) r[0], (String) r[1]);   // later rows win
        return out;
    }

    /** Registered work value per consultant via the task->project->client join (spec §5.1 work cross-check). */
    private Map<String, BigDecimal> workValues(String clientUuid, int year, int month) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (ConsultantWorkRevenue r : workService.findRevenueByClientAndMonth(clientUuid, year, month)) {
            out.put(r.useruuid(), r.revenue() == null ? BigDecimal.ZERO : r.revenue());
        }
        return out;
    }

    private boolean isCrossCompany(SelfBilledLine anchor, List<SelfBilledAssignment> assignments,
                                   AssignmentSuggester.Suggestion best) {
        if (!assignments.isEmpty()) {
            return assignments.stream().anyMatch(a -> {
                String issuer = codeResolver.resolveIssuerCompany(a.consultantUuid, a.workYear, a.workMonth);
                return issuer != null && !issuer.equals(anchor.debtorCompanyUuid);
            });
        }
        if (best != null) {
            String issuer = codeResolver.resolveIssuerCompany(best.consultantUuid(), best.workYear(), best.workMonth());
            return issuer != null && !issuer.equals(anchor.debtorCompanyUuid);
        }
        return false;
    }

    private Map<String, String> consultantNames(Set<String> uuids) {
        Map<String, String> out = new HashMap<>();
        if (uuids.isEmpty()) return out;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT uuid, CONCAT(firstname,' ',lastname) FROM user WHERE uuid IN (:ids)")
                .setParameter("ids", uuids).getResultList();
        for (Object[] r : rows) out.put((String) r[0], (String) r[1]);
        return out;
    }
}
