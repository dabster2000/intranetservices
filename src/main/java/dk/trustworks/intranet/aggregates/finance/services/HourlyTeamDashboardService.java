package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.HourlyOverviewDTO;
import dk.trustworks.intranet.aggregates.finance.dto.HourlyOverviewDTO.ClientHours;
import dk.trustworks.intranet.aggregates.finance.dto.HourlyOverviewDTO.JkProfitTotals;
import dk.trustworks.intranet.aggregates.finance.dto.HourlyOverviewDTO.PlanCoverage;
import dk.trustworks.intranet.aggregates.finance.dto.NameRef;
import dk.trustworks.intranet.aggregates.finance.dto.TeamCapacityDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamCapacityDTO.MemberCapacity;
import dk.trustworks.intranet.aggregates.finance.dto.TeamCapacityDTO.WeekCell;
import dk.trustworks.intranet.aggregates.finance.dto.TeamJkProfitDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamJkProfitDTO.JuniorProfit;
import dk.trustworks.intranet.aggregates.finance.dto.TeamMemberAssignmentsDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamMemberAssignmentsDTO.ContractLine;
import dk.trustworks.intranet.aggregates.finance.dto.TeamMemberAssignmentsDTO.InternalLine;
import dk.trustworks.intranet.aggregates.finance.dto.TeamMemberAssignmentsDTO.MonthCapacity;
import dk.trustworks.intranet.aggregates.finance.dto.TeamOverviewDTO.TeamAttentionItemDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamPricingModelDistributionDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamPricingModelDistributionDTO.ModelBucket;
import dk.trustworks.intranet.aggregates.finance.dto.TeamRegisteredVsInvoicedDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamRegisteredVsInvoicedDTO.ClientRow;
import dk.trustworks.intranet.aggregates.finance.dto.TeamRegisteredVsInvoicedDTO.MonthCell;
import dk.trustworks.intranet.aggregates.finance.dto.TeamSickHoursDTO;
import dk.trustworks.intranet.aggregates.internalassignment.model.InternalAssignment;
import dk.trustworks.intranet.aggregates.userprofile.dto.UserProfileExtensionDTO;
import dk.trustworks.intranet.aggregates.userprofile.model.UserProfileExtension;
import dk.trustworks.intranet.contracts.dto.ZeroRateWatchlistDTO;
import dk.trustworks.intranet.contracts.model.PricingModelDefinition;
import dk.trustworks.intranet.contracts.services.ZeroRateWatchlistService;
import dk.trustworks.intranet.utils.DateUtils;
import dk.trustworks.intranet.utils.TwConstants;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper.FiscalYearRange;
import static dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper.toMonthKey;

/**
 * The hourly-kind card set of the Team Dashboard (JK Team 2.0 WP6 §4.6.0 tab matrix,
 * §4.6.2, §4.6.4). Everything here is built on declared hours (WP1), approved internal
 * demand (WP3), the client-hours discriminator (§4.4.0: internal client, paid, declared
 * 0 kr) and the HOURLY branch of the salary fact. None of the CONSULTANT-only queries in
 * {@link TeamDashboardService} are widened — they keep meaning what they mean.
 *
 * <p>Read-model only. Every method takes the member population from the caller, which has
 * already validated team access.
 */
@JBossLog
@ApplicationScoped
public class HourlyTeamDashboardService {

    /** D7: "11 of 17 juniors have declared the next 4 weeks". */
    public static final int COVERAGE_HORIZON_WEEKS = 4;
    public static final int DEFAULT_CAPACITY_WEEKS = 12;
    public static final int MAX_CAPACITY_WEEKS = 26;
    public static final int STEP_UP_WINDOW_DAYS = 30;
    public static final int GRADUATION_WINDOW_MONTHS = 3;
    static final double FULL_WEEK_HOURS = 37.0;
    /** Salary fact HOURLY rows are øre-scaled; NORMAL rows are DKK. */
    static final double OERE_PER_KRONE = 100.0;

    @Inject
    EntityManager em;

    @Inject
    ZeroRateWatchlistService zeroRateWatchlistService;

    // ── overview block ────────────────────────────────────────────────────

    public HourlyOverviewDTO buildHourlyOverview(Set<String> memberUuids,
                                                 Map<String, UserProfileExtension> profiles,
                                                 FiscalYearRange fy,
                                                 LocalDate effectiveEnd,
                                                 LocalDate today) {
        int bachelor = 0;
        int kandidat = 0;
        int unknown = 0;
        for (String uuid : memberUuids) {
            UserProfileExtension p = profiles.get(uuid);
            String level = p == null ? null : p.getStudyLevel();
            if (UserProfileExtension.STUDY_LEVEL_BACHELOR.equals(level)) bachelor++;
            else if (UserProfileExtension.STUDY_LEVEL_KANDIDAT.equals(level)) kandidat++;
            else unknown++;
        }

        Map<String, NameRef> names = names(memberUuids);
        PlanCoverage coverage = planCoverage(memberUuids, names, today, COVERAGE_HORIZON_WEEKS);

        YearMonth month = YearMonth.from(effectiveEnd);
        Map<String, WorkSplit> monthSplit = workSplit(memberUuids, month.atDay(1), month.atEndOfMonth());
        WorkSplit monthTotal = WorkSplit.sum(monthSplit.values());
        ClientHours clientHours = new ClientHours(toMonthKey(effectiveEnd),
                monthTotal.paidHours, monthTotal.zeroRateHours, monthTotal.internalHours);

        JkProfitTotals jkMonth = jkProfitTotals("month", memberUuids, month.atDay(1), month.atEndOfMonth());
        JkProfitTotals jkFy = jkProfitTotals("fy", memberUuids, fy.start(), effectiveEnd);

        return new HourlyOverviewDTO(bachelor, kandidat, unknown, coverage, clientHours, jkMonth, jkFy);
    }

