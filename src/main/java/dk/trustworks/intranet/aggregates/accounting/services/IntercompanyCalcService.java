package dk.trustworks.intranet.aggregates.accounting.services;

import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.financeservice.model.AccountLumpSum;
import dk.trustworks.intranet.financeservice.model.AccountingAccount;
import dk.trustworks.intranet.financeservice.model.AccountingCategory;
import dk.trustworks.intranet.financeservice.model.FinanceDetails;
import dk.trustworks.intranet.model.Company;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
@JBossLog
public class IntercompanyCalcService {

    @Inject EntityManager em;
    @Inject AvailabilityService availabilityService;

    public static final int SCALE = 2;
    public static final int RATIO_SCALE = 10;
    public static final RoundingMode RM = RoundingMode.HALF_EVEN;

    /** Konfiguration der gør det muligt at køre enten “Invoice-regler” eller “Distribution (legacy)”. */
    public enum Mode {
        INVOICE_V1,            // Brug præcis samme filter/regelsæt som createInternalServiceInvoiceDraft
        DISTRIBUTION_LEGACY    // Bevar præcis nuværende opførsel i distributeMonthlyExpenses
    }

    /** Samlet, genbrugsvenlig måneds-context. */
    public static final class MonthData {
        public final LocalDate monthFrom;          // inkl.
        public final LocalDate monthTo;            // ekskl.
        public final List<Company> companies;
        public final Map<String, Company> byUuid;
        public final List<AccountingCategory> categories;
        public final List<EmployeeAvailabilityPerMonth> availability;

        /** GL aggregeret pr. (companyUuid -> accountCode) for [from, to) */
        public final Map<String, Map<Integer, BigDecimal>> glByCompanyAccountRange;
        /** GL aggregeret pr. (companyUuid -> accountCode) for PRÆCIS dag == monthFrom (bruges af Invoice) */
        public final Map<String, Map<Integer, BigDecimal>> glByCompanyAccountExactDay;

        /** Consultant counts (bruges til fordelingsnøgler) */
        public final Map<String, BigDecimal> consultantCount;
        public final BigDecimal totalConsultants;
        public final Map<String, BigDecimal> ratioByCompany;

        /** STAFF-base fra BI (allerede multipliceret med 1.02 og afrundet til 2) — brugt i Distribution. */
        public final Map<String, BigDecimal> staffBaseBI102;

        public MonthData(LocalDate from, LocalDate to,
                         List<Company> companies,
                         List<AccountingCategory> categories,
                         List<EmployeeAvailabilityPerMonth> availability,
                         Map<String, Map<Integer, BigDecimal>> glRange,
                         Map<String, Map<Integer, BigDecimal>> glExact,
                         Map<String, BigDecimal> consultantCount,
                         Map<String, BigDecimal> ratioByCompany,
                         Map<String, BigDecimal> staffBaseBI102) {

            this.monthFrom = from;
            this.monthTo = to;
            this.companies = companies;
            this.byUuid = companies.stream().collect(Collectors.toMap(Company::getUuid, c -> c));
            this.categories = categories;
            this.availability = availability;
            this.glByCompanyAccountRange = glRange;
            this.glByCompanyAccountExactDay = glExact;
            this.consultantCount = consultantCount;
            this.totalConsultants = consultantCount.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            this.ratioByCompany = ratioByCompany;
            this.staffBaseBI102 = staffBaseBI102;
        }
    }

    // ---------- Public API ----------

    /** Indlæs alt data for en måned i én omgang (bruges af begge kaldere). */
    public MonthData loadMonthData(LocalDate from, LocalDate to, double salaryBufferMultiplier) {
        final List<Company> companies = Company.listAll();
        final List<AccountingCategory> categories =
                AccountingCategory.listAll(Sort.by("accountCode", Sort.Direction.Ascending));

        final List<EmployeeAvailabilityPerMonth> availability =
                availabilityService.getAllEmployeeAvailabilityByPeriod(from, to);

        // Consultant counts & ratios (på månedens 1.)
        final Map<String, BigDecimal> cCount = new HashMap<>();
        companies.forEach(c -> {
            double cnt = availabilityService.calculateConsultantCount(c, from, availability);
            cCount.put(c.getUuid(), BigDecimal.valueOf(cnt));
        });
        final BigDecimal total = cCount.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        final Map<String, BigDecimal> ratios = new HashMap<>();
        if (total.compareTo(BigDecimal.ZERO) > 0) {
            companies.forEach(c ->
                    ratios.put(c.getUuid(), cCount.get(c.getUuid()).divide(total, RATIO_SCALE, RM)));
        } else {
            companies.forEach(c -> ratios.put(c.getUuid(), BigDecimal.ZERO));
        }

        // GL range [from, to)
        final Map<String, Map<Integer, BigDecimal>> glRange = aggregateGL(from, to);

        // GL præcis dag == from (eksakt lighed — bruges af Invoice)
        final Map<String, Map<Integer, BigDecimal>> glExact = aggregateGLExactDay(from);

        // STAFF-base (BI-afledt) * multiplier (1.02) — afrundet til 2
        final Map<String, BigDecimal> staffBase = staffBaseFromBI(from.getYear(), from.getMonthValue(), salaryBufferMultiplier);

        return new MonthData(from, to, companies, categories, availability, glRange, glExact, cCount, ratios, staffBase);
    }

