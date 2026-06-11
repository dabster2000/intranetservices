package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupKey;
import dk.trustworks.intranet.aggregates.invoice.model.AttributionAuditLog;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.AssignContextDTO;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.ConsultantPeriodRow;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.QueuedInternalRow;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.SettleRequest;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledAssignment;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledLine;
import dk.trustworks.intranet.aggregates.invoice.services.InternalInvoiceOrchestrator;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceService;
import dk.trustworks.intranet.aggregates.invoice.services.PhantomSettlementService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.work.ConsultantWorkRevenue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pass-through settlement from HUMAN assignments (spec §6.1): for one
 * (client, consultant, work-period), target = -Σ assigned share, settled =
 * Σ stamped live internals for that consultant, delta books ONE internal
 * (delta > +1) or credit note (delta < -1) at the delta amount. The work-value
 * estimate can never enter (AC1). Reuses representativePhantom + the
 * createSettlementInternal plumbing + the open-QUEUED guard unchanged.
 */
@ApplicationScoped
public class SelfBilledSettlementService {

    private static final Logger log = Logger.getLogger(SelfBilledSettlementService.class);
    private static final BigDecimal THRESHOLD = BigDecimal.ONE; // 1 kr

    @Inject EntityManager em;
    @Inject InvoiceService invoiceService;
    @Inject InternalInvoiceOrchestrator orchestrator;
    @Inject PhantomSettlementService phantomSettlementService;   // settled side + duplicate guard (reuse, §8)
    @Inject SelfBilledDeltaQuery deltaQuery;
    @Inject SelfBilledCodeResolver codeResolver;
    @Inject SelfBilledAssignmentService assignmentService;
    @Inject HistoryReconciliationService historyService;         // unlinked-candidate count (AC8 warning)
    @Inject WorkService workService;