    /** Attention items for an hourly team: no plan, step-up soon, awaiting approval, graduating soon. */
    public List<TeamAttentionItemDTO> hourlyAttentionItems(Set<String> memberUuids,
                                                           Map<String, UserProfileExtension> profiles,
                                                           PlanCoverage coverage,
                                                           LocalDate today) {
        List<TeamAttentionItemDTO> items = new ArrayList<>();
        for (NameRef missing : coverage.missing()) {
            items.add(new TeamAttentionItemDTO("NO_PLAN", "WARNING", missing.userId(), missing.firstname(),
                    missing.lastname(), "No plan: undeclared working days in the next "
                    + coverage.horizonWeeks() + " weeks"));
        }
        for (ZeroRateWatchlistDTO row : zeroRateWatchlistService.watchlist(today, STEP_UP_WINDOW_DAYS, false)) {
            if (!memberUuids.contains(row.juniorUuid())) continue;
            String[] name = splitName(row.juniorName());
            String message = row.overdue()
                    ? "Step-up overdue at " + row.clientName() + " (review date " + row.rateReviewDate() + ")"
                    : "Step-up in " + row.daysRemaining() + " days at " + row.clientName();
            items.add(new TeamAttentionItemDTO("STEP_UP", row.overdue() ? "CRITICAL" : "WARNING",
                    row.juniorUuid(), name[0], name[1], message));
        }
        Map<String, NameRef> names = names(memberUuids);
        for (InternalAssignment pending : InternalAssignment.findPendingFor(memberUuids)) {
            NameRef n = names.get(pending.getUseruuid());
            items.add(new TeamAttentionItemDTO("PENDING_APPROVAL", "INFO", pending.getUseruuid(),
                    n == null ? null : n.firstname(), n == null ? null : n.lastname(),
                    "Internal assignment awaiting approval: " + pending.getTitle()));
        }
        LocalDate graduationCutoff = today.plusMonths(GRADUATION_WINDOW_MONTHS);
        for (String uuid : memberUuids) {
            UserProfileExtension p = profiles.get(uuid);
            if (p == null || p.getExpectedGraduation() == null) continue;
            if (p.getExpectedGraduation().isBefore(today) || p.getExpectedGraduation().isAfter(graduationCutoff)) continue;
            NameRef n = names.get(uuid);
            items.add(new TeamAttentionItemDTO("GRADUATING", "INFO", uuid,
                    n == null ? null : n.firstname(), n == null ? null : n.lastname(),
                    "Expected graduation " + p.getExpectedGraduation()));
        }
        return items;
    }

    // ── plan coverage (D7) ────────────────────────────────────────────────

    public PlanCoverage planCoverage(Set<String> memberUuids, Map<String, NameRef> names, LocalDate today, int weeks) {
        LocalDate from = today;
        LocalDate to = today.plusWeeks(weeks).minusDays(1);
        List<LocalDate> workingDays = workingDays(from, to);
        if (memberUuids.isEmpty() || workingDays.isEmpty()) {
            return new PlanCoverage(0, memberUuids.size(), weeks, from, to, List.of());
        }
        Map<String, Set<LocalDate>> declared = declaredDays(memberUuids, from, to);
        List<NameRef> missing = new ArrayList<>();
        int covered = 0;
        for (String uuid : memberUuids) {
            Set<LocalDate> days = declared.getOrDefault(uuid, Set.of());
            boolean complete = days.containsAll(workingDays);
            if (complete) covered++;
            else missing.add(names.getOrDefault(uuid, new NameRef(uuid, null, null)));
        }
        missing.sort((a, b) -> compareNames(a, b));
        return new PlanCoverage(covered, memberUuids.size(), weeks, from, to, missing);
    }

    // ── capacity tab ──────────────────────────────────────────────────────