    /** Lumps over månedens interval [from, to). Negative clamps til 0 (bruges i Distribution). */
    public Map<String, BigDecimal> lumpsMonthRange(LocalDate from, LocalDate to) {
        final List<AccountLumpSum> lumps = AccountLumpSum.list("registeredDate >= ?1 and registeredDate < ?2", from, to);
        final Map<String, BigDecimal> out = new HashMap<>();
        for (AccountLumpSum ls : lumps) {
            BigDecimal amt = BigDecimal.valueOf(ls.getAmount());
            // clamp negative
            if (amt.compareTo(BigDecimal.ZERO) < 0) amt = BigDecimal.ZERO;
            out.merge(ls.getAccountingAccount().getUuid(), amt.setScale(SCALE, RM), BigDecimal::add);
        }
        return out;
    }

    /** Lumps KUN på præcis monthFrom (første dag). Ingen clamp (bruges i Invoice for uændret adfærd). */
    public Map<String, BigDecimal> lumpsFirstOfMonth(LocalDate monthFrom) {
        final List<AccountLumpSum> lumps =
                AccountLumpSum.list("registeredDate = ?1", monthFrom.withDayOfMonth(1));
        final Map<String, BigDecimal> out = new HashMap<>();
        for (AccountLumpSum ls : lumps) {
            BigDecimal amt = BigDecimal.valueOf(ls.getAmount()); // kan være negativ (skal bevares)
            out.merge(ls.getAccountingAccount().getUuid(), amt, BigDecimal::add);
        }
        return out;
    }

    // ---------- Hjælpere til konkrete strategier ----------

    /** Fordeling pr. konto/kategori for Distribution (legacy). Returnerer baseToShare og originRemainder. */
    public static final class ShareAmounts {
        public BigDecimal baseToShare = BigDecimal.ZERO;   // den del der deles ift. ratio
        public BigDecimal originRemainder = BigDecimal.ZERO; // rest, der bliver hos origin
    }

    public ShareAmounts computeDistributionLegacyShareForAccount(
            MonthData md,
            AccountingAccount aaSrc,
            String originUuid,
            BigDecimal glAmount,                // GL for [from,to) pr. konto
            BigDecimal lumpsAmount,             // lumps (clamped >=0)
            Map<String, BigDecimal> staffRemainingByCompany // MUTERES når salary deler!
    ) {
        final boolean isShared = aaSrc.isShared();
        final boolean isSalary = aaSrc.isSalary();

        // GL clamp < 0 -> 0
        if (glAmount.compareTo(BigDecimal.ZERO) < 0) glAmount = BigDecimal.ZERO;
        glAmount = glAmount.setScale(SCALE, RM);

        // lumps deles ikke
        BigDecimal shareCandidate = glAmount.subtract(lumpsAmount);
        if (shareCandidate.compareTo(BigDecimal.ZERO) < 0) shareCandidate = BigDecimal.ZERO;

        BigDecimal baseToShare = BigDecimal.ZERO;
        if (md.totalConsultants.compareTo(BigDecimal.ZERO) > 0) {
            if (isSalary) {
                BigDecimal rem = staffRemainingByCompany.getOrDefault(originUuid, BigDecimal.ZERO);
                if (rem.compareTo(BigDecimal.ZERO) > 0) {
                    baseToShare = shareCandidate.min(rem);
                    staffRemainingByCompany.put(originUuid, rem.subtract(baseToShare));
                }
            } else if (isShared) { // non-salary deles kun hvis shared
                baseToShare = shareCandidate;
            }
        }
        baseToShare = baseToShare.setScale(SCALE, RM);

        ShareAmounts out = new ShareAmounts();
        out.baseToShare = baseToShare;
        out.originRemainder = glAmount.subtract(baseToShare).setScale(SCALE, RM);
        return out;
    }

