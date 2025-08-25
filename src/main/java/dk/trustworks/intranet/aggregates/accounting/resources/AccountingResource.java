package dk.trustworks.intranet.aggregates.accounting.resources;

import dk.trustworks.intranet.aggregates.accounting.dto.*;
import dk.trustworks.intranet.aggregates.accounting.model.CompanyAccountingRecord;
import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService;
import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.dto.DateAccountCategoriesDTO;
import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.expenseservice.resources.ExpenseResource;
import dk.trustworks.intranet.expenseservice.resources.UserAccountResource;
import dk.trustworks.intranet.financeservice.model.AccountLumpSum;
import dk.trustworks.intranet.financeservice.model.AccountingAccount;
import dk.trustworks.intranet.financeservice.model.AccountingCategory;
import dk.trustworks.intranet.financeservice.model.FinanceDetails;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.panache.common.Sort;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Tag(name = "accounting")
@JBossLog
@RequestScoped
@Path("/accounting")
@Produces("application/json")
@Consumes("application/json")
@RolesAllowed({"SYSTEM", "USER"})
@SecurityRequirement(name = "jwt")
public class AccountingResource {

    @Inject
    EntityManager em;

    @Inject
    ExpenseResource expenseAPI;

    @Inject
    UserAccountResource userAccountAPI;

    @Inject
    AvailabilityService availabilityService;

    @Inject
    IntercompanyCalcService calcService;

    @ConfigProperty(name = "dk.trustworks.intranet.aggregates.accounting.salary-buffer-multiplier", defaultValue = "1.02")
    double salaryBufferMultiplier;

    /**
     * Calculates adjusted expenses for salary accounts, applying the salary buffer multiplier
     * @param partialExpenses The initial expense amount
     * @param otherSalarySources Additional salary sources to consider
     * @param companySalarySum The total salary sum for the company
     * @return The adjusted expense amount
     */
    private double calculateAdjustedSalaryExpenses(double partialExpenses, double otherSalarySources, double companySalarySum) {
        partialExpenses += otherSalarySources;
        return Math.max(0, partialExpenses - (companySalarySum * salaryBufferMultiplier));
    }