    public TeamCapacityDTO getCapacity(Set<String> memberUuids, LocalDate fromDate, int weeks, LocalDate today) {
        int span = Math.min(Math.max(weeks, 1), MAX_CAPACITY_WEEKS);
        LocalDate from = fromDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate to = from.plusWeeks(span).minusDays(1);
        List<LocalDate> weekStarts = new ArrayList<>();
        for (int i = 0; i < span; i++) weekStarts.add(from.plusWeeks(i));

        Map<String, NameRef> names = names(memberUuids);
        PlanCoverage coverage = planCoverage(memberUuids, names, today, COVERAGE_HORIZON_WEEKS);
        if (memberUuids.isEmpty()) {
            return new TeamCapacityDTO(from, to, weekStarts, coverage, List.of());
        }

        Map<String, Map<LocalDate, Double>> declaredHours = declaredHoursByDay(memberUuids, from, to);
        Map<String, Map<LocalDate, WorkSplit>> work = workSplitByDay(memberUuids, from, to);
        Map<String, Map<LocalDate, Double>> contractBudget = budgetByDay(
                "SELECT bd.useruuid AS user_id, bd.document_date AS day, SUM(bd.budgetHours) AS hours "
                        + "FROM fact_budget_day bd WHERE bd.useruuid IN (:memberUuids) "
                        + "AND bd.document_date >= :fromDate AND bd.document_date <= :toDate "
                        + "GROUP BY bd.useruuid, bd.document_date", memberUuids, from, to);
        Map<String, Map<LocalDate, Double>> internalBudget = budgetByDay(
                "SELECT ib.useruuid AS user_id, ib.document_date AS day, SUM(ib.budget_hours) AS hours "
                        + "FROM fact_internal_budget_day ib WHERE ib.useruuid IN (:memberUuids) "
                        + "AND ib.document_date >= :fromDate AND ib.document_date <= :toDate "
                        + "GROUP BY ib.useruuid, ib.document_date", memberUuids, from, to);

        List<MemberCapacity> members = new ArrayList<>();
        List<String> ordered = new ArrayList<>(memberUuids);
        ordered.sort((a, b) -> compareNames(names.get(a), names.get(b)));
        for (String uuid : ordered) {
            NameRef n = names.getOrDefault(uuid, new NameRef(uuid, null, null));
            List<WeekCell> cells = new ArrayList<>();
            for (LocalDate weekStart : weekStarts) {
                cells.add(weekCell(weekStart,
                        declaredHours.getOrDefault(uuid, Map.of()),
                        work.getOrDefault(uuid, Map.of()),
                        contractBudget.getOrDefault(uuid, Map.of()),
                        internalBudget.getOrDefault(uuid, Map.of())));
            }
            members.add(new MemberCapacity(uuid, n.firstname(), n.lastname(), cells));
        }
        return new TeamCapacityDTO(from, to, weekStarts, coverage, members);
    }

    /** Package-private for the test: one week's cell from per-day maps. */
    static WeekCell weekCell(LocalDate weekStart,
                             Map<LocalDate, Double> declaredByDay,
                             Map<LocalDate, WorkSplit> workByDay,
                             Map<LocalDate, Double> contractBudgetByDay,
                             Map<LocalDate, Double> internalBudgetByDay) {
        double declared = 0;
        boolean anyDeclared = false;
        int undeclaredWorkingDays = 0;
        WorkSplit work = new WorkSplit();
        double contractBudget = 0;
        double internalBudget = 0;
        for (int i = 0; i < 7; i++) {
            LocalDate day = weekStart.plusDays(i);
            Double d = declaredByDay.get(day);
            if (d != null) {
                declared += d;
                anyDeclared = true;
            } else if (DateUtils.isWorkday(day)) {
                undeclaredWorkingDays++;
            }
            WorkSplit w = workByDay.get(day);
            if (w != null) work.add(w);
            contractBudget += contractBudgetByDay.getOrDefault(day, 0.0);
            internalBudget += internalBudgetByDay.getOrDefault(day, 0.0);
        }
        Double declaredHours = anyDeclared ? declared : null;
        double unplanned = anyDeclared ? Math.max(declared - contractBudget - internalBudget, 0.0) : 0.0;
        return new WeekCell(weekStart, declaredHours, undeclaredWorkingDays,
                work.total(), work.paidHours, work.zeroRateHours, work.internalHours, work.otherHours,
                contractBudget, internalBudget, unplanned);
    }

    // ── JK Profit ─────────────────────────────────────────────────────────

    public TeamJkProfitDTO getJkProfit(Set<String> memberUuids, FiscalYearRange fy, LocalDate effectiveEnd) {
        YearMonth month = YearMonth.from(effectiveEnd);
        Map<String, NameRef> names = names(memberUuids);
        Map<String, JkProfitTotals> perJuniorMonth = jkProfitPerJunior("month", memberUuids, month.atDay(1), month.atEndOfMonth());
        Map<String, JkProfitTotals> perJuniorFy = jkProfitPerJunior("fy", memberUuids, fy.start(), effectiveEnd);

        List<JuniorProfit> juniors = new ArrayList<>();
        List<String> ordered = new ArrayList<>(memberUuids);
        ordered.sort((a, b) -> compareNames(names.get(a), names.get(b)));
        for (String uuid : ordered) {
            NameRef n = names.getOrDefault(uuid, new NameRef(uuid, null, null));
            juniors.add(new JuniorProfit(uuid, n.firstname(), n.lastname(),
                    perJuniorMonth.getOrDefault(uuid, JkProfitTotals.of("month", 0, 0, 0, 0, 0)),
                    perJuniorFy.getOrDefault(uuid, JkProfitTotals.of("fy", 0, 0, 0, 0, 0))));
        }
        return new TeamJkProfitDTO(toMonthKey(effectiveEnd), fy.fiscalYear(),
                sumTotals("month", perJuniorMonth.values()), sumTotals("fy", perJuniorFy.values()), juniors);
    }

    private JkProfitTotals jkProfitTotals(String label, Set<String> memberUuids, LocalDate from, LocalDate to) {
        return sumTotals(label, jkProfitPerJunior(label, memberUuids, from, to).values());
    }

    private static JkProfitTotals sumTotals(String label, Collection<JkProfitTotals> values) {
        double revenue = 0, cost = 0, paid = 0, zero = 0, given = 0;
        for (JkProfitTotals t : values) {
            revenue += t.revenue();
            cost += t.cost();
            paid += t.paidHours();
            zero += t.zeroRateHours();
            given += t.valueGivenAway();
        }
        return JkProfitTotals.of(label, revenue, cost, paid, zero, given);
    }

