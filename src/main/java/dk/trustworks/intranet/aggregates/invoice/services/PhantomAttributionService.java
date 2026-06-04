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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives per-consultant attribution for e-conomic-imported PHANTOM invoices
 * from registered work, persisting invoice_item_attributions (source=AUTO,
 * preserving MANUAL). The share math and scope predicate are pure static
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

    /** Per-consultant work aggregate for a client+month. */
    public record WorkAgg(BigDecimal hours, BigDecimal revenue) {}

    /** A computed attribution row (pre-persist). */
    public record ShareRow(String consultantUuid, BigDecimal sharePct,
                           BigDecimal attributedAmount, BigDecimal originalHours) {}

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
     * Compute attribution shares for one phantom. Basis = registered revenue
     * (Σ hours×rate); falls back to hours when total revenue is 0. Shares are
     * rounded to PCT_SCALE; amounts to AMT_SCALE; the rounding residual is
     * absorbed into the largest-share consultant (ties → smallest consultant
     * uuid) so amounts sum EXACTLY to {@code phantomTotal}. Pure.
     */
    public static List<ShareRow> computeShares(Map<String, WorkAgg> byConsultant, BigDecimal phantomTotal) {
        if (byConsultant == null || byConsultant.isEmpty() || phantomTotal == null) {
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
        BigDecimal totalWeight = revenueBasis ? totalRevenue : totalHours;
        if (totalWeight.signum() <= 0) {
            return List.of();
        }

        BigDecimal total = phantomTotal.setScale(AMT_SCALE, RoundingMode.HALF_UP);
        List<ShareRow> rows = new ArrayList<>(consultants.size());
        BigDecimal sumAmount = BigDecimal.ZERO;
        for (String c : consultants) {
            WorkAgg w = byConsultant.get(c);
            BigDecimal weight = revenueBasis ? nz(w.revenue()) : nz(w.hours());
            BigDecimal sharePct = weight.multiply(HUNDRED).divide(totalWeight, PCT_SCALE, RoundingMode.HALF_UP);
            BigDecimal amount = sharePct.divide(HUNDRED, 10, RoundingMode.HALF_UP)
                    .multiply(total).setScale(AMT_SCALE, RoundingMode.HALF_UP);
            BigDecimal hours = nz(w.hours()).setScale(AMT_SCALE, RoundingMode.HALF_UP);
            rows.add(new ShareRow(c, sharePct, amount, hours));
            sumAmount = sumAmount.add(amount);
        }

        BigDecimal residual = total.subtract(sumAmount);
        if (residual.signum() != 0) {
            int idx = pickAbsorbingIndex(rows);
            ShareRow r = rows.get(idx);
            rows.set(idx, new ShareRow(r.consultantUuid(), r.sharePct(),
                    r.attributedAmount().add(residual), r.originalHours()));
        }
        return rows;
    }

    /** Largest sharePct; ties resolved to the smallest consultant uuid (rows are uuid-sorted). */
    private static int pickAbsorbingIndex(List<ShareRow> rows) {
        int best = 0;
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).sharePct().compareTo(rows.get(best).sharePct()) > 0) {
                best = i;
            }
        }
        return best;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Derive (or re-derive) attribution for a single phantom in its own
     * transaction. Preserves MANUAL rows; replaces AUTO rows idempotently.
     * Stamps invoices.billing_client_uuid with the resolved client.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public PhantomDerivationStatus deriveForPhantom(String invoiceUuid) {
        Invoice phantom = Invoice.findById(invoiceUuid);
        if (phantom == null) {
            log.warnf("deriveForPhantom: invoice not found uuid=%s", invoiceUuid);
            return PhantomDerivationStatus.OUT_OF_SCOPE;
        }
        LocalDate fyStart = DateUtils.getCurrentFiscalStartDate();
        LocalDate fyEnd = fyStart.plusYears(1);
        if (!isInScope(phantom, fyStart, fyEnd)) {
            return PhantomDerivationStatus.OUT_OF_SCOPE;
        }

        List<InvoiceItem> items = phantom.getInvoiceitems();
        InvoiceItem item = (items == null) ? null : items.stream().findFirst().orElse(null);
        if (item == null) {
            log.warnf("deriveForPhantom: phantom has no invoiceitem uuid=%s", invoiceUuid);
            return PhantomDerivationStatus.NO_WORK;
        }
        if (items.size() > 1) {
            // Auto-imported phantoms carry exactly one item; a multi-item phantom can
            // only come from the manual createPhantomInvoice path. Surface the unexpected
            // shape — attribution uses only the first item's total.
            log.warnf("deriveForPhantom: phantom uuid=%s has %d invoiceitems; attributing only the first",
                    invoiceUuid, items.size());
        }

        // Preserve MANUAL overrides (decision #7): recalc amount only, never replace.
        List<InvoiceItemAttribution> existing = InvoiceItemAttribution.list("invoiceitemUuid", item.uuid);
        boolean hasManual = existing.stream().anyMatch(a -> a.source == AttributionSource.MANUAL);
        if (hasManual) {
            double itemTotal = Math.abs(item.hours * item.rate);
            for (InvoiceItemAttribution attr : existing) {
                attr.recalculateAmount(itemTotal);
                attr.updatedAt = LocalDateTime.now();
            }
            return PhantomDerivationStatus.SKIPPED_MANUAL;
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

        BigDecimal phantomTotal = BigDecimal.valueOf(Math.abs(item.hours * item.rate))
                .setScale(AMT_SCALE, RoundingMode.HALF_UP);
        List<ShareRow> rows = computeShares(byConsultant, phantomTotal);
        if (rows.isEmpty()) {
            return PhantomDerivationStatus.NO_WORK;
        }

        // Idempotent AUTO replace (mirror computeBaseItemAttribution — enum bound directly).
        InvoiceItemAttribution.delete("invoiceitemUuid = ?1 AND source = ?2", item.uuid, AttributionSource.AUTO);
        for (ShareRow row : rows) {
            new InvoiceItemAttribution(item.uuid, row.consultantUuid(), row.sharePct(),
                    row.attributedAmount(), row.originalHours(), AttributionSource.AUTO).persist();
        }
        log.infof("deriveForPhantom: attributed phantom=%s client=%s consultants=%d total=%s",
                invoiceUuid, resolvedClientUuid, rows.size(), phantomTotal);
        return PhantomDerivationStatus.ATTRIBUTED;
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
     * without a MANUAL attribution, delete its AUTO attribution rows and null
     * its billing_client_uuid stamp. Phantoms carrying a MANUAL override are
     * left untouched (deriveForPhantom returns SKIPPED_MANUAL for them, so their
     * stamp must be preserved). Committed in its own transaction (called via the
     * self proxy) so the subsequent REQUIRES_NEW derives read the cleared state.
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
            long manual = InvoiceItemAttribution.count("invoiceitemUuid = ?1 and source = ?2",
                    item.uuid, AttributionSource.MANUAL);
            if (manual > 0) continue; // preserve MANUAL overrides
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
}
