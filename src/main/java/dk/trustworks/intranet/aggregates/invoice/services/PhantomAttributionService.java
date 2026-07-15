package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItemAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.PhantomClientMap;
import dk.trustworks.intranet.aggregates.invoice.model.dto.PhantomAttributionReviewDTO;
import dk.trustworks.intranet.aggregates.invoice.model.dto.PhantomClientSuggestion;
import dk.trustworks.intranet.aggregates.invoice.model.enums.AttributionSource;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.model.enums.PhantomDerivationStatus;
import dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueDirtyMarker;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.PhantomClientMapRequest;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.work.ConsultantWorkRevenue;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * Derives per-consultant attribution for e-conomic-imported PHANTOM invoices
 * from registered work, persisting invoice_item_attributions (source=AUTO,
 * preserving MANUAL; items with SELFBILLED_ASSIGNMENT rows are skipped
 * entirely — AC10). The share math and scope predicate are pure static
 * methods (unit-tested with no DB); persistence mirrors
 * {@link InvoiceAttributionService#computeBaseItemAttribution}.
 */
@JBossLog
@ApplicationScoped
public class PhantomAttributionService {

    private static final int PCT_SCALE = 4;   // matches InvoiceAttributionService
    private static final int AMT_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Inject
    WorkService workService;

    /**
     * Self-injection so deriveAllInScope() invokes deriveForPhantom() / the
     * read through the CDI client proxy — required for @Transactional(REQUIRES_NEW)
     * and @Transactional to engage (a plain this.method() self-call bypasses the
     * Arc interceptor, leaving the nested transaction a no-op). This gives each
     * phantom its own transaction so one failure cannot roll back the whole batch.
     */
    @Inject
    PhantomAttributionService self;

    @Inject
    EntityManager em;

    @Inject
    PhantomClientResolver phantomClientResolver;

    @Inject
    PracticeRevenueDirtyMarker practiceRevenueDirtyMarker;

    /** Per-consultant work aggregate for a client+month. */
    public record WorkAgg(BigDecimal hours, BigDecimal revenue) {}

    /** A computed attribution row (pre-persist). */
    public record ShareRow(String consultantUuid, BigDecimal sharePct,
                           BigDecimal attributedAmount, BigDecimal originalHours) {}

    /** What the existing attribution rows of an item allow the estimator to do. */
    public enum ExistingAttributionState { EMPTY, AUTO_ONLY, MANUAL, SELFBILLED }

    /** SELFBILLED_ASSIGNMENT (human truth, §6.3) outranks MANUAL outranks AUTO. Pure. */
    static ExistingAttributionState classifyExisting(List<InvoiceItemAttribution> existing) {
        if (existing.stream().anyMatch(a -> a.source == AttributionSource.SELFBILLED_ASSIGNMENT)) {
            return ExistingAttributionState.SELFBILLED;
        }
        if (existing.stream().anyMatch(a -> a.source == AttributionSource.MANUAL)) {
            return ExistingAttributionState.MANUAL;
        }
        return existing.isEmpty() ? ExistingAttributionState.EMPTY : ExistingAttributionState.AUTO_ONLY;
    }

    /** In-scope = a CREATED PHANTOM, not skip-flagged, whose month is in [fyStart, fyEnd). */
    static boolean isInScope(Invoice inv, LocalDate fyStart, LocalDate fyEnd) {
        if (inv == null) return false;
        if (inv.getType() != InvoiceType.PHANTOM) return false;
        if (inv.getStatus() != InvoiceStatus.CREATED) return false;
        if (inv.internalInvoiceSkip) return false;
        // Guard a malformed period (e.g. an unset month=0 on a manually-created
        // phantom): an out-of-range month/year would make LocalDate.of throw, and
        // because isInScope runs inside listInScopeUuids()'s stream filter — OUTSIDE
        // deriveAllInScope()'s per-phantom try/catch — that would abort the whole batch.
        if (inv.getYear() < 1 || inv.getMonth() < 1 || inv.getMonth() > 12) return false;
        LocalDate period = LocalDate.of(inv.getYear(), inv.getMonth(), 1);
        return !period.isBefore(fyStart) && period.isBefore(fyEnd);
    }

    /**
     * Compute attribution rows for one phantom within its settlement group. The intercompany
     * transfer price for each consultant is their <b>own logged work value</b> (Σ hours×rate) — i.e.
     * what a normal client draft invoice would bill — NOT a share of the self-billed phantom revenue
     * (which carries the client margin/timing noise; distributing it was wrong by the phantom/work
     * ratio, ~2.5× over for Vattenfall, ~0.5× under for Energinet).
     *
     * <p>A settlement group has MANY phantoms (one per e-conomic entry) that all see the same
     * group-level work. So each phantom carries only its <b>share</b> of the work value, apportioned
     * by the phantom's own total: {@code amount = workValue × (phantomTotal ÷ |groupTotal|)}, where
     * {@code phantomTotal} is THIS phantom's signed total and {@code groupTotal} is the signed total
     * of ALL phantoms in the group. This makes each consultant's amounts SUM across the group's
     * phantoms to their work value (up to per-phantom 2-dp rounding — a residual bounded by
     * ~phantomCount/2 øre), instead of work value × phantomCount. Dividing by the ABSOLUTE group
     * total preserves credit-note direction: a
     * credit-note group (negative groupTotal) yields negative amounts, and a lone credit-note phantom
     * inside a positive group contributes a negative slice. The margin (group revenue − Σ work value)
     * is intentionally not attributed. {@code sharePct} is retained for display only. Pure.
     */
    public static List<ShareRow> computeShares(Map<String, WorkAgg> byConsultant,
                                               BigDecimal phantomTotal, BigDecimal groupTotal) {
        if (byConsultant == null || byConsultant.isEmpty() || phantomTotal == null || groupTotal == null) {
            return List.of();
        }
        List<String> consultants = new ArrayList<>(byConsultant.keySet());
        consultants.sort(Comparator.naturalOrder()); // deterministic; smallest uuid first

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalHours = BigDecimal.ZERO;
        for (String c : consultants) {
            WorkAgg w = byConsultant.get(c);
            totalRevenue = totalRevenue.add(nz(w.revenue()));
            totalHours = totalHours.add(nz(w.hours()));
        }

        boolean revenueBasis = totalRevenue.signum() > 0;
        BigDecimal totalWeight = revenueBasis ? totalRevenue : totalHours; // sharePct (display) basis
        if (totalWeight.signum() <= 0) {
            return List.of(); // no work at all (neither revenue nor hours)
        }

        // This phantom's slice of the group: signed phantomTotal over the ABSOLUTE group total. The
        // per-consultant amounts therefore sum across the group's phantoms to their work value, and a
        // credit-note (negative) total reverses the transfer. groupTotal==0 (net-zero group) -> 0.
        BigDecimal absGroup = groupTotal.abs();
        BigDecimal fraction = absGroup.signum() == 0
                ? BigDecimal.ZERO
                : phantomTotal.divide(absGroup, 10, RoundingMode.HALF_UP);
        List<ShareRow> rows = new ArrayList<>(consultants.size());
        for (String c : consultants) {
            WorkAgg w = byConsultant.get(c);
            BigDecimal weight = revenueBasis ? nz(w.revenue()) : nz(w.hours());
            BigDecimal sharePct = weight.multiply(HUNDRED).divide(totalWeight, PCT_SCALE, RoundingMode.HALF_UP);
            BigDecimal amount = nz(w.revenue()).multiply(fraction).setScale(AMT_SCALE, RoundingMode.HALF_UP);
            BigDecimal hours = nz(w.hours()).setScale(AMT_SCALE, RoundingMode.HALF_UP);
            rows.add(new ShareRow(c, sharePct, amount, hours));
        }
        return rows;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Derive (or re-derive) attribution for a single phantom in its own
     * transaction. Preserves MANUAL rows; replaces AUTO rows idempotently;
     * skips items with SELFBILLED_ASSIGNMENT rows entirely (AC10 — no recalc,
     * the mirrored amounts are the human truth).
     * Stamps invoices.billing_client_uuid with the resolved client.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public PhantomDerivationStatus deriveForPhantom(String invoiceUuid) {
        LocalDate fyStart=DateUtils.getCurrentFiscalStartDate();
        return deriveForPhantom(invoiceUuid,fyStart,fyStart.plusYears(1),false,true,false);
    }

    /** Strict recovery item derivation over recognition dates, without ordinary watermark writes. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public PhantomDerivationStatus deriveForPhantomInRange(
            String invoiceUuid,LocalDate fromInclusive,LocalDate toInclusive){
        return deriveForPhantom(invoiceUuid,fromInclusive,toInclusive,true,false,false);
    }

    /** Strict derivation for a UUID already selected by the immutable bounded dependency set. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public PhantomDerivationStatus deriveForPhantomRecoveryDependency(String invoiceUuid){
        return deriveForPhantom(invoiceUuid,LocalDate.MIN,LocalDate.MAX,true,false,true);
    }

    private PhantomDerivationStatus deriveForPhantom(
            String invoiceUuid,LocalDate fromInclusive,LocalDate toInclusive,
            boolean recognitionRange,boolean markDirty,boolean exactDependency){
        Invoice phantom = Invoice.findById(invoiceUuid);
        if (phantom == null) {
            log.warnf("deriveForPhantom: invoice not found uuid=%s", invoiceUuid);
            return PhantomDerivationStatus.OUT_OF_SCOPE;
        }
        boolean inScope=recognitionRange
                ?phantom.getType()==InvoiceType.PHANTOM&&phantom.getStatus()==InvoiceStatus.CREATED
                &&!phantom.internalInvoiceSkip&&phantom.invoicedate!=null
                &&(exactDependency||!phantom.invoicedate.isBefore(fromInclusive)
                                    &&!phantom.invoicedate.isAfter(toInclusive))
                &&phantom.getYear()>0&&phantom.getMonth()>0&&phantom.getMonth()<=12
                :isInScope(phantom,fromInclusive,toInclusive);
        if(!inScope){
            return PhantomDerivationStatus.OUT_OF_SCOPE;
        }

        List<InvoiceItem> items = phantom.getInvoiceitems();
        InvoiceItem item = (items == null) ? null : items.stream().findFirst().orElse(null);
        if (item == null) {
            log.warnf("deriveForPhantom: phantom has no invoiceitem uuid=%s", invoiceUuid);
            return PhantomDerivationStatus.NO_WORK;
        }
        if(recognitionRange&&items.size()!=1){
            return PhantomDerivationStatus.NO_WORK;
        }
        if (items.size() > 1) {
            // Auto-imported phantoms carry exactly one item; a multi-item phantom can
            // only come from the manual createPhantomInvoice path. Surface the unexpected
            // shape — attribution uses only the first item's total.
            log.warnf("deriveForPhantom: phantom uuid=%s has %d invoiceitems; attributing only the first",
                    invoiceUuid, items.size());
        }

        List<InvoiceItemAttribution> existing = InvoiceItemAttribution.list("invoiceitemUuid", item.uuid);
        switch (classifyExisting(existing)) {
            case SELFBILLED:
                // AC10: the rows mirror a human self-billed assignment — never replace,
                // never recalc (recalculateAmount would corrupt the assigned amounts).
                return PhantomDerivationStatus.SKIPPED_SELFBILLED;
            case MANUAL: {
                // Preserve MANUAL overrides (decision #7): recalc amount only, never replace.
                double itemTotal = item.hours * item.rate;
                for (InvoiceItemAttribution attr : existing) {
                    attr.recalculateAmount(itemTotal);
                    attr.updatedAt = LocalDateTime.now();
                }
                if(markDirty)markRevenueDirty(phantom);
                return PhantomDerivationStatus.SKIPPED_MANUAL;
            }
            case EMPTY, AUTO_ONLY:
                if(recognitionRange){
                    InvoiceItemAttribution.delete("invoiceitemUuid = ?1 AND source = ?2",
                            item.uuid,AttributionSource.AUTO);
                }
                break; // -> derive below
        }

        // Resolve client: prefer an already-set billing_client_uuid, else the confirmed map.
        String resolvedClientUuid = trimToNull(phantom.getBillingClientUuid());
        if (resolvedClientUuid == null) {
            PhantomClientMap map = PhantomClientMap.findById(phantom.getClientname());
            if (map != null && map.excluded) {
                return PhantomDerivationStatus.EXCLUDED;
            }
            if (map != null) {
                resolvedClientUuid = trimToNull(map.clientUuid);
            }
        }
        if (resolvedClientUuid == null) {
            return PhantomDerivationStatus.UNRESOLVED_CLIENT;
        }
        phantom.setBillingClientUuid(resolvedClientUuid);
        if(markDirty)markRevenueDirty(phantom);

        // Registered work for the resolved client + the phantom's month.
        List<ConsultantWorkRevenue> work =
                workService.findRevenueByClientAndMonth(resolvedClientUuid, phantom.getYear(), phantom.getMonth());
        if (work.isEmpty()) {
            return PhantomDerivationStatus.NO_WORK;
        }
        Map<String, WorkAgg> byConsultant = new LinkedHashMap<>();
        for (ConsultantWorkRevenue r : work) {
            byConsultant.put(r.useruuid(), new WorkAgg(r.hours(), r.revenue()));
        }

        BigDecimal phantomTotal = BigDecimal.valueOf(item.hours * item.rate)
                .setScale(AMT_SCALE, RoundingMode.HALF_UP);
        BigDecimal groupTotal = groupPhantomTotal(phantom, resolvedClientUuid);
        List<ShareRow> rows = computeShares(byConsultant, phantomTotal, groupTotal);
        if (rows.isEmpty()) {
            return PhantomDerivationStatus.NO_WORK;
        }
        // Rate=0 work has no monetary transfer-price basis -> every amount is 0. Surface it so an
        // accountant can add the missing rate; we never fabricate a price (work value is the basis).
        if (phantomTotal.signum() != 0 && rows.stream().allMatch(r -> r.attributedAmount().signum() == 0)) {
            log.warnf("deriveForPhantom: phantom=%s client=%s has logged hours but zero work value "
                    + "(missing rate) -> attribution is 0; manual rate review needed", invoiceUuid, resolvedClientUuid);
        }

        // Idempotent AUTO replace (mirror computeBaseItemAttribution — enum bound directly).
        InvoiceItemAttribution.delete("invoiceitemUuid = ?1 AND source = ?2", item.uuid, AttributionSource.AUTO);
        for (ShareRow row : rows) {
            new InvoiceItemAttribution(item.uuid, row.consultantUuid(), row.sharePct(),
                    row.attributedAmount(), row.originalHours(), AttributionSource.AUTO).persist();
        }
        log.infof("deriveForPhantom: attributed phantom=%s client=%s consultants=%d phantomTotal=%s groupTotal=%s",
                invoiceUuid, resolvedClientUuid, rows.size(), phantomTotal, groupTotal);
        return PhantomDerivationStatus.ATTRIBUTED;
    }

    /**
     * Rebuilds every recognized PHANTOM dependency in the closed interval. It never catches an
     * item failure, and re-reads the exact UUID set before returning so concurrent population drift
     * cannot be certified by the recovery owner.
     */
    public StrictRangeResult deriveRangeStrict(LocalDate fromInclusive,LocalDate toInclusive){
        if(fromInclusive==null||toInclusive==null||fromInclusive.isAfter(toInclusive)){
            throw new IllegalArgumentException("invalid PHANTOM recovery bounds");
        }
        List<String> expected=self.listRecognitionRangeUuids(fromInclusive,toInclusive);
        Map<PhantomDerivationStatus,Integer> counts=new EnumMap<>(PhantomDerivationStatus.class);
        Set<String> processed=new HashSet<>();
        for(String uuid:expected){
            if(!processed.add(uuid))throw new IllegalStateException("PHANTOM_DEPENDENCY_DUPLICATE");
            PhantomDerivationStatus status=self.deriveForPhantomRecoveryDependency(uuid);
            self.validateStrictOutcome(uuid,status);
            counts.merge(status,1,Integer::sum);
        }
        List<String> finalPopulation=self.listRecognitionRangeUuids(fromInclusive,toInclusive);
        if(!expected.equals(finalPopulation)||processed.size()!=expected.size()){
            throw new IllegalStateException("PHANTOM_DEPENDENCY_SET_ADVANCED");
        }
        return new StrictRangeResult(expected.size(),Map.copyOf(counts));
    }

    @Transactional
    public List<String> listRecognitionRangeUuids(LocalDate fromInclusive,LocalDate toInclusive){
        @SuppressWarnings("unchecked")
        List<String> rows=em.createNativeQuery("""
                SELECT DISTINCT i.uuid FROM invoices i
                WHERE i.type='PHANTOM' AND i.status='CREATED' AND i.internal_invoice_skip=FALSE
                  AND (i.invoicedate BETWEEN :fromDate AND :toDate
                       OR i.uuid IN (
                           SELECT m.source_document_uuid
                           FROM practice_basis_dependency_manifest_mat m
                           JOIN practice_operating_cost_publication o
                             ON o.publication_id=1
                            AND o.practice_basis_generation_id=m.generation_id
                           WHERE m.recognized_document_type='CREDIT_NOTE'
                             AND m.recognized_month BETWEEN :fromDate AND :toDate
                             AND m.source_document_uuid IS NOT NULL
                       ))
                ORDER BY i.uuid
                """).setParameter("fromDate",fromInclusive).setParameter("toDate",toInclusive)
                .getResultList();
        return List.copyOf(rows);
    }

    @Transactional
    public void validateStrictOutcome(String invoiceUuid,PhantomDerivationStatus status){
        @SuppressWarnings("unchecked")
        List<Object[]> rows=em.createNativeQuery("""
                SELECT a.source,COUNT(*),COUNT(DISTINCT a.consultant_uuid),
                       MIN(a.share_pct),MAX(a.share_pct),SUM(a.share_pct)
                FROM invoice_item_attributions a
                JOIN invoiceitems ii ON ii.uuid=a.invoiceitem_uuid
                WHERE ii.invoiceuuid=:invoiceUuid
                GROUP BY a.source
                """).setParameter("invoiceUuid",invoiceUuid).getResultList();
        Map<String,Object[]> bySource=new LinkedHashMap<>();
        for(Object[] row:rows)bySource.put(String.valueOf(row[0]),row);
        if(status==PhantomDerivationStatus.ATTRIBUTED){
            Object[] auto=bySource.get(AttributionSource.AUTO.name());
            if(auto==null||((Number)auto[1]).longValue()!=((Number)auto[2]).longValue()
                    ||decimal(auto[3]).signum()<0||decimal(auto[4]).compareTo(HUNDRED)>0
                    ||decimal(auto[5]).compareTo(new BigDecimal("99.9900"))<0
                    ||decimal(auto[5]).compareTo(new BigDecimal("100.0100"))>0){
                throw new IllegalStateException("PHANTOM_AUTO_VALIDATION_FAILED");
            }
        }else if(status==PhantomDerivationStatus.SKIPPED_MANUAL){
            if(!bySource.containsKey(AttributionSource.MANUAL.name()))
                throw new IllegalStateException("PHANTOM_MANUAL_VALIDATION_FAILED");
        }else if(status==PhantomDerivationStatus.SKIPPED_SELFBILLED){
            if(!bySource.containsKey(AttributionSource.SELFBILLED_ASSIGNMENT.name()))
                throw new IllegalStateException("PHANTOM_SELF_BILLED_VALIDATION_FAILED");
        }else if(bySource.containsKey(AttributionSource.AUTO.name())){
            throw new IllegalStateException("PHANTOM_STALE_AUTO_EVIDENCE");
        }
    }

    private static BigDecimal decimal(Object value){
        return value instanceof BigDecimal decimal?decimal:new BigDecimal(value.toString());
    }

    public record StrictRangeResult(int dependencyCount,
                                    Map<PhantomDerivationStatus,Integer> statusCounts){}

    /**
     * Signed Σ of phantom item totals over the settlement group — the denominator that apportions
     * each consultant's work value across the group's phantoms (see {@link #computeShares}). The
     * settlement engine groups its target by {@code billing_client_uuid}, so this denominator MUST
     * cover the same set: all in-scope (CREATED, economics-imported, not skip-flagged) phantoms
     * (company + period) whose import label ({@code clientname}) maps to {@code resolvedClientUuid},
     * OR that are already stamped to it. The status/scope filters match the settlement engine's exactly. Grouping by the
     * label-SET (not a single label) keeps R consistent with the settlement target even if two
     * e-conomic labels map to the same client — otherwise each label's slice would divide by its own
     * label's R while settlement sums both, re-introducing inflation one level up. Label-based (not
     * billing_client_uuid-based) so R is stable before sibling phantoms are stamped during derive.
     */
    BigDecimal groupPhantomTotal(Invoice phantom, String resolvedClientUuid) {
        String companyUuid = phantom.getCompany() != null ? phantom.getCompany().getUuid() : null;
        Object res = em.createNativeQuery("""
                SELECT COALESCE(SUM(ii.hours*ii.rate),0)
                FROM invoices p JOIN invoiceitems ii ON ii.invoiceuuid = p.uuid
                WHERE p.type='PHANTOM' AND p.status='CREATED'
                  AND p.economics_entry_number IS NOT NULL AND p.internal_invoice_skip = 0
                  AND p.companyuuid = :co AND p.year = :y AND p.month = :m
                  AND ( p.clientname = :myLabel
                        OR p.billing_client_uuid = :client
                        OR p.clientname IN (SELECT m.clientname FROM phantom_client_map m
                                            WHERE m.client_uuid = :client AND (m.excluded = 0 OR m.excluded IS NULL)) )
                """)
                .setParameter("co", companyUuid)
                .setParameter("y", phantom.getYear())
                .setParameter("m", phantom.getMonth())
                .setParameter("myLabel", phantom.getClientname())
                .setParameter("client", resolvedClientUuid)
                .getSingleResult();
        if (res == null) return BigDecimal.ZERO;
        return (res instanceof BigDecimal b) ? b : BigDecimal.valueOf(((Number) res).doubleValue());
    }

    /** Read the in-scope phantom uuids in a short transaction (called via self proxy). */
    @Transactional
    public List<String> listInScopeUuids() {
        LocalDate fyStart = DateUtils.getCurrentFiscalStartDate();
        LocalDate fyEnd = fyStart.plusYears(1);
        return Invoice.<Invoice>list("type = ?1 and status = ?2",
                        InvoiceType.PHANTOM, InvoiceStatus.CREATED)
                .stream()
                .filter(p -> isInScope(p, fyStart, fyEnd))
                .map(Invoice::getUuid)
                .toList();
    }

    /**
     * Derive attribution for every in-scope phantom. Per-phantom failures are
     * logged and counted but never abort the run (each derive is REQUIRES_NEW).
     * Returns a status histogram for logging.
     */
    public Map<PhantomDerivationStatus, Integer> deriveAllInScope() {
        List<String> uuids = self.listInScopeUuids();
        Map<PhantomDerivationStatus, Integer> counts = new EnumMap<>(PhantomDerivationStatus.class);
        for (String uuid : uuids) {
            try {
                counts.merge(self.deriveForPhantom(uuid), 1, Integer::sum);
            } catch (RuntimeException e) {
                log.errorf(e, "deriveForPhantom failed for phantom=%s", uuid);
            }
        }
        log.infof("deriveAllInScope: processed=%d result=%s", uuids.size(), counts);
        return counts;
    }

    /** Test/diagnostic passthrough to the work query. */
    List<ConsultantWorkRevenue> findWork(String clientUuid, int year, int month) {
        return workService.findRevenueByClientAndMonth(clientUuid, year, month);
    }

    /**
     * Build the review queue: in-scope phantoms with ZERO attribution rows,
     * grouped by clientname label, with the current mapping state and (when
     * unmapped) an auto-suggestion. Labels marked excluded are omitted.
     */
    @Transactional
    public List<PhantomAttributionReviewDTO> buildReviewQueue() {
        LocalDate fyStart = DateUtils.getCurrentFiscalStartDate();
        LocalDate fyEnd = fyStart.plusYears(1);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT i.clientname AS clientname, COUNT(*) AS cnt, SUM(t.total) AS total
                FROM invoices i
                JOIN (SELECT ii.invoiceuuid AS invoiceuuid, SUM(ABS(ii.hours * ii.rate)) AS total
                      FROM invoiceitems ii GROUP BY ii.invoiceuuid) t ON t.invoiceuuid = i.uuid
                WHERE i.type = 'PHANTOM' AND i.status = 'CREATED' AND i.internal_invoice_skip = 0
                  AND (MAKEDATE(i.year, 1) + INTERVAL (i.month - 1) MONTH) >= :fyStart
                  AND (MAKEDATE(i.year, 1) + INTERVAL (i.month - 1) MONTH) <  :fyEnd
                  AND NOT EXISTS (
                      SELECT 1 FROM invoice_item_attributions a
                      JOIN invoiceitems ii2 ON a.invoiceitem_uuid = ii2.uuid
                      WHERE ii2.invoiceuuid = i.uuid)
                GROUP BY i.clientname
                ORDER BY total DESC
                """, Tuple.class)
                .setParameter("fyStart", fyStart)
                .setParameter("fyEnd", fyEnd)
                .getResultList();

        List<PhantomAttributionReviewDTO> queue = new ArrayList<>();
        for (Tuple r : rows) {
            String clientname = r.get("clientname", String.class);
            long cnt = ((Number) r.get("cnt")).longValue();
            BigDecimal total = r.get("total") == null ? BigDecimal.ZERO : new BigDecimal(r.get("total").toString());

            PhantomClientMap map = PhantomClientMap.findById(clientname);
            if (map != null && map.excluded) {
                continue; // excluded -> not in the queue
            }
            String mappedClientUuid = map != null ? trimToNull(map.clientUuid) : null;
            String mappedClientName = null;
            PhantomClientSuggestion suggestion;
            if (mappedClientUuid != null) {
                Client c = Client.findById(mappedClientUuid);
                mappedClientName = c != null ? c.getName() : null;
                suggestion = PhantomClientSuggestion.none(); // mapped already (likely NO_WORK)
            } else {
                suggestion = phantomClientResolver.suggest(clientname);
            }
            queue.add(new PhantomAttributionReviewDTO(
                    clientname, cnt, total, mappedClientUuid, mappedClientName, false, suggestion));
        }
        return queue;
    }

    /** Upsert one label->client mapping in its own committed transaction. */
    @Transactional
    public void upsertClientMap(PhantomClientMapRequest req, String userUuid) {
        boolean excluded = req.excluded() != null && req.excluded();
        String clientUuid = excluded ? null : trimToNull(req.clientUuid());
        LocalDateTime now = LocalDateTime.now();

        PhantomClientMap map = PhantomClientMap.findById(req.clientname());
        if (map == null) {
            map = new PhantomClientMap(req.clientname(), clientUuid, excluded, req.note(), userUuid);
            map.persist();
        } else {
            map.clientUuid = clientUuid;
            map.excluded = excluded;
            map.note = req.note();
            map.confirmedBy = userUuid;
            map.confirmedAt = now;
            map.updatedAt = now;
        }
    }

    /**
     * Confirm/exclude a label, then re-derive every in-scope phantom with that
     * label. The mapping is committed first (via the self proxy) so the
     * REQUIRES_NEW derives see it. A freshly-confirmed mapping always wins: the
     * re-derive clears any stale AUTO state first (see {@link #rederiveLabel}),
     * so correcting a label to a different client actually re-points it. Returns
     * the re-derive status histogram.
     */
    public Map<PhantomDerivationStatus, Integer> upsertClientMapAndRederive(
            PhantomClientMapRequest req, String userUuid) {
        self.upsertClientMap(req, userUuid); // committed
        if (req.excluded() != null && req.excluded()) {
            self.resetAutoStateForLabel(req.clientname()); // excluded labels must not keep stale AUTO rows
            return new EnumMap<>(PhantomDerivationStatus.class); // excluded -> nothing to derive
        }
        return rederiveLabel(req.clientname());
    }

    /**
     * Re-derive all in-scope phantoms carrying {@code clientname}. Clears any
     * stale AUTO state (a previously-stamped billing_client_uuid + AUTO rows)
     * for the label's non-MANUAL phantoms FIRST, in its own committed
     * transaction (via the self proxy), so the REQUIRES_NEW derives fall through
     * to the just-confirmed mapping instead of short-circuiting on the old
     * stamp. This makes a confirmed mapping authoritative on re-derive (an admin
     * can correct a mis-mapped label). MANUAL-overridden phantoms are untouched
     * (they return SKIPPED_MANUAL). Single-derive + nightly derive-all keep their
     * stamp-preserving idempotency — only this label re-derive path resets.
     */
    public Map<PhantomDerivationStatus, Integer> rederiveLabel(String clientname) {
        self.resetAutoStateForLabel(clientname); // committed before the REQUIRES_NEW derives read
        List<String> uuids = self.listInScopeUuidsForLabel(clientname);
        Map<PhantomDerivationStatus, Integer> counts = new EnumMap<>(PhantomDerivationStatus.class);
        for (String uuid : uuids) {
            try {
                counts.merge(self.deriveForPhantom(uuid), 1, Integer::sum);
            } catch (RuntimeException e) {
                log.errorf(e, "rederiveLabel: derive failed for phantom=%s", uuid);
            }
        }
        log.infof("rederiveLabel '%s': %s", clientname, counts);
        return counts;
    }

    /**
     * Clear stale AUTO derivation state for a label's in-scope phantoms so a
     * re-derive honors a freshly-confirmed mapping: for each in-scope phantom
     * whose attribution is absent or AUTO-only, delete its AUTO attribution rows
     * and null its billing_client_uuid stamp. Phantoms carrying a MANUAL override
     * or SELFBILLED_ASSIGNMENT rows are left untouched (deriveForPhantom returns
     * SKIPPED_MANUAL / SKIPPED_SELFBILLED for them BEFORE the client-resolution
     * block, so their stamp would never be restored — it must be preserved here).
     * Committed in its own transaction (called via the self proxy) so the
     * subsequent REQUIRES_NEW derives read the cleared state.
     */
    @Transactional
    public void resetAutoStateForLabel(String clientname) {
        LocalDate fyStart = DateUtils.getCurrentFiscalStartDate();
        LocalDate fyEnd = fyStart.plusYears(1);
        List<Invoice> phantoms = Invoice.list("type = ?1 and status = ?2 and clientname = ?3",
                InvoiceType.PHANTOM, InvoiceStatus.CREATED, clientname);
        for (Invoice phantom : phantoms) {
            if (!isInScope(phantom, fyStart, fyEnd)) continue;
            List<InvoiceItem> items = phantom.getInvoiceitems();
            InvoiceItem item = (items == null) ? null : items.stream().findFirst().orElse(null);
            if (item == null) continue;
            List<InvoiceItemAttribution> existing =
                    InvoiceItemAttribution.list("invoiceitemUuid", item.uuid);
            switch (classifyExisting(existing)) {
                case MANUAL, SELFBILLED:
                    continue; // skip statuses keep their stamp (never re-resolved on derive)
                case EMPTY, AUTO_ONLY:
                    break; // safe to reset below
            }
            InvoiceItemAttribution.delete("invoiceitemUuid = ?1 and source = ?2",
                    item.uuid, AttributionSource.AUTO);
            phantom.setBillingClientUuid(null); // managed entity -> flushed on commit
        }
    }

    /** In-scope phantom uuids for one label (read via self proxy for a session). */
    @Transactional
    public List<String> listInScopeUuidsForLabel(String clientname) {
        LocalDate fyStart = DateUtils.getCurrentFiscalStartDate();
        LocalDate fyEnd = fyStart.plusYears(1);
        return Invoice.<Invoice>list("type = ?1 and status = ?2 and clientname = ?3",
                        InvoiceType.PHANTOM, InvoiceStatus.CREATED, clientname)
                .stream()
                .filter(p -> isInScope(p, fyStart, fyEnd))
                .map(Invoice::getUuid)
                .toList();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private void markRevenueDirty(Invoice phantom) {
        // Kept null-tolerant for existing direct-construction unit tests; CDI always injects it.
        if (practiceRevenueDirtyMarker != null) {
            practiceRevenueDirtyMarker.mark(PracticeRevenueDirtyMarker.Source.PHANTOM_ATTRIBUTION,
                    YearMonth.of(phantom.getYear(), phantom.getMonth()));
        }
    }
}
