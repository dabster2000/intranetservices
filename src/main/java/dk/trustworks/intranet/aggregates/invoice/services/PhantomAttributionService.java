package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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
}