    /** Sum GL på eksakt dag == monthFrom (bruges af Invoice-varianten). */
    public BigDecimal glExact(Company company, int accountCode, MonthData md) {
        return md.glByCompanyAccountExactDay
                .getOrDefault(company.getUuid(), Collections.emptyMap())
                .getOrDefault(accountCode, BigDecimal.ZERO);
    }

    // ---------- Private loaders ----------

    private Map<String, Map<Integer, BigDecimal>> aggregateGL(LocalDate from, LocalDate to) {
        final List<FinanceDetails> finance =
                FinanceDetails.list("expensedate >= ?1 and expensedate < ?2", from, to);

        final Map<String, Map<Integer, BigDecimal>> out = new HashMap<>();
        for (FinanceDetails fd : finance) {
            String cu = fd.getCompany().getUuid();
            int acc = fd.getAccountnumber();
            BigDecimal amt = BigDecimal.valueOf(fd.getAmount());
            out.computeIfAbsent(cu, k -> new HashMap<>())
                    .merge(acc, amt, BigDecimal::add);
        }
        return out;
    }

    private Map<String, Map<Integer, BigDecimal>> aggregateGLExactDay(LocalDate day) {
        final List<FinanceDetails> finance =
                FinanceDetails.list("expensedate = ?1", day);

        final Map<String, Map<Integer, BigDecimal>> out = new HashMap<>();
        for (FinanceDetails fd : finance) {
            String cu = fd.getCompany().getUuid();
            int acc = fd.getAccountnumber();
            BigDecimal amt = BigDecimal.valueOf(fd.getAmount());
            out.computeIfAbsent(cu, k -> new HashMap<>())
                    .merge(acc, amt, BigDecimal::add);
        }
        return out;
    }

    private Map<String, BigDecimal> staffBaseFromBI(int year, int month, double salaryBufferMultiplier) {
        String sql = """
        SELECT t.companyuuid, SUM(t.weighted_avg) AS staff_month_avg
        FROM (
            SELECT b.companyuuid,
                   a.useruuid,
                   a.avg_salary * (CAST(b.days_in_company AS DECIMAL(18,6)) / CAST(c.days_total AS DECIMAL(18,6))) AS weighted_avg
            FROM (
                SELECT useruuid, AVG(COALESCE(salary,0)) AS avg_salary
                FROM bi_data_per_day
                WHERE year = :year AND month = :month
                  AND consultant_type = 'STAFF'
                GROUP BY useruuid
            ) a
            JOIN (
                SELECT useruuid, companyuuid, COUNT(DISTINCT day) AS days_in_company
                FROM bi_data_per_day
                WHERE year = :year AND month = :month
                  AND consultant_type = 'STAFF'
                  AND companyuuid IS NOT NULL
                GROUP BY useruuid, companyuuid
            ) b ON a.useruuid = b.useruuid
            JOIN (
                SELECT useruuid, COUNT(DISTINCT day) AS days_total
                FROM bi_data_per_day
                WHERE year = :year AND month = :month
                  AND consultant_type = 'STAFF'
                GROUP BY useruuid
            ) c ON a.useruuid = c.useruuid AND c.days_total > 0
        ) t
        GROUP BY t.companyuuid
        """;
        Query q = em.createNativeQuery(sql);
        q.setParameter("year", year);
        q.setParameter("month", month);

        Map<String, BigDecimal> map = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        for (Object[] r : rows) {
            String companyuuid = (String) r[0];
            BigDecimal avgSum = new BigDecimal(String.valueOf(r[1]));
            map.put(companyuuid, avgSum);
        }

        // Sikr nøgler for alle companies og påfør pensionsfaktor
        Company.<Company>listAll().forEach(c ->
                map.putIfAbsent(c.getUuid(), BigDecimal.ZERO));
        final BigDecimal PENSION_MULT = BigDecimal.valueOf(salaryBufferMultiplier);
        map.replaceAll((k, v) -> v.multiply(PENSION_MULT).setScale(SCALE, RM));
        return map;
    }