    @GET
    @Path("/categories/csv")
    public List<DateAccountCategoriesDTO> findAllAccountingCategoriesCSV(
            @QueryParam("companyuuid") String companyuuid,
            @QueryParam("fromdate") Optional<String> strFromdate,
            @QueryParam("todate") Optional<String> strTodate) {

        // ---- Validation & setup ----
        if (companyuuid == null || companyuuid.trim().isEmpty()) {
            log.warnf("Request rejected: Company UUID is required");
            throw new WebApplicationException("Company UUID is required", Response.Status.BAD_REQUEST);
        }

        LocalDate datefrom = strFromdate.map(DateUtils::dateIt)
                .orElse(LocalDate.of(2017, 1, 1))
                .withDayOfMonth(1);
        LocalDate dateto = strTodate.map(DateUtils::dateIt)
                .orElse(LocalDate.now())
                .withDayOfMonth(1)
                .plusMonths(1);

        if (datefrom.isAfter(dateto)) throw new WebApplicationException("From date must be before or equal to to date", Response.Status.BAD_REQUEST);
        if (datefrom.isBefore(LocalDate.of(2010, 1, 1))) throw new WebApplicationException("From date cannot be before 2010-01-01", Response.Status.BAD_REQUEST);
        if (dateto.isAfter(LocalDate.now().plusYears(2))) throw new WebApplicationException("To date cannot be more than 2 years in the future", Response.Status.BAD_REQUEST);

        final Company primaryCompany = Company.findById(companyuuid);
        if (primaryCompany == null) throw new WebApplicationException("Company not found: " + companyuuid, Response.Status.NOT_FOUND);

        final List<EmployeeAvailabilityPerMonth> availability =
                availabilityService.getAllEmployeeAvailabilityByPeriod(datefrom, dateto);

        final List<AccountingCategory> allCategories =
                AccountingCategory.listAll(Sort.by("accountCode", Sort.Direction.Ascending));

        final List<FinanceDetails> allFinance =
                FinanceDetails.list("expensedate >= ?1 and expensedate < ?2", datefrom, dateto);

        final List<AccountLumpSum> allLumps =
                AccountLumpSum.list("registeredDate >= ?1 and registeredDate < ?2", datefrom, dateto);

        // BigDecimal config
        final int SCALE = 2; // cents
        final int RATIO_SCALE = 10; // for proportions
        final RoundingMode RM = RoundingMode.HALF_EVEN; // banker’s rounding

        final BigDecimal SALARY_BUFFER = BigDecimal.valueOf(salaryBufferMultiplier);

        // Build a BigDecimal lump map: accountUuid -> (monthStart -> sum)
        final Map<String, Map<LocalDate, BigDecimal>> lumpsByAccountAndMonth = new HashMap<>();
        for (AccountLumpSum ls : allLumps) {
            String key = ls.getAccountingAccount().getUuid();
            LocalDate month = ls.getRegisteredDate().withDayOfMonth(1);
            BigDecimal amt = BigDecimal.valueOf(ls.getAmount());
            lumpsByAccountAndMonth.computeIfAbsent(key, k -> new HashMap<>())
                    .merge(month, amt, BigDecimal::add);
        }

        // Partition finance rows by (company, month, accountnumber) to avoid repeated filters
        // Map<Company, Map<LocalDate, Map<Integer, BigDecimal>>>
        final Map<Company, Map<LocalDate, Map<Integer, BigDecimal>>> financeByCoMonthAcc = new HashMap<>();
        for (FinanceDetails fd : allFinance) {
            Company co = fd.getCompany();
            LocalDate m = fd.getExpensedate().withDayOfMonth(1);
            int acc = fd.getAccountnumber();
            BigDecimal amt = BigDecimal.valueOf(fd.getAmount());
            financeByCoMonthAcc
                    .computeIfAbsent(co, c -> new HashMap<>())
                    .computeIfAbsent(m, mm -> new HashMap<>())
                    .merge(acc, amt, BigDecimal::add);
        }

        // Secondary companies list
        final List<Company> allCompanies = Company.listAll();
        final List<Company> secondaryCompanies = allCompanies.stream()
                .filter(c -> !c.getUuid().equals(primaryCompany.getUuid()))
                .toList();

        // ---- Main monthly loop ----
        final Map<LocalDate, DateAccountCategoriesDTO> byMonth = new TreeMap<>(); // chronological

        for (LocalDate cursor = datefrom; cursor.isBefore(dateto); cursor = cursor.plusMonths(1)) {
            final LocalDate month = cursor.withDayOfMonth(1);
            final DateAccountCategoriesDTO dto = new DateAccountCategoriesDTO(month, new ArrayList<>());
            byMonth.put(month, dto);

            // Driver: consultant counts (as headcount) and consultant salary sums, per company
            final Map<Company, BigDecimal> consultantCount = new HashMap<>();
            final Map<Company, BigDecimal> consultantSalary = new HashMap<>();

            for (Company c : allCompanies) {
                BigDecimal count = BigDecimal.valueOf(
                        availabilityService.calculateConsultantCount(c, month, availability)
                );
                BigDecimal salary = BigDecimal.valueOf(
                        availabilityService.calculateSalarySum(c, month, availability)
                );
                consultantCount.put(c, count);
                consultantSalary.put(c, salary);
            }

            BigDecimal totalConsultants = consultantCount.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (totalConsultants.compareTo(BigDecimal.ZERO) == 0) {
                log.debugf("Skipping %s: no consultants across companies", month);
                continue;
            }

            // Precompute, per company & month, the salary GL pools across ALL categories (after lumps):
            // - sharedSalaryGL[c]: sum of shared salary accounts (after lumps)
            // - nonSharedSalaryGL[c]: sum of non-shared salary accounts (after lumps)
            // - staffSalaryPool[c] = max(0, (shared + nonShared) - buffer * consultantSalary[c])
            final Map<Company, BigDecimal> sharedSalaryGL = new HashMap<>();
            final Map<Company, BigDecimal> nonSharedSalaryGL = new HashMap<>();
            final Map<Company, BigDecimal> staffSalaryPool = new HashMap<>();

            for (Company c : allCompanies) {
                BigDecimal shared = BigDecimal.ZERO;
                BigDecimal nonShared = BigDecimal.ZERO;

                for (AccountingCategory ac : allCategories) {
                    for (AccountingAccount aa : ac.getAccounts()) {
                        if (!aa.getCompany().equals(c) || !aa.isSalary()) continue;

                        BigDecimal gl = financeByCoMonthAcc
                                .getOrDefault(c, Collections.emptyMap())
                                .getOrDefault(month, Collections.emptyMap())
                                .getOrDefault(aa.getAccountCode(), BigDecimal.ZERO);

                        BigDecimal lump = lumpsByAccountAndMonth
                                .getOrDefault(aa.getUuid(), Collections.emptyMap())
                                .getOrDefault(month, BigDecimal.ZERO);

                        BigDecimal net = gl.subtract(lump);
                        if (aa.isShared()) shared = shared.add(net);
                        else nonShared = nonShared.add(net);
                    }
                }

                sharedSalaryGL.put(c, shared);
                nonSharedSalaryGL.put(c, nonShared);

                BigDecimal consultantPayroll = SALARY_BUFFER.multiply(consultantSalary.get(c));
                BigDecimal totalSalaryGL = shared.add(nonShared);

                // Keep staff pool non-negative (credits/reversals on salary shouldn’t create “negative staff”)
                BigDecimal pool = totalSalaryGL.subtract(consultantPayroll);
                if (pool.compareTo(BigDecimal.ZERO) < 0) pool = BigDecimal.ZERO;

                staffSalaryPool.put(c, pool.setScale(SCALE, RM));
            }

            // Helper lambdas
            java.util.function.BiFunction<Company, AccountingAccount, BigDecimal> accountNetAfterLumps = (co, aa) -> {
                BigDecimal gl = financeByCoMonthAcc
                        .getOrDefault(co, Collections.emptyMap())
                        .getOrDefault(month, Collections.emptyMap())
                        .getOrDefault(aa.getAccountCode(), BigDecimal.ZERO);

                BigDecimal lump = lumpsByAccountAndMonth
                        .getOrDefault(aa.getUuid(), Collections.emptyMap())
                        .getOrDefault(month, BigDecimal.ZERO);

                return gl.subtract(lump); // can be negative
            };

            java.util.function.BiFunction<BigDecimal, BigDecimal, BigDecimal> safeRatio = (num, den) -> {
                if (den == null || den.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
                return num.divide(den, RATIO_SCALE, RM);
            };

            // Prepare consultant shares
            final BigDecimal primaryShareOfOthers = consultantCount.get(primaryCompany)
                    .divide(totalConsultants, RATIO_SCALE, RM); // used on secondary to compute "debt"
            final BigDecimal othersShareOfPrimary = BigDecimal.ONE.subtract(primaryShareOfOthers); // used on primary to compute "loan"

            // ---- Build categories with allocated loans/debts ----
            for (AccountingCategory sourceCat : allCategories) {
                AccountingCategory cat = new AccountingCategory(sourceCat.getAccountCode(), sourceCat.getAccountname());
                dto.getAccountingCategories().add(cat);

                for (AccountingAccount aaSrc : sourceCat.getAccounts()) {
                    // Clone account object into this category to avoid graph confusion
                    AccountingAccount aa = new AccountingAccount(
                            aaSrc.getCompany(), cat, aaSrc.getAccountCode(),
                            aaSrc.getAccountDescription(), aaSrc.isShared(), aaSrc.isSalary()
                    );
                    cat.getAccounts().add(aa);

                    Company aaCompany = aa.getCompany();

                    // Full GL amount (before lumps) for sums on primary/secondary totals
                    BigDecimal full = financeByCoMonthAcc
                            .getOrDefault(aaCompany, Collections.emptyMap())
                            .getOrDefault(month, Collections.emptyMap())
                            .getOrDefault(aa.getAccountCode(), BigDecimal.ZERO);

                    // Track primary vs secondary sums (unadjusted)
                    if (aaCompany.equals(primaryCompany)) {
                        aa.addSum(full.setScale(SCALE, RM).doubleValue());
                        cat.addPrimarySum(full.setScale(SCALE, RM).doubleValue());
                    } else {
                        cat.addSecondarySum(full.setScale(SCALE, RM).doubleValue());
                    }

                    // Allocation only applies to shared accounts
                    if (!aa.isShared()) continue;

                    // Net (GL - lumps). For salary, we will replace "net" by the share of the staff pool later.
                    BigDecimal net = accountNetAfterLumps.apply(aaCompany, aa).setScale(SCALE, RM);

                    // Determine the amount subject to intercompany split for THIS account
                    BigDecimal allocBase;
                    if (aa.isSalary()) {
                        // Allocate STAFF salary pool across all SHARED salary accounts, proportional to each account's net share.
                        BigDecimal denom = sharedSalaryGL.get(aaCompany);
                        if (denom == null || denom.compareTo(BigDecimal.ZERO) == 0) {
                            allocBase = BigDecimal.ZERO;
                        } else {
                            BigDecimal share = safeRatio.apply(net, denom); // net of this shared salary account / total shared salary
                            allocBase = staffSalaryPool.get(aaCompany).multiply(share).setScale(SCALE, RM);
                        }
                    } else {
                        // Non-salary shared accounts: allocate the actual net (credits allowed).
                        allocBase = net;
                    }

                    // If zero, nothing to allocate
                    if (allocBase.compareTo(BigDecimal.ZERO) == 0) continue;

                    if (aaCompany.equals(primaryCompany)) {
                        // Primary holds the cost; others owe "loan"
                        BigDecimal loan = allocBase.multiply(othersShareOfPrimary).setScale(SCALE, RM);
                        aa.addLoan(loan.doubleValue()); // allow negative if allocBase < 0
                    } else {
                        // Secondary holds the cost; primary owes "debt"
                        BigDecimal debt = allocBase.multiply(primaryShareOfOthers).setScale(SCALE, RM);
                        aa.addDebt(debt.doubleValue()); // allow negative if allocBase < 0
                    }
                }
            }

            log.debugf("Processed month %s for company %s", month, primaryCompany.getUuid());
        }

        log.infof("Successfully processed accounting categories for company %s with %d monthly results",
                companyuuid, byMonth.size());

        // Sorted by month due to TreeMap
        return byMonth.values().stream().toList();
    }

    @GET
    @Path("/distribution")
    public ExpenseDistributionResult distributeMonthlyExpenses(@QueryParam("year") int year, @QueryParam("month") int month) {

        if (year < 2010 || month < 1 || month > 12) {
            throw new WebApplicationException("Invalid year/month", Response.Status.BAD_REQUEST);
        }
        final LocalDate from = LocalDate.of(year, month, 1);
        final LocalDate to = from.plusMonths(1);

        // KONSTANTER (uændret)
        final int SCALE = 2;
        final int RATIO_SCALE = 10;
        final RoundingMode RM = RoundingMode.HALF_EVEN;

        // ---- NY: centraliseret indlæsning ----
        final IntercompanyCalcService.MonthData md =
                calcService.loadMonthData(from, to, salaryBufferMultiplier);

        // Lumps (legacy-adfærd: clamp negatives)
        final Map<String, BigDecimal> lumpsByAccount = calcService.lumpsMonthRange(from, to);

        // Result container (uændret)
        ExpenseDistributionResult result = new ExpenseDistributionResult(year, month);

        // Company summaries (uændret)
        md.companies.forEach(c -> {
            CompanySummary cs = new CompanySummary();
            cs.companyUuid = c.getUuid();
            cs.companyName = c.getName();
            cs.consultants  = md.consultantCount.getOrDefault(c.getUuid(), BigDecimal.ZERO)
                    .setScale(RATIO_SCALE, RM);
            cs.staffCostOrigin = md.staffBaseBI102.getOrDefault(c.getUuid(), BigDecimal.ZERO);
            result.companies.add(cs);
        });
        final Map<String, CompanySummary> companySummaryIndex =
                result.companies.stream().collect(Collectors.toMap(x -> x.companyUuid, x -> x));

        final Map<String, Map<String, BigDecimal>> catAgg = new HashMap<>();
        final Map<String, String> catNameByCode = new HashMap<>();
        final Map<String, Map<String, Map<Integer, BigDecimal>>> owesByAccount = new HashMap<>();
        final Map<String, Map<String, Map<String, BigDecimal>>>  owesByCategory = new HashMap<>();
        final Map<String, BigDecimal> staffPayableByCompany = new HashMap<>();

        // Mutable staffRemaining = BI-base (legacy cap)
        final Map<String, BigDecimal> staffRemaining = new HashMap<>(md.staffBaseBI102);

        // ---- Hovedløkken (samme struktur som før) ----
        for (AccountingCategory catSrc : md.categories) {
            final String catCode = catSrc.getAccountCode();
            final String catName = catSrc.getAccountname();
            catNameByCode.put(catCode, catName);

            for (AccountingAccount aaSrc : catSrc.getAccounts()) {
                final String originUuid = aaSrc.getCompany().getUuid();
                final int accountCode = aaSrc.getAccountCode();
                final boolean isShared = aaSrc.isShared();
                final boolean isSalary = aaSrc.isSalary();

                BigDecimal gl = md.glByCompanyAccountRange
                        .getOrDefault(originUuid, Collections.emptyMap())
                        .getOrDefault(accountCode, BigDecimal.ZERO);

                BigDecimal lump = lumpsByAccount.getOrDefault(aaSrc.getUuid(), BigDecimal.ZERO);

                // NY: fælles beregningsprimitive for legacy-fordeling
                IntercompanyCalcService.ShareAmounts share =
                        calcService.computeDistributionLegacyShareForAccount(md, aaSrc, originUuid, gl, lump, staffRemaining);

                // Byg uændret AccountDistribution
                AccountDistribution dist = new AccountDistribution();
                dist.accountCode = accountCode;
                dist.accountDescription = aaSrc.getAccountDescription();
                dist.categoryCode = catCode;
                dist.categoryName = catName;
                dist.originCompanyUuid = originUuid;
                dist.shared = (isShared || isSalary);
                dist.salary = isSalary;

                // Allokér til betalere efter ratio (uændret)
                for (Company payer : md.companies) {
                    String payerUuid = payer.getUuid();
                    BigDecimal alloc = BigDecimal.ZERO;

                    if (share.baseToShare.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal r = md.ratioByCompany.getOrDefault(payerUuid, BigDecimal.ZERO);
                        BigDecimal part = share.baseToShare.multiply(r).setScale(SCALE, RM);
                        alloc = alloc.add(part);

                        if (!payerUuid.equals(originUuid) && part.compareTo(BigDecimal.ZERO) > 0) {
                            owesByAccount.computeIfAbsent(payerUuid, k -> new HashMap<>())
                                    .computeIfAbsent(originUuid, k -> new HashMap<>())
                                    .merge(accountCode, part, BigDecimal::add);
                            owesByCategory.computeIfAbsent(payerUuid, k -> new HashMap<>())
                                    .computeIfAbsent(originUuid, k -> new HashMap<>())
                                    .merge(catCode, part, BigDecimal::add);
                        }
                        if (isSalary && part.compareTo(BigDecimal.ZERO) > 0) {
                            staffPayableByCompany.merge(payerUuid, part, BigDecimal::add);
                        }
                    }

                    if (payerUuid.equals(originUuid) && share.originRemainder.compareTo(BigDecimal.ZERO) > 0) {
                        alloc = alloc.add(share.originRemainder);
                    }

                    dist.allocations.put(payerUuid, alloc.setScale(SCALE, RM));

                    catAgg.computeIfAbsent(catCode, k -> new HashMap<>())
                            .merge(payerUuid, alloc, BigDecimal::add);
                }

                result.accounts.add(dist);
            }
        }

        // Resten: finalize categories, owes-lister og staffPayable (uændret)
        catAgg.forEach((cc, map) -> {
            CategoryAggregate ca = new CategoryAggregate();
            ca.categoryCode = cc;
            ca.categoryName = catNameByCode.getOrDefault(cc, "");
            map.replaceAll((k, v) -> v.setScale(SCALE, RM));
            ca.allocations.putAll(map);
            result.categories.add(ca);
        });
        owesByAccount.forEach((payer, recvMap) -> recvMap.forEach((receiver, accMap) ->
                accMap.forEach((acc, amt) -> {
                    IntercompanyOwe row = new IntercompanyOwe();
                    row.fromCompanyUuid = payer;
                    row.toCompanyUuid = receiver;
                    row.accountCode = acc;
                    row.amount = amt.setScale(SCALE, RM);
                    result.owesByAccount.add(row);
                })
        ));
        owesByCategory.forEach((payer, recvMap) -> recvMap.forEach((receiver, catMap) ->
                catMap.forEach((cc, amt) -> {
                    IntercompanyOweCategory row = new IntercompanyOweCategory();
                    row.fromCompanyUuid = payer;
                    row.toCompanyUuid = receiver;
                    row.categoryCode = cc;
                    row.categoryName = catNameByCode.getOrDefault(cc, "");
                    row.amount = amt.setScale(SCALE, RM);
                    result.owesByCategory.add(row);
                })
        ));
        staffPayableByCompany.forEach((uuid, amt) -> {
            CompanySummary cs = companySummaryIndex.get(uuid);
            if (cs != null) cs.staffPayable = amt.setScale(SCALE, RM);
        });

        return result;
    }

    // In: dk.trustworks.intranet.aggregates.accounting.resources.AccountingResource

    @GET
    @Path("/distribution/categories/by-month")
    public List<CompanyCategoryMonth> distributedCategoryExpensesByMonth(
            @QueryParam("companyuuid") String companyuuid,
            @QueryParam("from") String fromMonthStr,
            @QueryParam("to") String toMonthStr
    ) {
        // ---- Validation ----
        if (companyuuid == null || companyuuid.isBlank())
            throw new WebApplicationException("Company UUID is required", Response.Status.BAD_REQUEST);

        final Company payer = Company.findById(companyuuid);
        if (payer == null)
            throw new WebApplicationException("Company not found: " + companyuuid, Response.Status.NOT_FOUND);

        final LocalDate fromMonth = parseMonthArgOrThrow(fromMonthStr)
                .withDayOfMonth(1);
        final LocalDate toMonth = parseMonthArgOrThrow(toMonthStr)
                .withDayOfMonth(1);

        if (fromMonth.isAfter(toMonth))
            throw new WebApplicationException("From month must be before or equal to to month", Response.Status.BAD_REQUEST);
        if (fromMonth.isBefore(LocalDate.of(2010, 1, 1)))
            throw new WebApplicationException("From month cannot be before 2010-01-01", Response.Status.BAD_REQUEST);
        if (toMonth.isAfter(LocalDate.now().plusYears(2)))
            throw new WebApplicationException("To month cannot be more than 2 years in the future", Response.Status.BAD_REQUEST);

        // ---- Main loop per month (inclusive) ----
        final List<CompanyCategoryMonth> out = new ArrayList<>();
        for (LocalDate cursor = fromMonth; !cursor.isAfter(toMonth); cursor = cursor.plusMonths(1)) {
            final LocalDate monthStart = cursor.withDayOfMonth(1);
            final LocalDate monthEndExcl = monthStart.plusMonths(1);

            // Batch-load month context once
            final IntercompanyCalcService.MonthData md =
                    calcService.loadMonthData(monthStart, monthEndExcl, salaryBufferMultiplier);

            // Lumps for month (legacy clamp >= 0)
            final Map<String, BigDecimal> lumpsByAccount =
                    calcService.lumpsMonthRange(monthStart, monthEndExcl);

            // Compute category totals for payer company
            final Map<String, BigDecimal> totals =
                    calcService.computeCategoryTotalsForCompany(md, lumpsByAccount, companyuuid);

            // Build a stable category list (include all categories, zeros allowed)
            final Map<String, String> catNames = md.categories.stream()
                    .collect(Collectors.toMap(
                            AccountingCategory::getAccountCode,
                            AccountingCategory::getAccountname,
                            (a, b) -> a)); // keep first if duplicate keys

            final CompanyCategoryMonth row = new CompanyCategoryMonth(monthStart);

            // Preserve the sorted order already present in md.categories (by accountCode asc)
            for (AccountingCategory cat : md.categories) {
                final String code = cat.getAccountCode();
                final String name = catNames.getOrDefault(code, "");
                final BigDecimal amount = totals.getOrDefault(code, BigDecimal.ZERO)
                        .setScale(IntercompanyCalcService.SCALE, IntercompanyCalcService.RM);

                row.categories.add(new CompanyCategoryAmount(code, name, amount));
            }

            out.add(row);
        }

        log.infof("Distributed category expenses by month computed for company %s (%d months)",
                companyuuid, out.size());
        return out;
    }

    /** Accepts yyyy-MM or yyyy-MM-dd; returns first-of-month LocalDate on success, otherwise BAD_REQUEST. */
    private LocalDate parseMonthArgOrThrow(String s) {
        if (s == null || s.isBlank())
            throw new WebApplicationException("Missing required month parameter", Response.Status.BAD_REQUEST);
        final String trimmed = s.trim();
        try {
            if (trimmed.length() == 7) { // yyyy-MM
                return java.time.YearMonth.parse(trimmed).atDay(1);
            }
            // yyyy-MM-dd
            return DateUtils.dateIt(trimmed).withDayOfMonth(1);
        } catch (Exception ex) {
            throw new WebApplicationException("Invalid month format. Use yyyy-MM or yyyy-MM-dd", Response.Status.BAD_REQUEST);
        }
    }


    @GET
    @Path("/distribution/fy")
    public ExpenseDistributionResult distributeFinancialYear(@QueryParam("fyEndYear") int fyEndYear) {

        if (fyEndYear < 2011) {
            throw new WebApplicationException("Invalid financial year", Response.Status.BAD_REQUEST);
        }

        // Financial year is July 1 (fyEndYear-1) through June 30 (fyEndYear)
        final LocalDate fyFrom = LocalDate.of(fyEndYear - 1, 7, 1);   // inclusive
        final LocalDate fyTo   = LocalDate.of(fyEndYear, 7, 1);       // exclusive

        // Use the same constants as the monthly method
        final int SCALE = IntercompanyCalcService.SCALE;
        final int RATIO_SCALE = IntercompanyCalcService.RATIO_SCALE;
        final RoundingMode RM = IntercompanyCalcService.RM;

        // Batch-load everything we can for the whole FY
        final IntercompanyCalcService.FiscalYearData fyData =
                calcService.loadFiscalYear(fyFrom, fyTo, salaryBufferMultiplier);

        // Result container (mark with FY ending June)
        ExpenseDistributionResult result = new ExpenseDistributionResult(fyEndYear, 6);

        // ---- Aggregation state (spans all 12 months) ----
        final List<Company> companies = fyData.companies;
        final Map<String, String> companyNameByUuid = companies.stream()
                .collect(Collectors.toMap(Company::getUuid, Company::getName));

        // Companies
        final Map<String, BigDecimal> consultantsSum = new HashMap<>();    // sum over months (we’ll avg later)
        final Map<String, BigDecimal> staffOriginSum = new HashMap<>();    // sum over months
        final Map<String, BigDecimal> staffPayableSum = new HashMap<>();   // sum over months

        // Accounts (key = originUuid|accountCode)
        final Map<String, AccountDistribution> accountIndex = new LinkedHashMap<>();

        // Categories
        final Map<String, Map<String, BigDecimal>> catAgg = new HashMap<>();
        final Map<String, String> catNameByCode = new HashMap<>();

        // Owes
        final Map<String, Map<String, Map<Integer, BigDecimal>>> owesByAccount = new HashMap<>();
        final Map<String, Map<String, Map<String, BigDecimal>>>  owesByCategory = new HashMap<>();

        // Iterate the 12 months in order
        final int totalMonths = 12;
        LocalDate cursor = fyFrom;
        for (int i = 0; i < totalMonths; i++, cursor = cursor.plusMonths(1)) {
            final java.time.YearMonth ym = java.time.YearMonth.from(cursor);

            final IntercompanyCalcService.MonthData md =
                    fyData.perMonth.get(ym);
            final Map<String, BigDecimal> lumpsByAccount =
                    fyData.lumpsByMonth.getOrDefault(ym, Collections.emptyMap());

            // Company summaries (collect sums for later finalize)
            md.companies.forEach(c -> {
                String cu = c.getUuid();
                BigDecimal cons = md.consultantCount.getOrDefault(cu, BigDecimal.ZERO)
                        .setScale(RATIO_SCALE, RM);
                consultantsSum.merge(cu, cons, BigDecimal::add);

                BigDecimal staffOrigin = md.staffBaseBI102.getOrDefault(cu, BigDecimal.ZERO);
                staffOriginSum.merge(cu, staffOrigin, BigDecimal::add);
            });

            // Mutable staffRemaining resets each month (legacy salary cap behavior)
            final Map<String, BigDecimal> staffRemaining = new HashMap<>(md.staffBaseBI102);

            // ---- Same distribution loop as the monthly endpoint ----
            for (AccountingCategory catSrc : md.categories) {
                final String catCode = catSrc.getAccountCode();
                final String catName = catSrc.getAccountname();
                catNameByCode.put(catCode, catName);

                for (AccountingAccount aaSrc : catSrc.getAccounts()) {
                    final String originUuid = aaSrc.getCompany().getUuid();
                    final int accountCode = aaSrc.getAccountCode();
                    final boolean isShared = aaSrc.isShared();
                    final boolean isSalary = aaSrc.isSalary();

                    BigDecimal gl = md.glByCompanyAccountRange
                            .getOrDefault(originUuid, Collections.emptyMap())
                            .getOrDefault(accountCode, BigDecimal.ZERO);

                    BigDecimal lump = lumpsByAccount.getOrDefault(aaSrc.getUuid(), BigDecimal.ZERO);

                    IntercompanyCalcService.ShareAmounts share =
                            calcService.computeDistributionLegacyShareForAccount(
                                    md, aaSrc, originUuid, gl, lump, staffRemaining);

                    // Aggregated AccountDistribution (one per (origin,account))
                    final String accKey = originUuid + "|" + accountCode;
                    final AccountDistribution dist = accountIndex.computeIfAbsent(accKey, k -> {
                        AccountDistribution d = new AccountDistribution();
                        d.accountCode = accountCode;
                        d.accountDescription = aaSrc.getAccountDescription();
                        d.categoryCode = catCode;
                        d.categoryName = catName;
                        d.originCompanyUuid = originUuid;
                        d.shared = (isShared || isSalary);
                        d.salary = isSalary;
                        return d;
                    });

                    // Allocate to payers by ratio (identical to monthly)
                    for (Company payer : md.companies) {
                        String payerUuid = payer.getUuid();
                        BigDecimal alloc = dist.allocations.getOrDefault(payerUuid, BigDecimal.ZERO);

                        if (share.baseToShare.compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal r = md.ratioByCompany.getOrDefault(payerUuid, BigDecimal.ZERO);
                            BigDecimal part = share.baseToShare.multiply(r).setScale(SCALE, RM);
                            alloc = alloc.add(part);

                            if (!payerUuid.equals(originUuid) && part.compareTo(BigDecimal.ZERO) > 0) {
                                owesByAccount.computeIfAbsent(payerUuid, k -> new HashMap<>())
                                        .computeIfAbsent(originUuid, k -> new HashMap<>())
                                        .merge(accountCode, part, BigDecimal::add);
                                owesByCategory.computeIfAbsent(payerUuid, k -> new HashMap<>())
                                        .computeIfAbsent(originUuid, k -> new HashMap<>())
                                        .merge(catCode, part, BigDecimal::add);
                            }
                            if (isSalary && part.compareTo(BigDecimal.ZERO) > 0) {
                                staffPayableSum.merge(payerUuid, part, BigDecimal::add);
                            }
                        }

                        if (payerUuid.equals(originUuid) && share.originRemainder.compareTo(BigDecimal.ZERO) > 0) {
                            alloc = alloc.add(share.originRemainder);
                        }

                        dist.allocations.put(payerUuid, alloc.setScale(SCALE, RM));

                        // Aggregate category totals across months
                        catAgg.computeIfAbsent(catCode, k -> new HashMap<>())
                                .merge(payerUuid, alloc, BigDecimal::add);
                    }
                }
            }
        }

        // ---- Finalize result from aggregated state ----

        // Companies: average consultants over 12 months; sum financials
        for (Company c : companies) {
            CompanySummary cs = new CompanySummary();
            cs.companyUuid = c.getUuid();
            cs.companyName = c.getName();

            BigDecimal consSum = consultantsSum.getOrDefault(c.getUuid(), BigDecimal.ZERO);
            cs.consultants = (totalMonths > 0
                    ? consSum.divide(BigDecimal.valueOf(totalMonths), RATIO_SCALE, RM)
                    : BigDecimal.ZERO);

            cs.staffCostOrigin = staffOriginSum.getOrDefault(c.getUuid(), BigDecimal.ZERO)
                    .setScale(SCALE, RM);
            cs.staffPayable = staffPayableSum.getOrDefault(c.getUuid(), BigDecimal.ZERO)
                    .setScale(SCALE, RM);
            result.companies.add(cs);
        }

        // Categories
        catAgg.forEach((cc, map) -> {
            CategoryAggregate ca = new CategoryAggregate();
            ca.categoryCode = cc;
            ca.categoryName = catNameByCode.getOrDefault(cc, "");
            map.replaceAll((k, v) -> v.setScale(SCALE, RM));
            ca.allocations.putAll(map);
            result.categories.add(ca);
        });

        // Accounts (already aggregated)
        result.accounts.addAll(accountIndex.values());

        // Owes
        owesByAccount.forEach((payer, recvMap) -> recvMap.forEach((receiver, accMap) ->
                accMap.forEach((acc, amt) -> {
                    IntercompanyOwe row = new IntercompanyOwe();
                    row.fromCompanyUuid = payer;
                    row.toCompanyUuid = receiver;
                    row.accountCode = acc;
                    row.amount = amt.setScale(SCALE, RM);
                    result.owesByAccount.add(row);
                })
        ));
        owesByCategory.forEach((payer, recvMap) -> recvMap.forEach((receiver, catMap) ->
                catMap.forEach((cc, amt) -> {
                    IntercompanyOweCategory row = new IntercompanyOweCategory();
                    row.fromCompanyUuid = payer;
                    row.toCompanyUuid = receiver;
                    row.categoryCode = cc;
                    row.categoryName = catNameByCode.getOrDefault(cc, "");
                    row.amount = amt.setScale(SCALE, RM);
                    result.owesByCategory.add(row);
                })
        ));

        return result;
    }

    @GET
    @Path("/categories/csv/old")
    public List<DateAccountCategoriesDTO> findAllAccountingCategoriesCSV_OLD(@QueryParam("companyuuid") String companyuuid, @QueryParam("fromdate") Optional<String> strFromdate, @QueryParam("todate") Optional<String> strTodate) {
        // Validate company UUID
        if (companyuuid == null || companyuuid.trim().isEmpty()) {
            log.warnf("Request rejected: Company UUID is required");
            throw new WebApplicationException("Company UUID is required", Response.Status.BAD_REQUEST);
        }

        LocalDate datefrom = strFromdate.map(DateUtils::dateIt).orElse(LocalDate.of(2017, 1, 1)).withDayOfMonth(1);
        LocalDate dateto = strTodate.map(DateUtils::dateIt).orElse(LocalDate.now()).withDayOfMonth(1).plusMonths(1);
        
        // Validate date range
        if (datefrom.isAfter(dateto)) {
            log.warnf("Request rejected: Invalid date range - from {} is after to {}", datefrom, dateto);
            throw new WebApplicationException("From date must be before or equal to to date", Response.Status.BAD_REQUEST);
        }
        if (datefrom.isBefore(LocalDate.of(2010, 1, 1))) {
            log.warnf("Request rejected: From date {} is before 2010-01-01", datefrom);
            throw new WebApplicationException("From date cannot be before 2010-01-01", Response.Status.BAD_REQUEST);
        }
        if (dateto.isAfter(LocalDate.now().plusYears(2))) {
            log.warnf("Request rejected: To date {} is more than 2 years in the future", dateto);
            throw new WebApplicationException("To date cannot be more than 2 years in the future", Response.Status.BAD_REQUEST);
        }
        
        log.debugf("Processing accounting categories CSV for company {} from {} to {}", companyuuid, datefrom, dateto);

        List<EmployeeAvailabilityPerMonth> employeeAvailabilityPerMonthList = availabilityService.getAllEmployeeAvailabilityByPeriod(datefrom, dateto);
        Company company = Company.findById(companyuuid);
        if (company == null) {
            throw new WebApplicationException("Company not found: " + companyuuid, Response.Status.NOT_FOUND);
        }

        List<AccountingCategory> allAccountingCategories = AccountingCategory.listAll(Sort.by("accountCode", Sort.Direction.Ascending));
        List<FinanceDetails> allFinanceDetails = FinanceDetails.list("expensedate >= ?1 and expensedate < ?2", datefrom, dateto);

        List<FinanceDetails> primaryCompanyFinanceDetails = allFinanceDetails.stream().filter(fd -> fd.getCompany().equals(company)).toList();
        List<FinanceDetails> secondaryCompaniesFinanceDetails = allFinanceDetails.stream().filter(fd -> !fd.getCompany().equals(company)).toList();

        // Load all AccountLumpSum data once to avoid queries in nested loops
        log.debug("Loading all AccountLumpSum data for performance optimization");
        List<AccountLumpSum> allLumpSums = AccountLumpSum.list("registeredDate >= ?1 and registeredDate < ?2", datefrom, dateto);
        Map<String, Map<LocalDate, Double>> lumpSumsByAccountAndDate = new HashMap<>();
        for (AccountLumpSum lumpSum : allLumpSums) {
            String accountKey = lumpSum.getAccountingAccount().getUuid();
            LocalDate monthDate = lumpSum.getRegisteredDate().withDayOfMonth(1);
            lumpSumsByAccountAndDate
                .computeIfAbsent(accountKey, k -> new HashMap<>())
                .merge(monthDate, lumpSum.getAmount(), Double::sum);
        }
        log.debugf("Loaded {} lump sum records", allLumpSums.size());

        // Cache all companies to avoid repeated queries
        List<Company> allCompanies = Company.listAll();
        List<Company> secondaryCompanies = allCompanies.stream()
            .filter(c -> !c.getUuid().equals(companyuuid))
            .toList();
        log.debugf("Processing data for {} companies", allCompanies.size());

        LocalDate date = datefrom;

        Map<LocalDate, DateAccountCategoriesDTO> result = new HashMap<>();
        do {
            // For use ind streams
            LocalDate finalDate = date;

            result.putIfAbsent(finalDate, new DateAccountCategoriesDTO(finalDate, new ArrayList<>()));

            // Beregn totale lønsum for den primært valgte virksomhed. Dette bruges til at trække fra lønsummen, så den ikke deles mellem virksomhederne.
            double primaryCompanySalarySum = availabilityService.calculateSalarySum(company, finalDate, employeeAvailabilityPerMonthList);

            // Beregn det gennemsnitlige antal konsulenter i den primære virksomhed. Dette bruges til at omkostningsfordele forbrug mellem virksomhederne.
            double primaryCompanyConsultant = availabilityService.calculateConsultantCount(company, finalDate, employeeAvailabilityPerMonthList);

            // Beregn det totale gennemsnitlige antal konsulenter i alle andre virksomheder end den primære. Dette bruge til at kunne regne en andel ud. F.eks. 65 medarbejdere ud af 100 i alt.
            AtomicReference<Double> secondaryCompanyConsultant = new AtomicReference<>(0.0);
            AtomicReference<Double> secondaryCompanySalarySum = new AtomicReference<>(0.0);
            secondaryCompanies.forEach(secondaryCompany -> {
                secondaryCompanySalarySum.updateAndGet(v -> v + availabilityService.calculateSalarySum(secondaryCompany, finalDate, employeeAvailabilityPerMonthList));
                secondaryCompanyConsultant.updateAndGet(v -> v + availabilityService.calculateConsultantCount(secondaryCompany, finalDate, employeeAvailabilityPerMonthList));
            });

            double totalNumberOfConsultants = primaryCompanyConsultant + secondaryCompanyConsultant.get();
            if (totalNumberOfConsultants == 0) {
                // Skip allocation if no consultants
                date = date.plusMonths(1);
                continue;
            }

            for (AccountingCategory ac : allAccountingCategories) {
                AccountingCategory accountingCategory = new AccountingCategory(ac.getAccountCode(), ac.getAccountname());
                result.get(finalDate).getAccountingCategories().add(accountingCategory);

                for (AccountingAccount aa : ac.getAccounts()) {
                    // Find existing account in accountingCategory or create a new one
                    Optional<AccountingAccount> existingAccount = accountingCategory.getAccounts().stream().filter(eaa -> eaa.getAccountCode() == aa.getAccountCode() && eaa.getCompany().equals(aa.getCompany())).findAny();
                    AccountingAccount accountingAccount;
                    if (existingAccount.isPresent()) accountingAccount = existingAccount.get();
                    else {
                        accountingAccount = new AccountingAccount(aa.getCompany(), ac, aa.getAccountCode(), aa.getAccountDescription(), aa.isShared(), aa.isSalary());
                        accountingCategory.getAccounts().add(accountingAccount);
                    }

                    if(accountingAccount.getCompany().equals(company)) {
                        double fullExpenses = primaryCompanyFinanceDetails.stream()
                                .filter(fd -> fd.getAccountnumber() == aa.getAccountCode() && fd.getExpensedate().withDayOfMonth(1).equals(finalDate.withDayOfMonth(1)))
                                .mapToDouble(FinanceDetails::getAmount)
                                .sum();
                        accountingAccount.addSum(fullExpenses);
                        accountingCategory.addPrimarySum(fullExpenses);
                        if(!aa.isShared()) continue;

                        // Check and skip if expenses are negative, since they are not relevant for the calculation
                        if(fullExpenses <= 0) continue;

                        // Start by making the partial expenses equal fullExpenses
                        double partialExpenses = fullExpenses;

                        // Remove lump sums from the expenses
                        double lumpSum = lumpSumsByAccountAndDate
                            .getOrDefault(aa.getUuid(), Collections.emptyMap())
                            .getOrDefault(finalDate.withDayOfMonth(1), 0.0);
                        partialExpenses -= lumpSum;

                        // Hvis kontoen er en lønkonto, så træk lønsummen fra, så den ikke deles mellem virksomhederne.
                        if (aa.isSalary() && accountingAccount.isShared()) {
                            double otherSalarySources = ac.getAccounts().stream()
                                    .filter(value -> value.getCompany().equals(company) && !value.isShared() && value.isSalary())
                                    .mapToDouble(value -> primaryCompanyFinanceDetails.stream()
                                            .filter(fd -> fd.getAccountnumber() == value.getAccountCode() && 
                                                   fd.getExpensedate().withDayOfMonth(1).equals(finalDate.withDayOfMonth(1)))
                                            .mapToDouble(FinanceDetails::getAmount)
                                            .sum())
                                    .sum();
                            partialExpenses = calculateAdjustedSalaryExpenses(partialExpenses, otherSalarySources, primaryCompanySalarySum);
                        }
                        if(partialExpenses <= 0) continue;

                        if (accountingAccount.isShared())
                            // partial fullExpenses should only account for the part of the fullExpenses equal to the share of consultants in the primary company
                            partialExpenses *= (secondaryCompanyConsultant.get() / totalNumberOfConsultants);

                        // The loan is the difference between the fullExpenses and the partialExpenses
                        accountingAccount.addLoan(Math.max(0, partialExpenses));
                    } else {
                        if(!aa.isShared()) continue;
                        double fullExpenses = secondaryCompaniesFinanceDetails.stream()
                                .filter(fd -> fd.getAccountnumber() == aa.getAccountCode() && fd.getExpensedate().withDayOfMonth(1).equals(finalDate.withDayOfMonth(1)))
                                .mapToDouble(FinanceDetails::getAmount)
                                .sum();
                        accountingCategory.addSecondarySum(fullExpenses);

                        // Check and skip if expenses are negative, since they are not relevant for the calculation
                        if(fullExpenses <= 0) continue;

                        // Start by making the partial expenses equal fullExpenses
                        double partialExpenses = fullExpenses;

                        // Remove lump sums from the expenses
                        double lumpSum = lumpSumsByAccountAndDate
                            .getOrDefault(aa.getUuid(), Collections.emptyMap())
                            .getOrDefault(finalDate.withDayOfMonth(1), 0.0);
                        partialExpenses -= lumpSum;

                        // Hvis kontoen er en lønkonto, så træk lønsummen fra, så den ikke deles mellem virksomhederne.
                        if (aa.isSalary() && accountingAccount.isShared()) {
                            double otherSalarySources = ac.getAccounts().stream()
                                    .filter(value -> !value.getCompany().equals(company) && !value.isShared() && value.isSalary())
                                    .mapToDouble(value -> secondaryCompaniesFinanceDetails.stream()
                                            .filter(fd -> fd.getAccountnumber() == value.getAccountCode() && 
                                                   fd.getExpensedate().withDayOfMonth(1).equals(finalDate.withDayOfMonth(1)))
                                            .mapToDouble(FinanceDetails::getAmount)
                                            .sum())
                                    .sum();
                            partialExpenses = calculateAdjustedSalaryExpenses(partialExpenses, otherSalarySources, secondaryCompanySalarySum.get());
                        }
                        if(partialExpenses <= 0) continue;

                        if (accountingAccount.isShared())
                            // partial fullExpenses should only account for the part of the fullExpenses equal to the share of consultants in the primary company
                            partialExpenses *= (primaryCompanyConsultant / totalNumberOfConsultants);

                        // The loan is the difference between the fullExpenses and the partialExpenses
                        accountingAccount.addDebt(Math.max(0, partialExpenses));
                    }
                }
            }
            date = date.plusMonths(1);
        } while (date.isBefore(dateto));

        log.infof("Successfully processed accounting categories for company {} with {} monthly results", companyuuid, result.size());
        return result.values().stream().toList();
    }

    @GET
    @Path("/categories/v2")
    public List<AccountingCategory> findAllAccountingCategoriesV2(@QueryParam("companyuuid") String companyuuid, @QueryParam("fromdate") Optional<String> strFromdate, @QueryParam("todate") Optional<String> strTodate) {
        // Validate company UUID
        if (companyuuid == null || companyuuid.trim().isEmpty()) {
            throw new WebApplicationException("Company UUID is required", Response.Status.BAD_REQUEST);
        }

        LocalDate datefrom = strFromdate.map(DateUtils::dateIt).orElse(LocalDate.of(2017, 1, 1)).withDayOfMonth(1);
        LocalDate dateto = strTodate.map(DateUtils::dateIt).orElse(LocalDate.now()).withDayOfMonth(1).plusMonths(1);
        
        // Validate date range
        if (datefrom.isAfter(dateto)) {
            throw new WebApplicationException("From date must be before or equal to to date", Response.Status.BAD_REQUEST);
        }

        List<EmployeeAvailabilityPerMonth> employeeAvailabilityPerMonthList = availabilityService.getAllEmployeeAvailabilityByPeriod(datefrom, dateto);
        Company company = Company.findById(companyuuid);
        if (company == null) {
            throw new WebApplicationException("Company not found: " + companyuuid, Response.Status.NOT_FOUND);
        }

        List<AccountingCategory> list = AccountingCategory.listAll(Sort.by("accountCode", Sort.Direction.Ascending));

        LocalDate date = datefrom;
        do {
            LocalDate finalDate = date;
            // Beregn totale lønsum for den primært valgte virksomhed. Dette bruges til at trække fra lønsummen, så den ikke deles mellem virksomhederne.
            double primaryCompanySalarySum = availabilityService.calculateSalarySum(company, date, employeeAvailabilityPerMonthList);

            // Beregn det gennemsnitlige antal konsulenter i den primære virksomhed. Dette bruges til at omkostningsfordele forbrug mellem virksomhederne.
            double primaryCompanyConsultantAvg = availabilityService.calculateConsultantCount(company, date, employeeAvailabilityPerMonthList);

            // Beregn det totale gennemsnitlige antal konsulenter i alle andre virksomheder end den primære. Dette bruge til at kunne regne en andel ud. F.eks. 65 medarbejdere ud af 100 i alt.
            //AtomicReference<Double> secondaryCompanyConsultantAvg = new AtomicReference<>(0.0);
            //AtomicReference<Double> secondaryCompanySalarySum = new AtomicReference<>(0.0);
            Map<Company, CompanyAccountingRecord> accountingRecordMap = new HashMap<>();
            List<Company> secondaryCompanies = Company.<Company>listAll().stream().filter(c -> !c.getUuid().equals(companyuuid)).toList();
            secondaryCompanies.forEach(secondaryCompany -> {
                accountingRecordMap.put(secondaryCompany, new CompanyAccountingRecord(
                        availabilityService.calculateConsultantCount(secondaryCompany, finalDate, employeeAvailabilityPerMonthList),
                        availabilityService.calculateSalarySum(secondaryCompany, finalDate, employeeAvailabilityPerMonthList)
                ));
                //secondaryCompanySalarySum.updateAndGet(v -> v + calculateSalarySum(secondaryCompany, finalDate, employeeAvailabilityPerMonthList));
                //secondaryCompanyConsultantAvg.updateAndGet(v -> v + calculateConsultantCount(secondaryCompany, finalDate, employeeAvailabilityPerMonthList));
            });

            double totalNumberOfConsultants = primaryCompanyConsultantAvg + accountingRecordMap.values().stream().mapToDouble(CompanyAccountingRecord::consultantCount).sum();//secondaryCompanyConsultantAvg.get();
            if (totalNumberOfConsultants == 0) {
                // Skip allocation if no consultants
                date = date.plusMonths(1);
                continue;
            }

            // Beregn omkostningsfordeling for hver kategori og account for den primære virksomhed.
            //AccountingAccount.<AccountingAccount>list("accountingCategory = ?1 and company = ?2", accountingCategory, company)
            list.forEach(accountingCategory -> accountingCategory.getAccounts().stream().filter(aa -> aa.getCompany().equals(company)).forEach(accountingAccount -> {
                accountingAccount.setSum(
                        FinanceDetails.<FinanceDetails>stream("accountnumber = ?1 and company = ?2 and expensedate >= ?3 and expensedate < ?4", accountingAccount.getAccountCode(), company, finalDate, finalDate.plusMonths(1))
                                .mapToDouble(FinanceDetails::getAmount)
                                .sum());
                accountingCategory.setPrimarySum(accountingCategory.getPrimarySum() + accountingAccount.getSum());

                accountingAccount.setAdjustedSum(accountingAccount.getSum());

                // Hvis kontoen er en lønkonto, så træk lønsummen fra, så den ikke deles mellem virksomhederne.
                if(accountingAccount.isSalary()) {
                    accountingAccount.setAdjustedSum(Math.max(0, accountingAccount.getSum() - primaryCompanySalarySum));
                }

                if(accountingAccount.isShared()) accountingAccount.setAdjustedSum(accountingAccount.getAdjustedSum() * (primaryCompanyConsultantAvg / totalNumberOfConsultants));

                if(accountingAccount.isSalary()) {
                    accountingAccount.setAdjustedSum(accountingAccount.getAdjustedSum() + primaryCompanySalarySum);
                }

                accountingCategory.setAdjustedPrimarySum(accountingCategory.getAdjustedPrimarySum() + accountingAccount.getAdjustedSum());
            }));

            // Beregn omkostningsfordeling for hver kategori og account for alle andre virksomheder end den primære.
            // AccountingAccount.<AccountingAccount>list("accountingCategory = ?1 and company = ?2 and shared is true", accountingCategory, secondaryCompany)
            secondaryCompanies.forEach(secondaryCompany -> {
                list.forEach(accountingCategory -> accountingCategory.getAccounts().stream().filter(aa -> aa.getCompany().equals(secondaryCompany) && aa.isShared()).forEach(accountingAccount -> {
                    accountingAccount.setSum(
                            FinanceDetails.<FinanceDetails>stream("accountnumber = ?1 and company = ?2 and expensedate >= ?3 and expensedate < ?4", accountingAccount.getAccountCode(), secondaryCompany, finalDate, finalDate.plusMonths(1))
                                    .mapToDouble(FinanceDetails::getAmount)
                                    .sum());

                    accountingCategory.setSecondarySum(accountingCategory.getPrimarySum() + accountingAccount.getSum());

                    accountingAccount.setAdjustedSum(accountingAccount.getSum());

                    // Hvis kontoen er en lønkonto, så træk lønsummen fra, så den ikke deles mellem virksomhederne.
                    if(accountingAccount.isSalary()) {
                        accountingAccount.setAdjustedSum(Math.max(0, accountingAccount.getAdjustedSum() - accountingRecordMap.values().stream().mapToDouble(CompanyAccountingRecord::salarySum).sum()));
                    }

                    accountingAccount.setAdjustedSum(accountingAccount.getAdjustedSum() * (accountingRecordMap.get(secondaryCompany).consultantCount() / totalNumberOfConsultants));

                    accountingCategory.setAdjustedSecondarySum(accountingCategory.getAdjustedSecondarySum() + accountingAccount.getAdjustedSum());
                }));
            });

            date = date.plusMonths(1);
        } while (date.isBefore(dateto));

        return list;
    }

    @GET
    @Path("/categories")
    public List<AccountingCategory> findAllAccountingCategories(@QueryParam("companyuuid") String companyuuid, @QueryParam("fromdate") Optional<String> strFromdate, @QueryParam("todate") Optional<String> strTodate) {
        // Validate company UUID
        if (companyuuid == null || companyuuid.trim().isEmpty()) {
            throw new WebApplicationException("Company UUID is required", Response.Status.BAD_REQUEST);
        }

        LocalDate datefrom = strFromdate.map(DateUtils::dateIt).orElse(LocalDate.of(2017, 1, 1)).withDayOfMonth(1);
        LocalDate dateto = strTodate.map(DateUtils::dateIt).orElse(LocalDate.now()).withDayOfMonth(1).plusMonths(1);
        
        // Validate date range
        if (datefrom.isAfter(dateto)) {
            throw new WebApplicationException("From date must be before or equal to to date", Response.Status.BAD_REQUEST);
        }
        int monthsBetween = DateUtils.countMonthsBetween(datefrom, dateto);

        List<EmployeeAvailabilityPerMonth> employeeAvailabilityPerMonthList = availabilityService.getAllEmployeeAvailabilityByPeriod(datefrom, dateto);
        Company company = Company.findById(companyuuid);

        // Beregn totale lønsum for den primært valgte virksomhed. Dette bruges til at trække fra lønsummen, så den ikke deles mellem virksomhederne.
        double primaryCompanySalarySum = availabilityService.calculateSalarySum(company, employeeAvailabilityPerMonthList.stream().filter(e -> e.getDate().isBefore(LocalDate.now().withDayOfMonth(1))).toList());

        // Beregn det gennemsnitlige antal konsulenter i den primære virksomhed. Dette bruges til at omkostningsfordele forbrug mellem virksomhederne.
        double primaryCompanyConsultantAvg = availabilityService.calculateConsultantCount(company, employeeAvailabilityPerMonthList) / monthsBetween;

        // Beregn det totale gennemsnitlige antal konsulenter i alle andre virksomheder end den primære. Dette bruge til at kunne regne en andel ud. F.eks. 65 medarbejdere ud af 100 i alt.
        AtomicReference<Double> secondaryCompanyConsultantAvg = new AtomicReference<>(0.0);
        AtomicReference<Double> secondaryCompanySalarySum = new AtomicReference<>(0.0);
        List<Company> secondaryCompanies = Company.<Company>listAll().stream().filter(c -> !c.getUuid().equals(companyuuid)).toList();
        secondaryCompanies.forEach(secondaryCompany -> {
            secondaryCompanySalarySum.updateAndGet(v -> v + availabilityService.calculateSalarySum(secondaryCompany, employeeAvailabilityPerMonthList.stream().filter(e -> e.getDate().isBefore(LocalDate.now().withDayOfMonth(1))).toList()));
            secondaryCompanyConsultantAvg.updateAndGet(v -> v + availabilityService.calculateConsultantCount(secondaryCompany, employeeAvailabilityPerMonthList) / monthsBetween);
        });

        double totalNumberOfConsultants = primaryCompanyConsultantAvg + secondaryCompanyConsultantAvg.get();
        if (totalNumberOfConsultants == 0) {
            // No consultants, return empty categories
            return AccountingCategory.listAll(Sort.by("accountCode", Sort.Direction.Ascending));
        }

        List<AccountingCategory> list = AccountingCategory.listAll(Sort.by("accountCode", Sort.Direction.Ascending));

        // Beregn omkostningsfordeling for hver kategori og account for den primære virksomhed.
        //AccountingAccount.<AccountingAccount>list("accountingCategory = ?1 and company = ?2", accountingCategory, company)
        list.forEach(accountingCategory -> accountingCategory.getAccounts().stream().filter(aa -> aa.getCompany().equals(company)).forEach(accountingAccount -> {
            accountingAccount.setSum(
                    FinanceDetails.<FinanceDetails>stream("accountnumber = ?1 and company = ?2 and expensedate >= ?3 and expensedate < ?4", accountingAccount.getAccountCode(), company, datefrom, dateto)
                            .mapToDouble(FinanceDetails::getAmount)
                            .sum());
            accountingCategory.setPrimarySum(accountingCategory.getPrimarySum() + accountingAccount.getSum());

            accountingAccount.setAdjustedSum(accountingAccount.getSum());

            // Hvis kontoen er en lønkonto, så træk lønsummen fra, så den ikke deles mellem virksomhederne.
            if(accountingAccount.isSalary()) {
                accountingAccount.setAdjustedSum(Math.max(0, accountingAccount.getSum() - primaryCompanySalarySum));
            }

            if(accountingAccount.isShared()) accountingAccount.setAdjustedSum(accountingAccount.getAdjustedSum() * (primaryCompanyConsultantAvg / totalNumberOfConsultants));

            if(accountingAccount.isSalary()) {
                accountingAccount.setAdjustedSum(accountingAccount.getAdjustedSum() + primaryCompanySalarySum);
            }

            accountingCategory.setAdjustedPrimarySum(accountingCategory.getAdjustedPrimarySum() + accountingAccount.getAdjustedSum());
        }));

        // Beregn omkostningsfordeling for hver kategori og account for alle andre virksomheder end den primære.
        // AccountingAccount.<AccountingAccount>list("accountingCategory = ?1 and company = ?2 and shared is true", accountingCategory, secondaryCompany)
        secondaryCompanies.forEach(secondaryCompany -> {
            list.forEach(accountingCategory -> accountingCategory.getAccounts().stream().filter(aa -> aa.getCompany().equals(secondaryCompany) && aa.isShared()).forEach(accountingAccount -> {
                accountingAccount.setSum(
                        FinanceDetails.<FinanceDetails>stream("accountnumber = ?1 and company = ?2 and expensedate >= ?3 and expensedate < ?4", accountingAccount.getAccountCode(), secondaryCompany, datefrom, dateto)
                                .mapToDouble(FinanceDetails::getAmount)
                                .sum());

                accountingCategory.setSecondarySum(accountingCategory.getPrimarySum() + accountingAccount.getSum());

                accountingAccount.setAdjustedSum(accountingAccount.getSum());

                // Hvis kontoen er en lønkonto, så træk lønsummen fra, så den ikke deles mellem virksomhederne.
                if(accountingAccount.isSalary()) {
                    accountingAccount.setAdjustedSum(Math.max(0, accountingAccount.getAdjustedSum() - secondaryCompanySalarySum.get()));
                }

                accountingAccount.setAdjustedSum(accountingAccount.getAdjustedSum() * (primaryCompanyConsultantAvg / totalNumberOfConsultants));

                accountingCategory.setAdjustedSecondarySum(accountingCategory.getAdjustedSecondarySum() + accountingAccount.getAdjustedSum());
            }));
        });

        return list;
    }


    @GET
    @Path("/categories/{uuid}")
    public AccountingCategory findAccountingCategoryByUuid(@PathParam("uuid") String uuid) {
        return AccountingCategory.findById(uuid);
    }

    @GET
    @Path("/categories/{uuid}/expenses/sum")
    public String getExpenses(@QueryParam("companyuuid") String companyuuid, @PathParam("uuid") String uuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate date1 = DateUtils.dateIt(fromdate);
        LocalDate date2 = DateUtils.dateIt(todate);
        Company company = Company.findById(companyuuid);
        if (company == null) {
            throw new WebApplicationException("Company not found: " + companyuuid, Response.Status.NOT_FOUND);
        }
        AccountingCategory category = AccountingCategory.findById(uuid);
        if (category == null) {
            throw new WebApplicationException("Accounting category not found: " + uuid, Response.Status.NOT_FOUND);
        }
        List<FinanceDetails> financeDetails = FinanceDetails.list("expensedate between ?1 and ?2", date1, date2);
        List<EmployeeAvailabilityPerMonth> employeeAvailabilityPerMonthList = availabilityService.getAllEmployeeAvailabilityByPeriod(date1, date2);
        AtomicInteger sum = new AtomicInteger();
        LocalDate date = date1;
        while(date.isBefore(date2)) {
            LocalDate finalDate = date;

            // count sum of salary per month and number of consultants
            AtomicInteger salarySum = new AtomicInteger();
            AtomicInteger totalNumberOfConsultants = new AtomicInteger();
            Map<Company, Integer> numberOfConsultantsPerCompany = new HashMap<>();
            List<Company> allCompanies = Company.listAll();
            for (Company c : allCompanies) {
                numberOfConsultantsPerCompany.put(c, 0);
                employeeAvailabilityPerMonthList.stream()
                        .filter(e -> LocalDate.of(e.getYear(), e.getMonth(), 1).equals(finalDate) &&
                                !e.getStatus().equals(StatusType.TERMINATED) &&
                                !e.getStatus().equals(StatusType.NON_PAY_LEAVE) &&
                                e.getCompany()!=null &&
                                e.getCompany().equals(c) &&
                                e.getConsultantType().equals(ConsultantType.CONSULTANT)).forEach(employeeDataPerMonth -> {
                            salarySum.addAndGet(employeeDataPerMonth.getAvgSalary().intValue());
                            totalNumberOfConsultants.getAndIncrement();
                            numberOfConsultantsPerCompany.merge(employeeDataPerMonth.getCompany(), 1, Integer::sum);
                        });
            }

            // Go through each account type and sum up the expenses. Include all company account and those which are shared
            AccountingAccount.<AccountingAccount>list("accountingCategory", category).stream().filter(a -> (a.isShared() || a.getCompany().equals(company))).forEach(accountingAccount -> {
                // Calculate the raw sum of expenses for the given account type and month. Only include which are shared or the correct company
                double partialSum = financeDetails.stream()
                        .filter(fd -> fd.getExpensedate().withDayOfMonth(1).equals(finalDate.withDayOfMonth(1)) &&
                                fd.getAccountnumber() == accountingAccount.getAccountCode() &&
                                (accountingAccount.isShared() || accountingAccount.getCompany().equals(company)))
                        .mapToDouble(FinanceDetails::getAmount)
                        .sum();

                if(accountingAccount.isSalary()) {
                    partialSum -= salarySum.get();
                }

                if(accountingAccount.isShared() && totalNumberOfConsultants.get() > 0) {
                    partialSum = (partialSum / totalNumberOfConsultants.get()) * numberOfConsultantsPerCompany.get(company);
                }

                if(accountingAccount.isSalary()) {
                    partialSum += salarySum.get();
                }

                sum.addAndGet((int) partialSum);
            });
            date = date.plusMonths(1);
        }
        return null; //TODO replace this stub to something useful
    }

    @GET
    @Path("/receipts/{uuid}")
    public Expense findByUuid(@PathParam("uuid") String uuid) {
        return expenseAPI.findByUuid(uuid);
    }

    @GET
    @Path("/receipts/file/{uuid}")
    public ExpenseFile getFileById(@PathParam("uuid") String uuid) {
        return expenseAPI.getFileById(uuid);
    }

    @GET
    @Path("/receipts/user/{useruuid}")
    public List<Expense> findByUser(@PathParam("useruuid") String useruuid, @QueryParam("limit") Optional<String> limit, @QueryParam("page") Optional<String> page) {
        if(limit.isPresent() && page.isPresent())
            return expenseAPI.findByUser(useruuid, limit.get(), page.get());
        else
            return expenseAPI.findByUser(useruuid);
    }

    @GET
    @Path("/receipts/project/{projectuuid}/search/period")
    public List<Expense> findByProjectAndPeriod(@PathParam("projectuuid") String projectuuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return expenseAPI.findByProjectAndPeriod(projectuuid, fromdate, todate);
    }

    @GET
    @Path("/receipts/user/{useruuid}/search/period")
    public List<Expense> findByUserAndPeriod(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return expenseAPI.findByUserAndPeriod(useruuid, fromdate, todate);
    }

    @GET
    @Path("/receipts/search/period")
    public List<Expense> findByPeriod(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return expenseAPI.findByPeriod(fromdate, todate);
    }

    @POST
    @Path("/receipts")
    public void save(Expense expense) throws IOException, InterruptedException {
        if(expense.getUseruuid().equals("173ee0b6-4ee5-11e7-b114-b2f933d5fe66")) return;
        expenseAPI.saveExpense(expense);
    }

    @PUT
    @Path("/receipts/{uuid}")
    public void updateOne(@PathParam("uuid") String uuid, Expense expense) {
        expenseAPI.updateOne(uuid, expense);
    }

    @DELETE
    @Path("/receipts/{uuid}")
    public void delete(@PathParam("uuid") String uuid) {
        expenseAPI.delete(uuid);
    }

    // UserAccount Resource

    @GET
    @Path("/user-accounts/{useruuid}")
    public UserAccount getUserAccountByUser(@PathParam("useruuid") String useruuid) {
        return userAccountAPI.getAccountByUser(useruuid);
    }

    @GET
    @Path("/user-accounts/search/findByAccountNumber")
    public UserAccount getAccount(@QueryParam("companyuuid") String companyuuid, @QueryParam("account") int account) throws IOException {
        if(account<=0) return new UserAccount(0, "", "No account found");
        return userAccountAPI.getAccount(companyuuid, account);
    }

    @POST
    @Path("/user-accounts")
    public void saveUserAccount(UserAccount userAccount) {
        userAccountAPI.saveAccount(userAccount);
    }

    @PUT
    @Path("/user-accounts/{useruuid}")
    public void updateUserAccount(@PathParam("useruuid") String useruuid, UserAccount userAccount) {
        userAccountAPI.updateAccount(useruuid, userAccount);
    }

    // AccountPlan Resource
/*
    @GET
    @Path("/expense-accounts/{account_no}")
    public ExpenseAccount findExpenseAccountByAccountNo(@PathParam("account_no") String account_no) {
        return accountPlanAPI.findAccountByAccountNo(account_no);
    }

    @POST
    @Path("/expense-accounts")
    @Transactional
    public void saveExpenseAccount(@Valid ExpenseAccount expenseAccount) {
        accountPlanAPI.saveExpenseAccount(expenseAccount);
    }

    @PUT
    @Path("/expense-accounts/{account-no}")
    @Transactional
    public void updateExpenseAccount(@PathParam("account-no") String account_no, ExpenseAccount expenseAccount) {
        accountPlanAPI.updateExpenseAccount(account_no, expenseAccount);
    }

    @GET
    @Path("/account-categories")
    public List<ExpenseCategory> findAllExpenseCategories() {
        return accountPlanAPI.findAll();
    }

    @GET
    @Path("/expense-categories/{uuid}")
    public ExpenseCategory findExpenseCategoryByUuid(@PathParam("uuid") String uuid) {
        return accountPlanAPI.findCategoryByUuid(uuid);
    }

    @GET
    @Path("/account-categories/active")
    public List<ExpenseCategory> findAllActiveExpenseCategories() {
        return accountPlanAPI.findAllActive();
    }

    @GET
    @Path("/account-categories/inactive")
    public List<ExpenseCategory> findAllInactiveExpenseCategories() {
        return accountPlanAPI.findAllInactive();
    }

    @POST
    @Path("/expense-categories")
    @Transactional
    public void saveExpenseCategory(@Valid ExpenseCategory expenseCategory) {
        accountPlanAPI.saveExpenseCategory(expenseCategory);
    }

    @PUT
    @Path("/expense-categories/{uuid}")
    @Transactional
    public void updateExpenseCategory(@PathParam("uuid") String uuid, ExpenseCategory expenseCategory) {
        accountPlanAPI.updateExpenseCategory(uuid, expenseCategory);
    }

 */
}

