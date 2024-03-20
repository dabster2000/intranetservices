package dk.trustworks.intranet.aggregateservices;


/*
@JBossLog
@ApplicationScoped
@Deprecated
public class EmployeeDataService {

    @PersistenceContext
    EntityManager entityManager;

    public List<EmployeeAvailabilityPerMonth> getAllEmployeeDataPerMonth(@CacheKey String companyuuid, @CacheKey LocalDate fromdate, @CacheKey LocalDate todate) {
        Query nativeQuery = entityManager.createNativeQuery("SELECT " +
                "    ad_agg.id, " +
                "    ad_agg.useruuid, " +
                "    ad_agg.consultant_type, " +
                "    ad_agg.status_type, " +
                "    ad_agg.companyuuid, " +
                "    ad_agg.year, " +
                "    ad_agg.month, " +
                "    ad_agg.gross_available_hours, " +
                "    ad_agg.unavailable_hours, " +
                "    ad_agg.vacation_hours, " +
                "    ad_agg.sick_hours, " +
                "    ad_agg.maternity_leave_hours, " +
                "    ad_agg.non_payd_leave_hours, " +
                "    ad_agg.paid_leave_hours, " +
                "    COALESCE(ww.workduration, 0) AS registered_billable_hours, " +
                "    COALESCE(ww.total_billed, 0) AS registered_amount, " +
                "    COALESCE(bb.budgetHours, 0) AS budget_hours, " +
                "    ad_agg.avg_salary, " +
                "    ad_agg.is_tw_bonus_eligible " +
                "FROM ( " +
                "    SELECT " +
                "        MIN(ad.id) AS id, " +
                "        ad.useruuid, " +
                "        ad.consultant_type, " +
                "        ad.status_type, " +
                "        ad.companyuuid, " +
                "        ad.year, " +
                "        ad.month, " +
                "        SUM(ad.gross_available_hours) AS gross_available_hours, " +
                "        SUM(ad.unavailable_hours) AS unavailable_hours, " +
                "        SUM(ad.vacation_hours) AS vacation_hours, " +
                "        SUM(ad.sick_hours) AS sick_hours, " +
                "        SUM(ad.maternity_leave_hours) AS maternity_leave_hours, " +
                "        SUM(ad.non_payd_leave_hours) AS non_payd_leave_hours, " +
                "        SUM(ad.paid_leave_hours) AS paid_leave_hours, " +
                "        AVG(ad.salary) AS avg_salary, " +
                "        MAX(ad.is_tw_bonus_eligible) AS is_tw_bonus_eligible " +
                "    FROM twservices.availability_document ad " +
                "    WHERE companyuuid = :companyuuid and document_date >= :startDate and document_date < :endDate " +
                "    GROUP BY ad.useruuid, ad.year, ad.month, ad.companyuuid " +
                ") ad_agg " +
                "LEFT JOIN ( " +
                "    SELECT " +
                "        w.useruuid, " +
                "        YEAR(w.registered) AS year, " +
                "        MONTH(w.registered) AS month, " +
                "        SUM(w.workduration) AS workduration, " +
                "        SUM(IFNULL(w.rate, 0) * w.workduration) AS total_billed " +
                "    FROM twservices.work_full w " +
                "    WHERE w.rate > 0 and consultant_company_uuid = :companyuuid and registered >= :startDate and registered < :endDate " +
                "    GROUP BY w.useruuid, YEAR(w.registered), MONTH(w.registered) " +
                ") ww ON ww.year = ad_agg.year AND ww.month = ad_agg.month AND ww.useruuid = ad_agg.useruuid " +
                "LEFT JOIN ( " +
                "    select " +
                "       `ad`.`useruuid`, " +
                "       `ad`.`year`             AS `year`, " +
                "       `ad`.`month`            AS `month`, " +
                "       sum(`ad`.`budgetHours`) AS `budgetHours` " +
                "from `twservices`.`budget_document` `ad` " +
                "where companyuuid = :companyuuid and document_date >= :startDate and document_date < :endDate " +
                "group by `ad`.`useruuid`, `ad`.`year`, `ad`.`month` " +
                ") bb ON bb.year = ad_agg.year AND bb.month = ad_agg.month AND bb.useruuid = ad_agg.useruuid " +
                "ORDER BY ad_agg.year, ad_agg.month;", EmployeeAvailabilityPerMonth.class);
        nativeQuery.setParameter("companyuuid", companyuuid);
        nativeQuery.setParameter("startDate", fromdate);
        nativeQuery.setParameter("endDate", todate);
        return nativeQuery.getResultList();
    }

    public List<EmployeeAvailabilityPerMonth> getEmployeeDataPerMonth(String useruuid, LocalDate fromdate, LocalDate todate) {
        Query nativeQuery = entityManager.createNativeQuery("SELECT " +
                "    ad_agg.id, " +
                "    ad_agg.useruuid, " +
                "    ad_agg.consultant_type, " +
                "    ad_agg.status_type, " +
                "    ad_agg.companyuuid, " +
                "    ad_agg.year, " +
                "    ad_agg.month, " +
                "    ad_agg.gross_available_hours, " +
                "    ad_agg.unavailable_hours, " +
                "    ad_agg.vacation_hours, " +
                "    ad_agg.sick_hours, " +
                "    ad_agg.maternity_leave_hours, " +
                "    ad_agg.non_payd_leave_hours, " +
                "    ad_agg.paid_leave_hours, " +
                "    COALESCE(ww.workduration, 0) AS registered_billable_hours, " +
                "    COALESCE(ww.total_billed, 0) AS registered_amount, " +
                "    COALESCE(bb.budgetHours, 0) AS budget_hours, " +
                "    ad_agg.avg_salary, " +
                "    ad_agg.is_tw_bonus_eligible " +
                "FROM ( " +
                "    SELECT " +
                "        MIN(ad.id) AS id, " +
                "        ad.useruuid, " +
                "        ad.consultant_type, " +
                "        ad.status_type, " +
                "        ad.companyuuid, " +
                "        ad.year, " +
                "        ad.month, " +
                "        SUM(ad.gross_available_hours) AS gross_available_hours, " +
                "        SUM(ad.unavailable_hours) AS unavailable_hours, " +
                "        SUM(ad.vacation_hours) AS vacation_hours, " +
                "        SUM(ad.sick_hours) AS sick_hours, " +
                "        SUM(ad.maternity_leave_hours) AS maternity_leave_hours, " +
                "        SUM(ad.non_payd_leave_hours) AS non_payd_leave_hours, " +
                "        SUM(ad.paid_leave_hours) AS paid_leave_hours, " +
                "        AVG(ad.salary) AS avg_salary, " +
                "        MAX(ad.is_tw_bonus_eligible) AS is_tw_bonus_eligible " +
                "    FROM twservices.availability_document ad " +
                "    WHERE useruuid = :useruuid and document_date >= :startDate and document_date < :endDate " +
                "    GROUP BY ad.useruuid, ad.year, ad.month, ad.companyuuid " +
                ") ad_agg " +
                "LEFT JOIN ( " +
                "    SELECT " +
                "        w.useruuid, " +
                "        YEAR(w.registered) AS year, " +
                "        MONTH(w.registered) AS month, " +
                "        SUM(w.workduration) AS workduration, " +
                "        SUM(IFNULL(w.rate, 0) * w.workduration) AS total_billed " +
                "    FROM twservices.work_full w " +
                "    WHERE w.rate > 0 and useruuid = :useruuid and registered >= :startDate and registered < :endDate " +
                "    GROUP BY w.useruuid, YEAR(w.registered), MONTH(w.registered) " +
                ") ww ON ww.year = ad_agg.year AND ww.month = ad_agg.month AND ww.useruuid = ad_agg.useruuid " +
                "LEFT JOIN ( " +
                "    select " +
                "       `ad`.`useruuid`, " +
                "       `ad`.`year`             AS `year`, " +
                "       `ad`.`month`            AS `month`, " +
                "       sum(`ad`.`budgetHours`) AS `budgetHours` " +
                "from `twservices`.`budget_document` `ad` " +
                "where useruuid = :useruuid and document_date >= :startDate and document_date < :endDate " +
                "group by `ad`.`useruuid`, `ad`.`year`, `ad`.`month` " +
                ") bb ON bb.year = ad_agg.year AND bb.month = ad_agg.month AND bb.useruuid = ad_agg.useruuid " +
                "ORDER BY ad_agg.year, ad_agg.month;", EmployeeAvailabilityPerMonth.class);
        nativeQuery.setParameter("useruuid", useruuid);
        nativeQuery.setParameter("startDate", fromdate);
        nativeQuery.setParameter("endDate", todate);
        return nativeQuery.getResultList();
    }
}


 */


