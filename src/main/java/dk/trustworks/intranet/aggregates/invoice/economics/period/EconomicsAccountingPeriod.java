package dk.trustworks.intranet.aggregates.invoice.economics.period;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * One accounting period of one accounting year, as returned by the e-conomic AccountingYears API
 * ({@code GET /accountingYears/periods/paged}).
 *
 * <p>{@code isClosed} and {@code isBarred} are two DIFFERENT things and both block a booking. In
 * e-conomic's own UI they are two separate columns — "Status" (Åben / Lukket) and "Spærret" — and
 * a period can be Åben with Spærret ticked, which is precisely the state that rejected five
 * internal invoices on 2026-08-27 with {@code E04870}.
 *
 * <p>That distinction is why this API is used at all. The legacy REST API
 * ({@code restapi.e-conomic.com/accounting-years/{year}/periods}, already wired up in
 * {@code ExpenseSyncBatchlet}) exposes only a single {@code closed} flag, which corresponds to
 * "Status". Against the 2026-08-27 data it would have answered "open" for every barred period and
 * caught nothing.
 *
 * <p>{@code dateFrom} / {@code dateTo} are inclusive bounds. The vendor documents them as
 * date-times, so {@link EconomicsAccountingPeriod#parseVendorDate} tolerates both shapes.
 */
@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EconomicsAccountingPeriod {

    private Integer periodNumber;
    /** Accounting-year label, e.g. {@code "2025/2026"}. Only used for diagnostics. */
    private String year;
    private String dateFrom;
    private String dateTo;
    /** "Status: Lukket" — the year or period has been closed off. */
    private Boolean isClosed;
    /** "Spærret" — the period is locked against further postings while still nominally open. */
    private Boolean isBarred;

    /** True when {@code date} falls inside this period's inclusive bounds. */
    public boolean covers(LocalDate date) {
        LocalDate from = parseVendorDate(dateFrom);
        LocalDate to = parseVendorDate(dateTo);
        if (from == null || to == null) return false;
        return !date.isBefore(from) && !date.isAfter(to);
    }

    /** True when e-conomic will refuse a posting dated inside this period, for either reason. */
    public boolean blocksPosting() {
        return Boolean.TRUE.equals(isClosed) || Boolean.TRUE.equals(isBarred);
    }

    /** Which of the two flags is set, for a message that tells the operator what to actually fix. */
    public String blockReason() {
        if (Boolean.TRUE.equals(isBarred) && Boolean.TRUE.equals(isClosed)) return "closed and barred";
        if (Boolean.TRUE.equals(isBarred)) return "barred";
        if (Boolean.TRUE.equals(isClosed)) return "closed";
        return "open";
    }

    /**
     * Lenient date parse. The vendor schema documents {@code dateFrom}/{@code dateTo} as
     * date-times ({@code 2019-08-24T14:15:22Z}) but real agreements return plain dates, and this
     * runs on a path that must never fail a booking over a formatting surprise — an unparseable
     * bound simply makes the period non-matching, and the check falls open.
     */
    static LocalDate parseVendorDate(String raw) {
        if (raw == null || raw.length() < 10) return null;
        try {
            return LocalDate.parse(raw.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }
}