    /** Consultants-tab rows: per cross-company (consultant, work-period) in the work window [fromYm,toYm]. */
    @Transactional
    public List<ConsultantPeriodRow> consultantRows(String clientUuid, int fromYm, int toYm) {
        String debtor = requireDebtor(clientUuid);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT a.consultant_uuid, a.work_year, a.work_month, COALESCE(-SUM(a.share_amount), 0)
                FROM selfbilled_assignment a
                JOIN selfbilled_line l ON l.uuid = a.selfbilled_line_uuid
                WHERE l.client_uuid = :c AND (a.work_year*100 + a.work_month) BETWEEN :from AND :to
                GROUP BY a.consultant_uuid, a.work_year, a.work_month
                ORDER BY a.work_year, a.work_month, a.consultant_uuid
                """).setParameter("c", clientUuid).setParameter("from", fromYm).setParameter("to", toYm)
                .getResultList();

        int unlinked = historyService.unlinkedCandidateCount();
        Map<Integer, Map<String, BigDecimal>> workCache = new HashMap<>();
        Set<String> consultantUuids = new HashSet<>();
        Set<String> companyUuids = new HashSet<>();
        List<Object[]> kept = new ArrayList<>();

        for (Object[] r : rows) {
            String consultant = (String) r[0];
            int y = ((Number) r[1]).intValue(), m = ((Number) r[2]).intValue();
            String issuer = codeResolver.resolveIssuerCompany(consultant, y, m);
            if (issuer == null || issuer.equals(debtor)) continue;   // same-company/unresolved -> not a settle row
            kept.add(new Object[]{consultant, y, m, r[3], issuer});
            consultantUuids.add(consultant);
            companyUuids.add(issuer);
        }
        Map<String, String> names = consultantNames(consultantUuids);
        Map<String, String> companies = companyNames(companyUuids);

        List<ConsultantPeriodRow> out = new ArrayList<>(kept.size());
        for (Object[] r : kept) {
            String consultant = (String) r[0];
            int y = (Integer) r[1], m = (Integer) r[2];
            BigDecimal assigned = toBig(r[3]).setScale(2, RoundingMode.HALF_UP);
            String issuer = (String) r[4];
            BigDecimal settled = deltaQuery.settled(new SettlementGroupKey(clientUuid, debtor, y, m), consultant);
            BigDecimal delta = assigned.subtract(settled);
            BigDecimal work = workCache.computeIfAbsent(y * 100 + m, ym -> workValues(clientUuid, y, m))
                    .getOrDefault(consultant, BigDecimal.ZERO);
            out.add(new ConsultantPeriodRow(consultant, names.getOrDefault(consultant, consultant), y, m,
                    issuer, companies.getOrDefault(issuer, issuer),
                    assigned.doubleValue(), settled.doubleValue(), delta.doubleValue(),
                    work.doubleValue(), delta.abs().compareTo(THRESHOLD) > 0, unlinked));
        }
        return out;
    }

    /**
     * Queued-lane read (Feature 3b): settlement-stamped QUEUED INTERNAL invoices for the client whose
     * settlement period (settlement_year*100 + settlement_month) falls in the window [fromYm,toYm]. Per
     * invoice: total = Σ items (hours*rate), consultant = the items' consultant, and paid/outstanding from
     * the underlying self-billing vouchers' 8610 remainder (via {@link SelfBilledDeltaQuery#voucherRemainders}
     * + {@link SelfBilledPaidGate#allPaid}) — paid when every backing voucher's 8610 remainder is exactly 0
     * (the client paid the self-billing invoice). Consultant identity is masked at the resource.
     */
    @Transactional
    public List<QueuedInternalRow> queuedInternals(String clientUuid, int fromYm, int toYm) {
        String debtor = requireDebtor(clientUuid);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT i.uuid, MAX(ii.consultantuuid), i.settlement_year, i.settlement_month,
                       COALESCE(SUM(ii.hours*ii.rate), 0)
                FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.type = 'INTERNAL' AND i.status = 'QUEUED'
                  AND i.settlement_billing_client_uuid = :c
                  AND i.settlement_year IS NOT NULL AND i.settlement_month IS NOT NULL
                  AND (i.settlement_year*100 + i.settlement_month) BETWEEN :from AND :to
                  AND ii.consultantuuid IS NOT NULL
                GROUP BY i.uuid, i.settlement_year, i.settlement_month
                ORDER BY i.settlement_year, i.settlement_month
                """).setParameter("c", clientUuid).setParameter("from", fromYm).setParameter("to", toYm)
                .getResultList();

        Set<String> consultantUuids = new HashSet<>();
        for (Object[] r : rows) consultantUuids.add((String) r[1]);
        Map<String, String> names = consultantNames(consultantUuids);