    // Add near MonthData class
    public static final class FiscalYearData {
        public final List<Company> companies;
        public final List<AccountingCategory> categories;
        public final Map<java.time.YearMonth, MonthData> perMonth; // month -> MonthData (prebuilt)
        public final Map<java.time.YearMonth, Map<String, BigDecimal>> lumpsByMonth; // month -> (accountUuid -> amount)

        public FiscalYearData(List<Company> companies,
                              List<AccountingCategory> categories,
                              Map<java.time.YearMonth, MonthData> perMonth,
                              Map<java.time.YearMonth, Map<String, BigDecimal>> lumpsByMonth) {
            this.companies = companies;
            this.categories = categories;
            this.perMonth = perMonth;
            this.lumpsByMonth = lumpsByMonth;
        }
    }

    // New batch loader
    public FiscalYearData loadFiscalYear(LocalDate from, LocalDate to, double salaryBufferMultiplier) {
        // Static data
        final List<Company> companies = Company.listAll();
        final List<AccountingCategory> categories =
                AccountingCategory.listAll(Sort.by("accountCode", Sort.Direction.Ascending));

        // Months in the period
        final java.time.YearMonth startYm = java.time.YearMonth.from(from);
        final java.time.YearMonth endYmExcl = java.time.YearMonth.from(to);
        final List<java.time.YearMonth> months = new ArrayList<>();
        for (java.time.YearMonth ym = startYm; ym.isBefore(endYmExcl); ym = ym.plusMonths(1)) {
            months.add(ym);
        }

        // One availability fetch for the whole FY
        final List<EmployeeAvailabilityPerMonth> availabilityAll =
                availabilityService.getAllEmployeeAvailabilityByPeriod(from, to);

        // -------- GL for entire FY, then group per month ----------
        @SuppressWarnings("unchecked")
        final List<FinanceDetails> financeFull =
                FinanceDetails.list("expensedate >= ?1 and expensedate < ?2", from, to);

        final Map<java.time.YearMonth, Map<String, Map<Integer, BigDecimal>>> glRangeByMonth = new HashMap<>();
        final Map<java.time.YearMonth, Map<String, Map<Integer, BigDecimal>>> glExactByMonth = new HashMap<>();

        for (FinanceDetails fd : financeFull) {
            final java.time.YearMonth ym = java.time.YearMonth.from(fd.getExpensedate());
            final String cu = fd.getCompany().getUuid();
            final int acc = fd.getAccountnumber();
            final BigDecimal amt = BigDecimal.valueOf(fd.getAmount());

            // range [month start, next month)
            glRangeByMonth
                    .computeIfAbsent(ym, k -> new HashMap<>())
                    .computeIfAbsent(cu, k -> new HashMap<>())
                    .merge(acc, amt, BigDecimal::add);

            // exact day == first of month
            if (fd.getExpensedate().getDayOfMonth() == 1) {
                glExactByMonth
                        .computeIfAbsent(ym, k -> new HashMap<>())
                        .computeIfAbsent(cu, k -> new HashMap<>())
                        .merge(acc, amt, BigDecimal::add);
            }
        }

        // -------- Lumps for entire FY, then group per month (clamp negatives like Distribution) ----------
        @SuppressWarnings("unchecked")
        final List<AccountLumpSum> lumpsFull =
                AccountLumpSum.list("registeredDate >= ?1 and registeredDate < ?2", from, to);

        final Map<java.time.YearMonth, Map<String, BigDecimal>> lumpsByMonth = new HashMap<>();
        for (AccountLumpSum ls : lumpsFull) {
            final java.time.YearMonth ym = java.time.YearMonth.from(ls.getRegisteredDate());
            BigDecimal amt = BigDecimal.valueOf(ls.getAmount());
            if (amt.compareTo(BigDecimal.ZERO) < 0) amt = BigDecimal.ZERO; // legacy clamp
            lumpsByMonth
                    .computeIfAbsent(ym, k -> new HashMap<>())
                    .merge(ls.getAccountingAccount().getUuid(), amt.setScale(SCALE, RM), BigDecimal::add);
        }

        // -------- Build MonthData per month using shared availability ----------
        final Map<java.time.YearMonth, MonthData> perMonth = new LinkedHashMap<>();
        for (java.time.YearMonth ym : months) {
            final LocalDate monthFrom = ym.atDay(1);
            final LocalDate monthTo = ym.plusMonths(1).atDay(1);

            // Consultant counts & ratios based on the FY-wide availability list
            final Map<String, BigDecimal> cCount = new HashMap<>();
            companies.forEach(c -> {
                double cnt = availabilityService.calculateConsultantCount(c, monthFrom, availabilityAll);
                cCount.put(c.getUuid(), BigDecimal.valueOf(cnt));
            });
            final BigDecimal total = cCount.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            final Map<String, BigDecimal> ratios = new HashMap<>();
            if (total.compareTo(BigDecimal.ZERO) > 0) {
                companies.forEach(c ->
                        ratios.put(c.getUuid(), cCount.get(c.getUuid()).divide(BigDecimal.ONE.max(total), RATIO_SCALE, RM)));
            } else {
                companies.forEach(c -> ratios.put(c.getUuid(), BigDecimal.ZERO));
            }

            // Staff base (kept per-month for correct salary cap semantics)
            final Map<String, BigDecimal> staffBase = staffBaseFromBI(ym.getYear(), ym.getMonthValue(), salaryBufferMultiplier);

            final Map<String, Map<Integer, BigDecimal>> glRange =
                    glRangeByMonth.getOrDefault(ym, Collections.emptyMap());
            final Map<String, Map<Integer, BigDecimal>> glExact =
                    glExactByMonth.getOrDefault(ym, Collections.emptyMap());

            perMonth.put(ym, new MonthData(
                    monthFrom,
                    monthTo,
                    companies,
                    categories,
                    availabilityAll,   // ok: MonthData only uses this to derive counts/ratios, which we already computed
                    glRange,
                    glExact,
                    cCount,
                    ratios,
                    staffBase
            ));
        }

        return new FiscalYearData(companies, categories, perMonth, lumpsByMonth);
    }

