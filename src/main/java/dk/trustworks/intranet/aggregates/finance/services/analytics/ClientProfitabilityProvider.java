package dk.trustworks.intranet.aggregates.finance.services.analytics;

import dk.trustworks.intranet.aggregates.finance.dto.ClientConsultantDetailDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ClientProfitabilityRowDTO;
import dk.trustworks.intranet.aggregates.finance.dto.OpexRow;
import dk.trustworks.intranet.aggregates.finance.services.DistributionAwareOpexProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.*;

@JBossLog
@ApplicationScoped
public class ClientProfitabilityProvider {
    private static final String INTERNAL_CLIENT_UUID = "d58bb00b-4474-4250-84eb-d8f77548ddac";

    @Inject
    EntityManager em;

    @Inject
    DistributionAwareOpexProvider distributionProvider;

    public List<ClientProfitabilityRowDTO> getClientProfitability(
            String fromKey, String toKey, Set<String> companyIds) {

        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();

        // 1. Per-client revenue + salary + external + expenses + consultant count
        Map<String, ClientAgg> agg = queryRevenueAndCosts(fromKey, toKey, hasCompanies ? companyIds : null);
        if (agg.isEmpty()) return List.of();

        // 2. Per-client rate gap
        Map<String, Double> rateGapByClient = queryRateGap(fromKey, toKey, hasCompanies ? companyIds : null);

        // 3. Per-client unused contract
        Map<String, Double> unusedByClient = queryUnusedContract(fromKey, toKey, hasCompanies ? companyIds : null);

        // 4. Tenant-wide non-salary OPEX (FY-aware)
        double totalNonSalaryOpex = distributionProvider
                .getDistributionAwareOpex(fromKey, toKey, hasCompanies ? companyIds : null, null, null)
                .stream()
                .filter(r -> !r.isPayrollFlag())
                .mapToDouble(OpexRow::opexAmountDkk)
                .sum();

        double totalClientRevenue = agg.values().stream().mapToDouble(a -> a.revenue).sum();

        List<ClientProfitabilityRowDTO> out = new ArrayList<>(agg.size());
        for (ClientAgg a : agg.values()) {
            double share = totalClientRevenue > 0 ? a.revenue / totalClientRevenue : 0.0;
            double opexAlloc = totalNonSalaryOpex * share;
            double actualProfit = a.revenue - a.salary - a.external - a.expenses - opexAlloc;
            double rateGap = rateGapByClient.getOrDefault(a.clientId, 0.0);
            double unused = unusedByClient.getOrDefault(a.clientId, 0.0);

            // Round each component, then derive target from the rounded values so the
            // invariant target = actualProfit + rateGap + unusedContract holds exactly.
            double rActual   = round(actualProfit);
            double rRateGap  = round(rateGap);
            double rUnused   = round(unused);
            double rTarget   = rActual + rRateGap + rUnused;

            out.add(new ClientProfitabilityRowDTO(
                    a.clientId, a.clientName, a.sector == null ? "OTHER" : a.sector,
                    round(a.revenue), round(a.salary), round(a.external), round(a.expenses),
                    round(opexAlloc), rActual, rRateGap, rUnused, rTarget,
                    a.consultantCount
            ));
        }
        out.sort(Comparator.comparingDouble(ClientProfitabilityRowDTO::actualProfitDkk));
        return out;
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }

    private static class ClientAgg {
        String clientId, clientName, sector;
        double revenue, salary, external, expenses;
        int consultantCount;
    }

    // -----------------------------------------------------------------------
    // Step 2: Revenue and costs per client
    // -----------------------------------------------------------------------