    /**
     * Revenue = paid client hours × rate (work_full, external clients only); 0 kr hours and
     * value given away come from declared zero-rate lines (§4.4.1); cost = the salary fact,
     * HOURLY rows normalised from øre. A junior with no rows is simply absent — the caller
     * renders a zero row, never falls through to a heuristic.
     */
    private Map<String, JkProfitTotals> jkProfitPerJunior(String label, Set<String> memberUuids, LocalDate from, LocalDate to) {
        if (memberUuids.isEmpty() || to.isBefore(from)) return Map.of();
        Map<String, WorkSplit> work = workSplit(memberUuids, from, to);

        @SuppressWarnings("unchecked")
        List<Tuple> salaryRows = em.createNativeQuery("""
                SELECT fsm.useruuid AS user_id,
                       SUM(CASE WHEN fsm.salary_type = 'HOURLY' THEN fsm.salary_sum / :oere ELSE fsm.salary_sum END) AS cost
                FROM fact_salary_monthly fsm
                WHERE fsm.useruuid IN (:memberUuids)
                  AND fsm.month_key >= :fromKey AND fsm.month_key <= :toKey
                GROUP BY fsm.useruuid
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("fromKey", toMonthKey(from))
                .setParameter("toKey", toMonthKey(to))
                .setParameter("oere", OERE_PER_KRONE)
                .getResultList();
        Map<String, Double> costByUser = new HashMap<>();
        for (Tuple r : salaryRows) costByUser.put((String) r.get("user_id"), num(r.get("cost")));

        Map<String, JkProfitTotals> result = new LinkedHashMap<>();
        for (String uuid : memberUuids) {
            WorkSplit w = work.getOrDefault(uuid, new WorkSplit());
            double cost = costByUser.getOrDefault(uuid, 0.0);
            if (w.isEmpty() && cost == 0.0) continue;
            result.put(uuid, JkProfitTotals.of(label, w.revenue, cost, w.paidHours, w.zeroRateHours, w.valueGivenAway));
        }
        return result;
    }

    // ── registered vs invoiced (roster-restricted ClientStatus) ──────────

    public TeamRegisteredVsInvoicedDTO getRegisteredVsInvoiced(Set<String> memberUuids, YearMonth end) {
        List<String> months = new ArrayList<>();
        for (int i = 11; i >= 0; i--) {
            YearMonth ym = end.minusMonths(i);
            months.add(String.format("%04d%02d", ym.getYear(), ym.getMonthValue()));
        }
        if (memberUuids.isEmpty()) {
            return new TeamRegisteredVsInvoicedDTO(months, List.of(), 0, 0);
        }
        LocalDate fromDate = end.minusMonths(11).atDay(1);
        LocalDate toExclusive = end.plusMonths(1).atDay(1);
        int fromPeriod = end.minusMonths(11).getYear() * 100 + end.minusMonths(11).getMonthValue();
        int toPeriod = end.getYear() * 100 + end.getMonthValue();

        Map<String, Map<String, Double>> expected = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Tuple> expectedRows = em.createNativeQuery("""
                SELECT w.clientuuid AS client_id,
                       DATE_FORMAT(w.registered, '%Y%m') AS month_key,
                       SUM(IFNULL(w.rate, 0) * w.workduration) AS expected
                FROM work_full w
                WHERE w.useruuid IN (:memberUuids)
                  AND w.rate > 0
                  AND w.clientuuid IS NOT NULL
                  AND w.clientuuid NOT IN (:internalClients)
                  AND w.registered >= :fromDate AND w.registered < :toDate
                GROUP BY w.clientuuid, DATE_FORMAT(w.registered, '%Y%m')
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("internalClients", TwConstants.INTERNAL_CLIENT_UUIDS)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toExclusive)
                .getResultList();
        for (Tuple r : expectedRows) {
            expected.computeIfAbsent((String) r.get("client_id"), k -> new HashMap<>())
                    .put((String) r.get("month_key"), num(r.get("expected")));
        }

        // Same invoice filters as ClientStatusService (status, type, external credit notes
        // only, gross basis, billing period), restricted through invoiceitems.consultantuuid.
        Map<String, Map<String, Double>> invoiced = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Tuple> invoicedRows = em.createNativeQuery("""
                SELECT COALESCE(p.clientuuid, i.billing_client_uuid) AS client_id,
                       CONCAT(i.year, LPAD(i.month, 2, '0')) AS month_key,
                       SUM(CASE WHEN i.type = 'CREDIT_NOTE' THEN -1 ELSE 1 END * (ii.hours * ii.rate)) AS invoiced
                FROM invoices i
                LEFT JOIN project p ON p.uuid = i.projectuuid
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE ii.consultantuuid IN (:memberUuids)
                  AND i.status IN ('CREATED', 'QUEUED')
                  AND i.type IN ('INVOICE', 'PHANTOM', 'CREDIT_NOTE')
                  AND (i.type <> 'CREDIT_NOTE' OR i.debtor_companyuuid IS NULL)
                  AND COALESCE(p.clientuuid, i.billing_client_uuid) IS NOT NULL
                  AND COALESCE(p.clientuuid, i.billing_client_uuid) NOT IN (:internalClients)
                  AND (i.year * 100 + i.month) >= :fromPeriod
                  AND (i.year * 100 + i.month) <= :toPeriod
                GROUP BY COALESCE(p.clientuuid, i.billing_client_uuid), CONCAT(i.year, LPAD(i.month, 2, '0'))
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("internalClients", TwConstants.INTERNAL_CLIENT_UUIDS)
                .setParameter("fromPeriod", fromPeriod)
                .setParameter("toPeriod", toPeriod)
                .getResultList();
        for (Tuple r : invoicedRows) {
            invoiced.computeIfAbsent((String) r.get("client_id"), k -> new HashMap<>())
                    .put((String) r.get("month_key"), num(r.get("invoiced")));
        }

        Set<String> clientUuids = new java.util.HashSet<>(expected.keySet());
        clientUuids.addAll(invoiced.keySet());
        Map<String, String> clientNames = clientNames(clientUuids);

        List<ClientRow> rows = new ArrayList<>();
        double totalExpected = 0;
        double totalInvoiced = 0;
        for (String clientUuid : clientUuids) {
            List<MonthCell> cells = new ArrayList<>();
            double e = 0;
            double inv = 0;
            for (String mk : months) {
                double ce = expected.getOrDefault(clientUuid, Map.of()).getOrDefault(mk, 0.0);
                double ci = invoiced.getOrDefault(clientUuid, Map.of()).getOrDefault(mk, 0.0);
                cells.add(new MonthCell(mk, ce, ci));
                e += ce;
                inv += ci;
            }
            totalExpected += e;
            totalInvoiced += inv;
            rows.add(new ClientRow(clientUuid, clientNames.getOrDefault(clientUuid, clientUuid), cells, e, inv, e - inv));
        }
        rows.sort((a, b) -> Double.compare(b.gap(), a.gap()));
        return new TeamRegisteredVsInvoicedDTO(months, rows, totalExpected, totalInvoiced);
    }

    // ── pricing-model distribution ───────────────────────────────────────

    public TeamPricingModelDistributionDTO getPricingModelDistribution(Set<String> memberUuids, LocalDate from, LocalDate to) {
        Map<String, NameRef> names = names(memberUuids);
        Map<String, String> modelNames = new LinkedHashMap<>();
        for (PricingModelDefinition m : PricingModelDefinition.findActive()) modelNames.put(m.getCode(), m.getName());

        Map<String, Set<String>> juniorsByModel = new LinkedHashMap<>();
        for (String code : modelNames.keySet()) juniorsByModel.put(code, new java.util.TreeSet<>());
        juniorsByModel.put("NONE", new java.util.TreeSet<>());
        Set<String> withLines = new java.util.HashSet<>();

        if (!memberUuids.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Tuple> rows = em.createNativeQuery("""
                    SELECT DISTINCT cc.useruuid, cc.pricing_model_code
                    FROM contract_consultants cc
                    JOIN contracts c ON c.uuid = cc.contractuuid
                    WHERE cc.useruuid IN (:memberUuids)
                      AND cc.activefrom <= :to AND cc.activeto >= :from
                      AND c.status IN ('SIGNED', 'TIME')
                    """, Tuple.class)
                    .setParameter("memberUuids", memberUuids)
                    .setParameter("from", from)
                    .setParameter("to", to)
                    .getResultList();
            for (Tuple r : rows) {
                String uuid = (String) r.get("useruuid");
                String code = (String) r.get("pricing_model_code");
                withLines.add(uuid);
                String bucket = code != null && juniorsByModel.containsKey(code) ? code : "NONE";
                juniorsByModel.get(bucket).add(uuid);
            }
        }

        List<ModelBucket> buckets = new ArrayList<>();
        for (var e : juniorsByModel.entrySet()) {
            List<NameRef> juniors = e.getValue().stream()
                    .map(u -> names.getOrDefault(u, new NameRef(u, null, null)))
                    .sorted(HourlyTeamDashboardService::compareNames)
                    .toList();
            String name = "NONE".equals(e.getKey()) ? "No model" : modelNames.get(e.getKey());
            buckets.add(new ModelBucket(e.getKey(), name, juniors.size(), juniors));
        }
        List<NameRef> without = memberUuids.stream()
                .filter(u -> !withLines.contains(u))
                .map(u -> names.getOrDefault(u, new NameRef(u, null, null)))
                .sorted(HourlyTeamDashboardService::compareNames)
                .toList();
        return new TeamPricingModelDistributionDTO(buckets, withLines.size(), without.size(), without);
    }

    // ── zero-rate watchlist, roster-restricted (WP4b §4.4.3) ─────────────

    public List<ZeroRateWatchlistDTO> watchlistFor(Set<String> memberUuids, LocalDate today, int withinDays) {
        if (memberUuids.isEmpty()) return List.of();
        return zeroRateWatchlistService.watchlist(today, withinDays, false).stream()
                .filter(row -> memberUuids.contains(row.juniorUuid()))
                .toList();
    }

    // ── sick hours ────────────────────────────────────────────────────────

    public List<TeamSickHoursDTO> getSickHours(Set<String> memberUuids, LocalDate today) {
        if (memberUuids.isEmpty()) return List.of();
        Map<String, NameRef> names = names(memberUuids);
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT fud.useruuid AS user_id,
                       SUM(COALESCE(fud.sick_hours, 0)) AS h365,
                       SUM(CASE WHEN fud.document_date > :d90 THEN COALESCE(fud.sick_hours, 0) ELSE 0 END) AS h90,
                       SUM(CASE WHEN COALESCE(fud.sick_hours, 0) > 0 THEN 1 ELSE 0 END) AS days365
                FROM fact_user_day fud
                WHERE fud.useruuid IN (:memberUuids)
                  AND fud.document_date > :d365 AND fud.document_date <= :today
                GROUP BY fud.useruuid
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("d365", today.minusDays(365))
                .setParameter("d90", today.minusDays(90))
                .setParameter("today", today)
                .getResultList();
        Map<String, Tuple> byUser = new HashMap<>();
        for (Tuple r : rows) byUser.put((String) r.get("user_id"), r);

        List<TeamSickHoursDTO> result = new ArrayList<>();
        List<String> ordered = new ArrayList<>(memberUuids);
        ordered.sort((a, b) -> compareNames(names.get(a), names.get(b)));
        for (String uuid : ordered) {
            NameRef n = names.getOrDefault(uuid, new NameRef(uuid, null, null));
            Tuple r = byUser.get(uuid);
            result.add(new TeamSickHoursDTO(uuid, n.firstname(), n.lastname(),
                    r == null ? 0 : num(r.get("h365")),
                    r == null ? 0 : num(r.get("h90")),
                    r == null ? 0 : (int) num(r.get("days365"))));
        }
        return result;
    }

    // ── capacity & assignments card (one member) ─────────────────────────

    public TeamMemberAssignmentsDTO getMemberAssignments(String useruuid, YearMonth fromMonth, int months) {
        int span = Math.min(Math.max(months, 1), 24);
        LocalDate from = fromMonth.atDay(1);
        LocalDate to = fromMonth.plusMonths(span - 1L).atEndOfMonth();
        Set<String> one = Set.of(useruuid);

        @SuppressWarnings("unchecked")
        List<Tuple> lineRows = em.createNativeQuery("""
                SELECT cc.uuid, cc.contractuuid, c.name AS contract_name, cl.name AS client_name,
                       CONCAT(am.firstname, ' ', am.lastname) AS am_name,
                       cc.activefrom, cc.activeto, cc.rate, cc.hours, cc.pricing_model_code,
                       cc.zero_rate_reason, cc.rate_review_date, cc.list_rate, c.status, cc.name AS line_name
                FROM contract_consultants cc
                JOIN contracts c ON c.uuid = cc.contractuuid
                LEFT JOIN client cl ON cl.uuid = c.clientuuid
                LEFT JOIN user am ON am.uuid = c.leaduuid
                WHERE cc.useruuid = :useruuid
                  AND cc.activefrom <= :to AND cc.activeto >= :from
                  AND c.status NOT IN ('INACTIVE', 'CLOSED')
                ORDER BY cc.activefrom, c.name
                """, Tuple.class)
                .setParameter("useruuid", useruuid)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        List<ContractLine> contracts = new ArrayList<>();
        for (Tuple r : lineRows) {
            double rate = num(r.get("rate"));
            double hours = num(r.get("hours"));
            Object listRate = r.get("list_rate");
            contracts.add(new ContractLine(
                    (String) r.get("uuid"), (String) r.get("contractuuid"), (String) r.get("contract_name"),
                    (String) r.get("client_name"), (String) r.get("am_name"),
                    toLocalDate(r.get("activefrom")), toLocalDate(r.get("activeto")),
                    rate, hours, (int) Math.round(hours / FULL_WEEK_HOURS * 100.0),
                    (String) r.get("pricing_model_code"),
                    rate == 0.0, (String) r.get("zero_rate_reason"), toLocalDate(r.get("rate_review_date")),
                    listRate == null ? null : ((Number) listRate).doubleValue(),
                    (String) r.get("status"), (String) r.get("line_name")));
        }

        @SuppressWarnings("unchecked")
        List<Tuple> internalRows = em.createNativeQuery("""
                SELECT ia.uuid, ia.title, ia.active_from, ia.active_to, ia.hours_per_week, ia.strategic, ia.status,
                       CONCAT(sp.firstname, ' ', sp.lastname) AS sponsor_name
                FROM internal_assignment ia
                LEFT JOIN user sp ON sp.uuid = ia.sponsor_useruuid
                WHERE ia.useruuid = :useruuid
                  AND ia.status <> 'REJECTED'
                  AND ia.active_from <= :to AND ia.active_to >= :from
                ORDER BY ia.active_from
                """, Tuple.class)
                .setParameter("useruuid", useruuid)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        List<InternalLine> internal = new ArrayList<>();
        for (Tuple r : internalRows) {
            Object strategic = r.get("strategic");
            boolean isStrategic = strategic instanceof Boolean b ? b : strategic instanceof Number n && n.intValue() != 0;
            internal.add(new InternalLine((String) r.get("uuid"), (String) r.get("title"), (String) r.get("sponsor_name"),
                    toLocalDate(r.get("active_from")), toLocalDate(r.get("active_to")),
                    num(r.get("hours_per_week")), isStrategic, (String) r.get("status")));
        }

        Map<LocalDate, Double> declared = declaredHoursByDay(one, from, to).getOrDefault(useruuid, Map.of());
        Map<LocalDate, WorkSplit> work = workSplitByDay(one, from, to).getOrDefault(useruuid, Map.of());
        Map<LocalDate, Double> contractBudget = budgetByDay(
                "SELECT bd.useruuid AS user_id, bd.document_date AS day, SUM(bd.budgetHours) AS hours "
                        + "FROM fact_budget_day bd WHERE bd.useruuid IN (:memberUuids) "
                        + "AND bd.document_date >= :fromDate AND bd.document_date <= :toDate "
                        + "GROUP BY bd.useruuid, bd.document_date", one, from, to).getOrDefault(useruuid, Map.of());
        Map<LocalDate, Double> internalBudget = budgetByDay(
                "SELECT ib.useruuid AS user_id, ib.document_date AS day, SUM(ib.budget_hours) AS hours "
                        + "FROM fact_internal_budget_day ib WHERE ib.useruuid IN (:memberUuids) "
                        + "AND ib.document_date >= :fromDate AND ib.document_date <= :toDate "
                        + "GROUP BY ib.useruuid, ib.document_date", one, from, to).getOrDefault(useruuid, Map.of());

        List<MonthCapacity> monthsOut = new ArrayList<>();
        for (int i = 0; i < span; i++) {
            YearMonth ym = fromMonth.plusMonths(i);
            double declaredSum = 0;
            boolean anyDeclared = false;
            int undeclared = 0;
            WorkSplit ws = new WorkSplit();
            double cb = 0;
            double ib = 0;
            for (LocalDate day = ym.atDay(1); !day.isAfter(ym.atEndOfMonth()); day = day.plusDays(1)) {
                Double d = declared.get(day);
                if (d != null) {
                    declaredSum += d;
                    anyDeclared = true;
                } else if (DateUtils.isWorkday(day)) {
                    undeclared++;
                }
                WorkSplit w = work.get(day);
                if (w != null) ws.add(w);
                cb += contractBudget.getOrDefault(day, 0.0);
                ib += internalBudget.getOrDefault(day, 0.0);
            }
            Double declaredHours = anyDeclared ? declaredSum : null;
            monthsOut.add(new MonthCapacity(String.format("%04d%02d", ym.getYear(), ym.getMonthValue()),
                    declaredHours, undeclared, ws.total(), ws.paidHours, ws.zeroRateHours, ws.internalHours,
                    cb, ib, anyDeclared ? ws.total() - declaredSum : null));
        }
        return new TeamMemberAssignmentsDTO(useruuid, from, to, contracts, internal, monthsOut);
    }

    // ── shared queries ────────────────────────────────────────────────────

    /** The client-hours discriminator (§4.4.0) applied to work_full, one bucket set per person. */
    static final class WorkSplit {
        double paidHours;
        double zeroRateHours;
        double internalHours;
        double otherHours;
        double revenue;
        double valueGivenAway;

        void add(WorkSplit o) {
            paidHours += o.paidHours;
            zeroRateHours += o.zeroRateHours;
            internalHours += o.internalHours;
            otherHours += o.otherHours;
            revenue += o.revenue;
            valueGivenAway += o.valueGivenAway;
        }

        double total() {
            return paidHours + zeroRateHours + internalHours + otherHours;
        }

        boolean isEmpty() {
            return total() == 0.0 && revenue == 0.0;
        }

        static WorkSplit sum(Collection<WorkSplit> values) {
            WorkSplit s = new WorkSplit();
            for (WorkSplit v : values) s.add(v);
            return s;
        }
    }

    private Map<String, WorkSplit> workSplit(Set<String> memberUuids, LocalDate from, LocalDate to) {
        Map<String, WorkSplit> out = new LinkedHashMap<>();
        for (var e : workSplitByDay(memberUuids, from, to).entrySet()) {
            out.put(e.getKey(), WorkSplit.sum(e.getValue().values()));
        }
        return out;
    }

    /**
     * Registered hours per person per day, split by the discriminator: internal client →
     * internal; rate &gt; 0 → paid; rate = 0 on a declared zero-rate line → 0 kr (carrying the
     * line's list value); anything else → other (undeclared 0 kr, no client).
     */
    private Map<String, Map<LocalDate, WorkSplit>> workSplitByDay(Set<String> memberUuids, LocalDate from, LocalDate to) {
        Map<String, Map<LocalDate, WorkSplit>> out = new LinkedHashMap<>();
        if (memberUuids.isEmpty()) return out;
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT w.useruuid AS user_id, w.registered AS day,
                       CASE WHEN w.clientuuid IN (:internalClients) THEN 'INTERNAL'
                            WHEN IFNULL(w.rate, 0) > 0 THEN 'PAID'
                            WHEN cc.zero_rate_reason IS NOT NULL THEN 'ZERO'
                            ELSE 'OTHER' END AS bucket,
                       SUM(w.workduration) AS hours,
                       SUM(CASE WHEN IFNULL(w.rate, 0) > 0 THEN w.workduration * w.rate ELSE 0 END) AS revenue,
                       SUM(CASE WHEN IFNULL(w.rate, 0) = 0 AND cc.list_rate IS NOT NULL
                                THEN w.workduration * cc.list_rate ELSE 0 END) AS given_away
                FROM work_full w
                LEFT JOIN contract_consultants cc
                       ON cc.contractuuid = w.contractuuid AND cc.useruuid = w.useruuid
                      AND w.registered >= cc.activefrom AND w.registered <= cc.activeto
                WHERE w.useruuid IN (:memberUuids)
                  AND w.registered >= :fromDate AND w.registered <= :toDate
                  AND w.workduration > 0
                GROUP BY w.useruuid, w.registered,
                         CASE WHEN w.clientuuid IN (:internalClients) THEN 'INTERNAL'
                              WHEN IFNULL(w.rate, 0) > 0 THEN 'PAID'
                              WHEN cc.zero_rate_reason IS NOT NULL THEN 'ZERO'
                              ELSE 'OTHER' END
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("internalClients", TwConstants.INTERNAL_CLIENT_UUIDS)
                .setParameter("fromDate", from)
                .setParameter("toDate", to)
                .getResultList();
        for (Tuple r : rows) {
            String uid = (String) r.get("user_id");
            LocalDate day = toLocalDate(r.get("day"));
            WorkSplit split = out.computeIfAbsent(uid, k -> new TreeMap<>()).computeIfAbsent(day, k -> new WorkSplit());
            double hours = num(r.get("hours"));
            switch ((String) r.get("bucket")) {
                case "INTERNAL" -> split.internalHours += hours;
                case "PAID" -> {
                    split.paidHours += hours;
                    split.revenue += num(r.get("revenue"));
                }
                case "ZERO" -> {
                    split.zeroRateHours += hours;
                    split.valueGivenAway += num(r.get("given_away"));
                }
                default -> split.otherHours += hours;
            }
        }
        return out;
    }

    private Map<String, Map<LocalDate, Double>> declaredHoursByDay(Set<String> memberUuids, LocalDate from, LocalDate to) {
        Map<String, Map<LocalDate, Double>> out = new LinkedHashMap<>();
        if (memberUuids.isEmpty()) return out;
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT uda.useruuid AS user_id, uda.day AS day, uda.hours AS hours
                FROM user_declared_availability uda
                WHERE uda.useruuid IN (:memberUuids)
                  AND uda.day >= :fromDate AND uda.day <= :toDate
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("fromDate", from)
                .setParameter("toDate", to)
                .getResultList();
        for (Tuple r : rows) {
            out.computeIfAbsent((String) r.get("user_id"), k -> new TreeMap<>())
                    .put(toLocalDate(r.get("day")), num(r.get("hours")));
        }
        return out;
    }

    private Map<String, Set<LocalDate>> declaredDays(Set<String> memberUuids, LocalDate from, LocalDate to) {
        Map<String, Set<LocalDate>> out = new HashMap<>();
        for (var e : declaredHoursByDay(memberUuids, from, to).entrySet()) {
            out.put(e.getKey(), e.getValue().keySet());
        }
        return out;
    }

    private Map<String, Map<LocalDate, Double>> budgetByDay(String sql, Set<String> memberUuids, LocalDate from, LocalDate to) {
        Map<String, Map<LocalDate, Double>> out = new LinkedHashMap<>();
        if (memberUuids.isEmpty()) return out;
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery(sql, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("fromDate", from)
                .setParameter("toDate", to)
                .getResultList();
        for (Tuple r : rows) {
            out.computeIfAbsent((String) r.get("user_id"), k -> new TreeMap<>())
                    .merge(toLocalDate(r.get("day")), num(r.get("hours")), Double::sum);
        }
        return out;
    }

    public Map<String, NameRef> names(Collection<String> memberUuids) {
        Map<String, NameRef> out = new HashMap<>();
        if (memberUuids == null || memberUuids.isEmpty()) return out;
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT u.uuid, u.firstname, u.lastname FROM user u WHERE u.uuid IN (:memberUuids)
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .getResultList();
        for (Tuple r : rows) {
            out.put((String) r.get("uuid"), new NameRef((String) r.get("uuid"), (String) r.get("firstname"), (String) r.get("lastname")));
        }
        return out;
    }

    private Map<String, String> clientNames(Collection<String> clientUuids) {
        Map<String, String> out = new HashMap<>();
        if (clientUuids == null || clientUuids.isEmpty()) return out;
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("SELECT c.uuid, c.name FROM client c WHERE c.uuid IN (:ids)", Tuple.class)
                .setParameter("ids", clientUuids)
                .getResultList();
        for (Tuple r : rows) out.put((String) r.get("uuid"), (String) r.get("name"));
        return out;
    }

    /** Working days in [from, to] — weekdays that are not Danish public holidays. */
    static List<LocalDate> workingDays(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (DateUtils.isWorkday(d)) days.add(d);
        }
        return days;
    }

    static int compareNames(NameRef a, NameRef b) {
        String la = a == null || a.lastname() == null ? "" : a.lastname();
        String lb = b == null || b.lastname() == null ? "" : b.lastname();
        int c = la.compareToIgnoreCase(lb);
        if (c != 0) return c;
        String fa = a == null || a.firstname() == null ? "" : a.firstname();
        String fb = b == null || b.firstname() == null ? "" : b.firstname();
        return fa.compareToIgnoreCase(fb);
    }

    private static String[] splitName(String full) {
        if (full == null || full.isBlank()) return new String[]{null, null};
        int i = full.lastIndexOf(' ');
        if (i < 0) return new String[]{full, null};
        return new String[]{full.substring(0, i), full.substring(i + 1)};
    }

    private static double num(Object value) {
        return value == null ? 0.0 : ((Number) value).doubleValue();
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate ld) return ld;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime().toLocalDate();
        return LocalDate.parse(value.toString().substring(0, 10));
    }
}
