package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItemAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.PhantomClientMap;
import dk.trustworks.intranet.aggregates.invoice.model.enums.AttributionSource;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.model.enums.PhantomDerivationStatus;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.work.ConsultantWorkRevenue;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
     * interceptor). Mirrors the per-voucher isolation in EconomicRevenueImportService.
     */
    @Inject
    PhantomAttributionService self;

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

        InvoiceItem item = phantom.getInvoiceitems() == null
                ? null
                : phantom.getInvoiceitems().stream().findFirst().orElse(null);
        if (item == null) {
            log.warnf("deriveForPhantom: phantom has no invoiceitem uuid=%s", invoiceUuid);
            return PhantomDerivationStatus.NO_WORK;
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

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