    private Map<String, ClientAgg> queryRevenueAndCosts(
            String fromKey, String toKey, Set<String> companyIds) {
        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();
        StringBuilder sql = new StringBuilder();
        sql.append("""
            SELECT
                cl.uuid        AS client_id,
                cl.name        AS client_name,
                cl.segment     AS sector,
                COALESCE(rev.revenue, 0)    AS revenue,
                COALESCE(pf.salary, 0)      AS salary,
                COALESCE(pf.external, 0)    AS external,
                COALESCE(pf.expenses, 0)    AS expenses,
                COALESCE(cc_count.n, 0)     AS consultant_count
            FROM (
                SELECT client_id, SUM(net_revenue_dkk) AS revenue
                FROM fact_client_revenue_mat
                WHERE month_key BETWEEN :fromKey AND :toKey
                  AND client_id IS NOT NULL
                  AND client_id <> :internalClient
            """);
        if (hasCompanies) sql.append("  AND company_id IN (:companyIds)\n");
        sql.append("""
                GROUP BY client_id
                HAVING SUM(net_revenue_dkk) > 0
            ) rev
            JOIN client cl ON cl.uuid = rev.client_id
            LEFT JOIN (
                SELECT
                    client_id,
                    SUM(employee_salary_cost_dkk)    AS salary,
                    SUM(external_consultant_cost_dkk) AS external,
                    SUM(project_expense_cost_dkk)     AS expenses
                FROM fact_project_financials_mat
                WHERE month_key BETWEEN :fromKey AND :toKey
                  AND client_id IS NOT NULL
            """);
        if (hasCompanies) sql.append("  AND companyuuid IN (:companyIds)\n");
        sql.append("""
                GROUP BY client_id
            ) pf ON pf.client_id = rev.client_id
            LEFT JOIN (
                SELECT p.clientuuid AS client_id, COUNT(DISTINCT w.useruuid) AS n
                FROM work w
                JOIN project p ON p.uuid = w.projectuuid
                WHERE DATE_FORMAT(w.registered, '%Y%m') BETWEEN :fromKey AND :toKey
                  AND p.clientuuid IS NOT NULL
                  AND w.rate > 0
                GROUP BY p.clientuuid
            ) cc_count ON cc_count.client_id = rev.client_id
            """);

        var q = em.createNativeQuery(sql.toString(), Tuple.class);
        q.setParameter("fromKey", fromKey);
        q.setParameter("toKey", toKey);
        q.setParameter("internalClient", INTERNAL_CLIENT_UUID);
        if (hasCompanies) q.setParameter("companyIds", companyIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = q.getResultList();
        Map<String, ClientAgg> out = new LinkedHashMap<>();
        for (Tuple r : rows) {
            ClientAgg a = new ClientAgg();
            a.clientId       = (String) r.get("client_id");
            a.clientName     = (String) r.get("client_name");
            a.sector         = (String) r.get("sector");
            a.revenue        = ((Number) r.get("revenue")).doubleValue();
            a.salary         = ((Number) r.get("salary")).doubleValue();
            a.external       = ((Number) r.get("external")).doubleValue();
            a.expenses       = ((Number) r.get("expenses")).doubleValue();
            a.consultantCount = ((Number) r.get("consultant_count")).intValue();
            out.put(a.clientId, a);
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // Step 3: Rate gap per client
    // -----------------------------------------------------------------------

    private Map<String, Double> queryRateGap(
            String fromKey, String toKey, Set<String> companyIds) {
        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();
        StringBuilder sql = new StringBuilder();
        sql.append("""
            SELECT client_id, SUM(gap_dkk) AS rate_gap
            FROM (
                SELECT
                    p.clientuuid AS client_id,
                    GREATEST(0,
                        AVG(mvr.break_even_rate_actual) * SUM(w.workduration)
                      - SUM(w.workduration * w.rate)
                    ) AS gap_dkk
                FROM work w
                JOIN project p  ON p.uuid = w.projectuuid
                JOIN user u     ON u.uuid = w.useruuid
                JOIN userstatus us ON us.useruuid = u.uuid
                    AND us.statusdate = (
                        SELECT MAX(us2.statusdate) FROM userstatus us2
                        WHERE us2.useruuid = u.uuid AND us2.statusdate <= w.registered
                    )
                JOIN user_career_level ucl ON ucl.useruuid = u.uuid
                    AND ucl.active_from = (
                        SELECT MAX(ucl2.active_from) FROM user_career_level ucl2
                        WHERE ucl2.useruuid = u.uuid AND ucl2.active_from <= w.registered
                    )
                JOIN fact_minimum_viable_rate_mat mvr
                    ON mvr.career_level = ucl.career_level
                   AND mvr.company_id   = us.companyuuid
                WHERE w.rate > 0
                  AND DATE_FORMAT(w.registered, '%Y%m') BETWEEN :fromKey AND :toKey
                  AND p.clientuuid IS NOT NULL
                  AND p.clientuuid <> :internalClient
            """);
        if (hasCompanies) sql.append("  AND us.companyuuid IN (:companyIds)\n");
        sql.append("""
                GROUP BY p.clientuuid, w.useruuid, DATE_FORMAT(w.registered, '%Y%m')
            ) per_cm
            GROUP BY client_id
            """);

        var q = em.createNativeQuery(sql.toString(), Tuple.class);
        q.setParameter("fromKey", fromKey);
        q.setParameter("toKey", toKey);
        q.setParameter("internalClient", INTERNAL_CLIENT_UUID);
        if (hasCompanies) q.setParameter("companyIds", companyIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = q.getResultList();
        Map<String, Double> out = new HashMap<>();
        for (Tuple r : rows) {
            out.put((String) r.get("client_id"), ((Number) r.get("rate_gap")).doubleValue());
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // Step 4: Month key helper
    // -----------------------------------------------------------------------

    private static LocalDate parseMonthKey(String key) {
        int y = Integer.parseInt(key.substring(0, 4));
        int m = Integer.parseInt(key.substring(4, 6));
        return LocalDate.of(y, m, 1);
    }

    // -----------------------------------------------------------------------
    // Step 5: Unused contract per client
    // -----------------------------------------------------------------------

    private Map<String, Double> queryUnusedContract(
            String fromKey, String toKey, Set<String> companyIds) {
        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();
        LocalDate fromDate = parseMonthKey(fromKey);
        LocalDate toDate   = parseMonthKey(toKey).plusMonths(1);

        StringBuilder sql = new StringBuilder();
        sql.append("""
            SELECT c.clientuuid AS client_id,
                   SUM(GREATEST(0, overlap.contracted_hrs_ttm - COALESCE(act.actual_hrs, 0)) * cc.rate) AS unused
            FROM contract_consultants cc
            JOIN contracts c ON c.uuid = cc.contractuuid
            JOIN (
                SELECT cc2.uuid AS ccuuid,
                       cc2.hours * (
                           GREATEST(0,
                               LEAST(DATEDIFF(:toDate, GREATEST(cc2.activefrom, :fromDate)),
                                     DATEDIFF(cc2.activeto, GREATEST(cc2.activefrom, :fromDate))) + 1
                           ) /
                           NULLIF(DATEDIFF(cc2.activeto, cc2.activefrom) + 1, 0)
                       ) AS contracted_hrs_ttm
                FROM contract_consultants cc2
                WHERE cc2.activeto >= :fromDate AND cc2.activefrom < :toDate
            ) overlap ON overlap.ccuuid = cc.uuid
            LEFT JOIN (
                SELECT cp.contractuuid, w.useruuid, SUM(w.workduration) AS actual_hrs
                FROM contract_project cp
                JOIN work w
                  ON w.projectuuid = cp.projectuuid
                 AND DATE_FORMAT(w.registered, '%Y%m') BETWEEN :fromKey AND :toKey
                 AND w.rate > 0
                GROUP BY cp.contractuuid, w.useruuid
            ) act ON act.contractuuid = cc.contractuuid AND act.useruuid = cc.useruuid
            WHERE c.status = 'SIGNED'
              AND c.clientuuid IS NOT NULL
              AND c.clientuuid <> :internalClient
            """);
        if (hasCompanies) sql.append("  AND c.companyuuid IN (:companyIds)\n");
        sql.append("GROUP BY c.clientuuid\n");

        var q = em.createNativeQuery(sql.toString(), Tuple.class);
        q.setParameter("fromKey", fromKey);
        q.setParameter("toKey", toKey);
        q.setParameter("fromDate", java.sql.Date.valueOf(fromDate));
        q.setParameter("toDate", java.sql.Date.valueOf(toDate));
        q.setParameter("internalClient", INTERNAL_CLIENT_UUID);
        if (hasCompanies) q.setParameter("companyIds", companyIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = q.getResultList();
        Map<String, Double> out = new HashMap<>();
        for (Tuple r : rows) {
            Object v = r.get("unused");
            if (v != null) out.put((String) r.get("client_id"), ((Number) v).doubleValue());
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // Task 5: Per-client consultant drill-down
    // -----------------------------------------------------------------------

    public List<ClientConsultantDetailDTO> getConsultantsForClient(
            String clientId, String fromKey, String toKey, Set<String> companyIds) {

        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();
        java.time.LocalDate fromDate = parseMonthKey(fromKey);
        java.time.LocalDate toDate   = parseMonthKey(toKey).plusMonths(1);

        StringBuilder sql = new StringBuilder();
        sql.append("""
            SELECT u.uuid AS useruuid, u.firstname, u.lastname, ucl.career_level,
                   mvr.break_even_rate_actual AS be_rate,
                   COALESCE(work_agg.hrs, 0)       AS hours_booked,
                   COALESCE(work_agg.amount, 0)    AS amount_booked,
                   COALESCE(contract_agg.hrs, 0)   AS hours_contracted,
                   COALESCE(util_agg.billable, 0)  AS util_billable,
                   COALESCE(util_agg.available, 0) AS util_available
            FROM user u
            JOIN userstatus us ON us.useruuid = u.uuid
                AND us.statusdate = (SELECT MAX(us2.statusdate) FROM userstatus us2
                                     WHERE us2.useruuid = u.uuid AND us2.statusdate <= CURRENT_DATE)
            JOIN user_career_level ucl ON ucl.useruuid = u.uuid
                AND ucl.active_from = (SELECT MAX(ucl2.active_from) FROM user_career_level ucl2
                                       WHERE ucl2.useruuid = u.uuid AND ucl2.active_from <= CURRENT_DATE)
            JOIN fact_minimum_viable_rate_mat mvr
                ON mvr.career_level = ucl.career_level AND mvr.company_id = us.companyuuid
            LEFT JOIN (
                SELECT w.useruuid,
                       SUM(w.workduration)             AS hrs,
                       SUM(w.workduration * w.rate)    AS amount
                FROM work w
                JOIN project p ON p.uuid = w.projectuuid
                WHERE p.clientuuid = :clientId
                  AND w.rate > 0
                  AND DATE_FORMAT(w.registered, '%Y%m') BETWEEN :fromKey AND :toKey
                GROUP BY w.useruuid
            ) work_agg ON work_agg.useruuid = u.uuid
            LEFT JOIN (
                SELECT cc.useruuid,
                       SUM(cc.hours * (
                           GREATEST(0,
                               LEAST(DATEDIFF(:toDate, GREATEST(cc.activefrom, :fromDate)),
                                     DATEDIFF(cc.activeto, GREATEST(cc.activefrom, :fromDate))) + 1
                           ) / NULLIF(DATEDIFF(cc.activeto, cc.activefrom) + 1, 0)
                       )) AS hrs
                FROM contract_consultants cc
                JOIN contracts c ON c.uuid = cc.contractuuid
                WHERE c.clientuuid = :clientId
                  AND c.status = 'SIGNED'
                  AND cc.activeto >= :fromDate AND cc.activefrom < :toDate
                GROUP BY cc.useruuid
            ) contract_agg ON contract_agg.useruuid = u.uuid
            LEFT JOIN (
                SELECT fud.useruuid,
                       SUM(fud.registered_billable_hours) AS billable,
                       SUM(fud.net_available_hours)      AS available
                FROM fact_user_day fud
                WHERE fud.consultant_type = 'CONSULTANT'
                  AND fud.status_type     = 'ACTIVE'
                  AND fud.document_date >= :fromDate AND fud.document_date < :toDate
                GROUP BY fud.useruuid
            ) util_agg ON util_agg.useruuid = u.uuid
            WHERE (work_agg.hrs IS NOT NULL OR contract_agg.hrs IS NOT NULL)
            """);
        if (hasCompanies) sql.append("  AND us.companyuuid IN (:companyIds)\n");
        sql.append("ORDER BY hours_booked DESC\n");

        var q = em.createNativeQuery(sql.toString(), Tuple.class);
        q.setParameter("clientId", clientId);
        q.setParameter("fromKey", fromKey);
        q.setParameter("toKey", toKey);
        q.setParameter("fromDate", java.sql.Date.valueOf(fromDate));
        q.setParameter("toDate", java.sql.Date.valueOf(toDate));
        if (hasCompanies) q.setParameter("companyIds", companyIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = q.getResultList();
        List<ClientConsultantDetailDTO> out = new ArrayList<>(rows.size());
        for (Tuple r : rows) {
            double hoursBooked     = ((Number) r.get("hours_booked")).doubleValue();
            double amountBooked    = ((Number) r.get("amount_booked")).doubleValue();
            double hoursContracted = ((Number) r.get("hours_contracted")).doubleValue();
            double beRate          = ((Number) r.get("be_rate")).doubleValue();
            double billable        = ((Number) r.get("util_billable")).doubleValue();
            double available       = ((Number) r.get("util_available")).doubleValue();

            Double actualRate = hoursBooked > 0 ? amountBooked / hoursBooked : null;
            Double util       = available > 0 ? billable / available : null;

            // Derive unused from rounded values to keep the invariant exact:
            // max(0, hoursContracted - hoursBooked) == unusedHours (within epsilon)
            double rHoursBooked     = round(hoursBooked);
            double rHoursContracted = round(hoursContracted);
            double rUnused          = Math.max(0, rHoursContracted - rHoursBooked);

            out.add(new ClientConsultantDetailDTO(
                    (String) r.get("useruuid"),
                    (String) r.get("firstname"),
                    (String) r.get("lastname"),
                    (String) r.get("career_level"),
                    actualRate == null ? null : round(actualRate),
                    round(beRate),
                    rHoursBooked,
                    rHoursContracted,
                    rUnused,
                    util
            ));
        }
        return out;
    }
}