    // In: dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService

    /**
     * Compute the final (post-distribution) total per category for a single payer company
     * for the given month context.
     *
     * Semantics match /accounting/distribution (legacy):
     *  - Salary: shared but capped by STAFF base (BI * multiplier), cap consumed across salary accounts.
     *  - Non-salary shared: fully shareable.
     *  - Non-shared: stay with origin; payer only gets origin remainder if payer==origin.
     *  - Lumps are not shared and month-range lumps are clamped at 0.
     *
     * @param md month data (from loadMonthData)
     * @param lumpsByAccount accountUuid -> amount (clamped >= 0) for [md.monthFrom, md.monthTo)
     * @param payerCompanyUuid company that we want the final totals for
     * @return Map<categoryCode, amount>
     */
    public Map<String, BigDecimal> computeCategoryTotalsForCompany(
            MonthData md,
            Map<String, BigDecimal> lumpsByAccount,
            String payerCompanyUuid
    ) {
        final Map<String, BigDecimal> totalsByCategory = new HashMap<>();

        // Mutable copy for STAFF cap behavior (legacy)
        final Map<String, BigDecimal> staffRemaining = new HashMap<>(md.staffBaseBI102);

        for (AccountingCategory catSrc : md.categories) {
            final String catCode = catSrc.getAccountCode();

            for (AccountingAccount aaSrc : catSrc.getAccounts()) {
                final String originUuid = aaSrc.getCompany().getUuid();
                final int accountCode = aaSrc.getAccountCode();

                BigDecimal gl = md.glByCompanyAccountRange
                        .getOrDefault(originUuid, Collections.emptyMap())
                        .getOrDefault(accountCode, BigDecimal.ZERO);

                BigDecimal lump = lumpsByAccount.getOrDefault(aaSrc.getUuid(), BigDecimal.ZERO);

                IntercompanyCalcService.ShareAmounts share =
                        computeDistributionLegacyShareForAccount(md, aaSrc, originUuid, gl, lump, staffRemaining);

                BigDecimal alloc = BigDecimal.ZERO;

                if (share.baseToShare.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal r = md.ratioByCompany.getOrDefault(payerCompanyUuid, BigDecimal.ZERO);
                    BigDecimal part = share.baseToShare.multiply(r).setScale(SCALE, RM);
                    alloc = alloc.add(part);
                }
                if (payerCompanyUuid.equals(originUuid) && share.originRemainder.compareTo(BigDecimal.ZERO) > 0) {
                    alloc = alloc.add(share.originRemainder);
                }

                if (alloc.compareTo(BigDecimal.ZERO) != 0) {
                    totalsByCategory.merge(catCode, alloc, BigDecimal::add);
                }
            }
        }

        // Ensure 2-decimal scale for all values
        totalsByCategory.replaceAll((k, v) -> v.setScale(SCALE, RM));
        return totalsByCategory;
    }


}