        List<QueuedInternalRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String invoiceUuid = (String) r[0];
            String consultant = (String) r[1];
            int y = ((Number) r[2]).intValue(), m = ((Number) r[3]).intValue();
            double total = toBig(r[4]).setScale(2, RoundingMode.HALF_UP).doubleValue();
            List<SelfBilledPaidGate.VoucherRemainder> remainders =
                    deltaQuery.voucherRemainders(clientUuid, debtor, consultant, y, m);
            boolean paid = SelfBilledPaidGate.allPaid(remainders);
            BigDecimal outstanding = remainders.stream()
                    .map(SelfBilledPaidGate.VoucherRemainder::remainder)
                    .filter(rem -> rem != null && rem.signum() > 0)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            out.add(new QueuedInternalRow(invoiceUuid, consultant, names.getOrDefault(consultant, consultant),
                    y, m, total, paid, outstanding.doubleValue()));
        }
        return out;
    }

    /**
     * Assign-modal context for the SELECTED (client, consultant, work-period) — which may differ
     * from the row's suggestion. Read-only composition: same debtor resolution as settle, the
     * issuer resolved as-of the work period (null when unresolvable), the cross-company verdict,
     * both company names, and the registered-work CROSS-CHECK value (never a settlement basis — AC1).
     * No @Transactional needed: pure reads through the same helpers consultantRows uses.
     */
    public AssignContextDTO assignContext(String clientUuid, String consultantUuid, int year, int month) {
        String debtor = requireDebtor(clientUuid);
        String issuer = codeResolver.resolveIssuerCompany(consultantUuid, year, month);
        boolean crossCompany = issuer != null && !issuer.equals(debtor);

        Set<String> companyUuids = new HashSet<>();
        companyUuids.add(debtor);
        if (issuer != null) companyUuids.add(issuer);
        Map<String, String> companies = companyNames(companyUuids);

        double workValue = workValues(clientUuid, year, month)
                .getOrDefault(consultantUuid, BigDecimal.ZERO).doubleValue();

        return new AssignContextDTO(crossCompany,
                issuer, issuer == null ? null : companies.getOrDefault(issuer, issuer),
                debtor, companies.getOrDefault(debtor, debtor),
                workValue);
    }

    /**
     * Human-clicked settle for ONE (client, consultant, work-period). NOT @Transactional:
     * createSettlementInternal commits in its own tx; the guard reads a fresh snapshot
     * (same structure as PhantomSettlementService.settleGroup). Contract: empty list =
     * delta within threshold (nothing to book, idempotent success); 409 = stale voucher
     * OR open QUEUED internal (human must act in the workbench first); a created-but-not-
     * finalized internal is still returned (finalize failure is logged, never swallowed
     * into a silent no-op).
     */
    public List<String> settleConsultantPeriod(SettleRequest req, String actor) {
        String debtor = requireDebtor(req.clientUuid());
        String issuer = codeResolver.resolveIssuerCompany(req.consultantUuid(), req.workYear(), req.workMonth());
        if (issuer == null) {
            throw new WebApplicationException("Consultant has no company as-of the work period",
                    Response.Status.BAD_REQUEST);
        }
        if (issuer.equals(debtor)) {
            throw new WebApplicationException("Same-company — no internal invoice applies",
                    Response.Status.BAD_REQUEST);
        }
        SettlementGroupKey key = new SettlementGroupKey(req.clientUuid(), debtor, req.workYear(), req.workMonth());
        BigDecimal target = deltaQuery.target(req.clientUuid(), req.consultantUuid(), req.workYear(), req.workMonth());
        BigDecimal settled = deltaQuery.settled(key, req.consultantUuid());
        BigDecimal delta = target.subtract(settled);
        if (delta.abs().compareTo(THRESHOLD) <= 0) {
            log.infof("settleConsultantPeriod: delta %s within threshold — nothing to book (key=%s consultant=%s)",
                    delta, key.asString(), req.consultantUuid());
            return List.of();
        }
        if (phantomSettlementService.hasOpenQueuedInternal(key, issuer)) {
            log.warnf("settleConsultantPeriod: open QUEUED internal for key=%s issuer=%s — skipping",
                    key.asString(), issuer);
            throw new WebApplicationException(
                    "An open QUEUED internal already exists for " + key.asString() + " issuer " + issuer
                            + " — force-create or delete it before settling again.",
                    Response.Status.CONFLICT);
        }
        assertVouchersUnchanged(req.clientUuid(), req.consultantUuid(), req.workYear(), req.workMonth());
        String representative = representativePhantom(req.clientUuid());
        if (representative == null) {
            throw new WebApplicationException("No imported phantom exists for this client",
                    Response.Status.CONFLICT);
        }
        String name = consultantNames(Set.of(req.consultantUuid()))
                .getOrDefault(req.consultantUuid(), req.consultantUuid());
        String internalUuid = invoiceService.createSettlementInternal(
                representative, issuer, debtor,
                List.of(new InvoiceService.SettlementLineInput(req.consultantUuid(), name, delta)),
                req.clientUuid(), req.workYear(), req.workMonth());
        if (!req.queue()) {
            // The QUEUED doc is durable at this point — on finalize failure still recompute and
            // return the uuid, or the retry hits the open-QUEUED 409 with no trace of why.
            try {
                orchestrator.finalizeAutomatically(internalUuid);
            } catch (Exception e) {
                log.errorf(e, "settleConsultantPeriod: finalize failed for internal=%s key=%s — "
                        + "document remains QUEUED", internalUuid, key.asString());
            }
        }

        assignmentService.recomputeForGroup(req.clientUuid(), req.consultantUuid(), req.workYear(), req.workMonth());
        log.infof("settleConsultantPeriod: created %s delta=%s key=%s consultant=%s by=%s queue=%s",
                internalUuid, delta, key.asString(), req.consultantUuid(), actor, req.queue());
        return List.of(internalUuid);
    }

    /**
     * Workbench undo for a settle that has not booked yet (Feature 3b): delete a QUEUED
     * settlement INTERNAL and reopen its delta. This is the only delete the workbench allows
     * because <strong>a QUEUED internal never created an e-conomic draft</strong> — the nightly
     * {@code QueuedInternalInvoiceProcessorBatchlet} is what calls
     * {@code InternalInvoiceOrchestrator#finalizeAutomatically} to create+book the draft, and it
     * runs only once the source is PAID. While the document is still QUEUED there is no remote
     * artefact to clean up, so a purely local delete is both safe and complete (same invariant the
     * batchlet documents when it deletes an emptied QUEUED row: "no e-conomics draft existed").
     *
     * <p>Guards (all 409 except not-found): the invoice must exist (404); be a settlement-stamped
     * INTERNAL (type INTERNAL + full settlement key) whose billing client is an ENABLED
     * {@code selfbilled_source} — this endpoint only handles workbench settlement docs; and be in
     * status QUEUED (re-checked under a pessimistic row lock to close the nightly-finalize race).
     * Behaviour (single tx): write an {@link AttributionAuditLog} BEFORE deleting (oldState =
     * settlement key + per-item consultant/hours/rate, newState = {"deleted":true}), delete the
     * invoice via the managed entity so cascade REMOVE takes the items, then {@code recomputeForGroup}
     * for the settlement group so the backing vouchers flip SETTLED→ASSIGNED and the delta reopens.
     */
    @Transactional
    public void deleteQueuedInternal(String invoiceUuid, String actor) {
        Invoice internal = Invoice.findById(invoiceUuid);
        if (internal == null) {
            throw new WebApplicationException("Internal not found", Response.Status.NOT_FOUND);
        }
        String clientUuid = internal.getSettlementBillingClientUuid();
        Integer year = internal.getSettlementYear();
        Integer month = internal.getSettlementMonth();
        boolean fullyStamped = internal.getType() == InvoiceType.INTERNAL
                && clientUuid != null && year != null && month != null;
        if (!fullyStamped || !isEnabledSelfBilledSource(clientUuid)) {
            throw new WebApplicationException(
                    "Not a workbench settlement internal — only settlement-stamped INTERNAL documents "
                            + "for an enabled self-billed source can be deleted here.",
                    Response.Status.CONFLICT);
        }
        if (internal.getStatus() != InvoiceStatus.QUEUED) {
            throw new WebApplicationException(
                    "Only QUEUED settlement internals can be deleted; this one is " + internal.getStatus(),
                    Response.Status.CONFLICT);
        }

        List<InvoiceItem> items = internal.getInvoiceitems() == null ? List.of() : internal.getInvoiceitems();
        String consultantUuid = items.stream()
                .map(i -> i.consultantuuid)
                .filter(c -> c != null && !c.isBlank())
                .findFirst().orElse(null);
        SettlementGroupKey key = new SettlementGroupKey(
                clientUuid, internal.getSettlementDebtorCompanyuuid(), year, month);

        // Close the nightly-finalize race: QueuedInternalInvoiceProcessorBatchlet runs its whole sweep in
        // one long @Transactional, so a concurrent finalize can commit CREATED while this tx held a stale
        // QUEUED read. Re-read under a row lock — this blocks on the finalizer's lock and sees its committed
        // status on wake — then re-check QUEUED so we never delete a row that just booked an e-conomic draft.
        em.refresh(internal, LockModeType.PESSIMISTIC_WRITE);
        if (internal.getStatus() != InvoiceStatus.QUEUED) {
            throw new WebApplicationException(
                    "Only QUEUED settlement internals can be deleted; this one is " + internal.getStatus(),
                    Response.Status.CONFLICT);
        }

        String firstItemUuid = items.isEmpty() ? invoiceUuid : items.get(0).uuid;
        new AttributionAuditLog(invoiceUuid, firstItemUuid, actor, "SELFBILLED_DELETE_QUEUED",
                deletedOldState(key, consultantUuid, items), "{\"deleted\":true}",
                "Workbench delete of queued settlement internal").persist();

        // Delete via the MANAGED entity so cascade REMOVE handles the eager-loaded items in the PC. A JPQL
        // bulk item delete here bypasses the PC, leaving the cascade to issue per-row deletes that affect 0
        // rows -> OptimisticLockException -> the whole tx (audit row included) rolls back. DB FK
        // fk_invoiceitems_invoice ON DELETE CASCADE (V173) is the backstop.
        internal.delete();

        // Reopen the delta: flip the backing self-billed vouchers SETTLED -> ASSIGNED for this group
        // (single consultant by construction; mirror of settleConsultantPeriod's recompute call).
        if (consultantUuid != null) {
            assignmentService.recomputeForGroup(clientUuid, consultantUuid, year, month);
        }
        log.infof("deleteQueuedInternal: deleted %s key=%s consultant=%s by=%s",
                invoiceUuid, key.asString(), consultantUuid, actor);
    }

    /** Old-state snapshot for the audit row: settlement key + per-item (consultant, hours, rate). */
    private static String deletedOldState(SettlementGroupKey key, String consultantUuid, List<InvoiceItem> items) {
        StringBuilder lines = new StringBuilder();
        for (InvoiceItem i : items) {
            if (lines.length() > 0) lines.append(',');
            lines.append("{\"consultant\":\"").append(i.consultantuuid)
                    .append("\",\"hours\":").append(i.hours)
                    .append(",\"rate\":").append(i.rate).append('}');
        }
        return "{\"settlementKey\":\"" + key.asString() + "\",\"consultant\":\""
                + consultantUuid + "\",\"items\":[" + lines + "]}";
    }

    /** True when the client is the billing client of an enabled selfbilled_source (workbench scope). */
    private boolean isEnabledSelfBilledSource(String clientUuid) {
        Object v = em.createNativeQuery(
                        "SELECT COUNT(*) FROM selfbilled_source WHERE client_uuid = :c AND enabled = 1")
                .setParameter("c", clientUuid).getSingleResult();
        return ((Number) v).intValue() > 0;
    }

    /**
     * Revalidation guard (Phase 1 review amendment): e-conomic may book new lines into an
     * already-assigned voucher (e.g. a reversing correction) — the assignment shares then
     * still target the OLD net. For every voucher backing this group, the Σ of ALL
     * assignment shares on the voucher's lines (across all consultants/periods) must still
     * ≈ the voucher's CURRENT net, else the human must re-confirm in the workbench.
     */
    private void assertVouchersUnchanged(String clientUuid, String consultantUuid, int workYear, int workMonth) {
        @SuppressWarnings("unchecked")
        List<Object[]> vouchers = em.createNativeQuery("""
                SELECT DISTINCT l.account_number, l.voucher_number
                FROM selfbilled_assignment a
                JOIN selfbilled_line l ON l.uuid = a.selfbilled_line_uuid
                WHERE l.client_uuid = :c AND a.consultant_uuid = :u
                  AND a.work_year = :y AND a.work_month = :m
                """).setParameter("c", clientUuid).setParameter("u", consultantUuid)
                .setParameter("y", workYear).setParameter("m", workMonth).getResultList();

        Map<String, BigDecimal> currentNet = new HashMap<>();
        Map<String, BigDecimal> assignedTotal = new HashMap<>();
        for (Object[] v : vouchers) {
            int account = ((Number) v[0]).intValue(), voucher = ((Number) v[1]).intValue();
            String voucherKey = account + ":" + voucher;
            List<SelfBilledLine> siblings = SelfBilledLine.findVoucherSiblings(account, voucher);
            BigDecimal net = BigDecimal.ZERO;
            List<String> lineUuids = new ArrayList<>(siblings.size());
            for (SelfBilledLine line : siblings) {
                net = net.add(line.amount == null ? BigDecimal.ZERO : line.amount);
                lineUuids.add(line.uuid);
            }
            BigDecimal shares = BigDecimal.ZERO;
            for (SelfBilledAssignment a : SelfBilledAssignment.findByLines(lineUuids)) {
                shares = shares.add(a.shareAmount == null ? BigDecimal.ZERO : a.shareAmount);
            }
            currentNet.put(voucherKey, net);
            assignedTotal.put(voucherKey, shares);
        }
        List<String> stale = staleVoucherNumbers(currentNet, assignedTotal);
        if (!stale.isEmpty()) {
            throw new WebApplicationException(
                    "Self-billed document(s) changed in e-conomic since assignment — re-confirm them in the "
                            + "workbench before settling. Stale voucher(s) (account:voucher): "
                            + String.join(", ", stale),
                    Response.Status.CONFLICT);
        }
    }

    /** Pure comparison: voucher keys whose current net differs from the assigned total by more than 1 kr. */
    static List<String> staleVoucherNumbers(Map<String, BigDecimal> currentNet,
                                            Map<String, BigDecimal> assignedTotal) {
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : currentNet.entrySet()) {
            BigDecimal assigned = assignedTotal.getOrDefault(e.getKey(), BigDecimal.ZERO);
            if (e.getValue().subtract(assigned).abs().compareTo(THRESHOLD) > 0) stale.add(e.getKey());
        }
        stale.sort(String::compareTo);
        return stale;
    }

    /** Representative phantom for invoice_ref_uuid (kept from the prior service — same semantics). */
    String representativePhantom(String billingClientUuid) {
        @SuppressWarnings("unchecked")
        List<String> ids = em.createNativeQuery("""
                SELECT uuid FROM invoices
                WHERE type='PHANTOM' AND status='CREATED' AND economics_entry_number IS NOT NULL
                  AND billing_client_uuid=:c ORDER BY uuid LIMIT 1
                """).setParameter("c", billingClientUuid).getResultList();
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String requireDebtor(String clientUuid) {
        String debtor = deltaQuery.debtorFor(clientUuid);
        if (debtor == null) {
            throw new WebApplicationException("Client is not a configured self-billed source",
                    Response.Status.BAD_REQUEST);
        }
        return debtor;
    }

    private Map<String, BigDecimal> workValues(String clientUuid, int year, int month) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (ConsultantWorkRevenue r : workService.findRevenueByClientAndMonth(clientUuid, year, month)) {
            out.put(r.useruuid(), r.revenue() == null ? BigDecimal.ZERO : r.revenue());
        }
        return out;
    }

    private Map<String, String> consultantNames(Set<String> ids) {
        Map<String, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT uuid, CONCAT(firstname,' ',lastname) FROM user WHERE uuid IN (:ids)")
                .setParameter("ids", ids).getResultList();
        for (Object[] r : rows) out.put((String) r[0], (String) r[1]);
        return out;
    }

    private Map<String, String> companyNames(Set<String> ids) {
        Map<String, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("SELECT uuid, name FROM companies WHERE uuid IN (:ids)")
                .setParameter("ids", ids).getResultList();
        for (Object[] r : rows) out.put((String) r[0], (String) r[1]);
        return out;
    }

    private static BigDecimal toBig(Object o) {
        if (o == null) return BigDecimal.ZERO;
        return (o instanceof BigDecimal b) ? b : BigDecimal.valueOf(((Number) o).doubleValue());
    }
}
