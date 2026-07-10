package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PayEquityRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PayTrendPoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.Response;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.RetentionCohort;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.RetentionCohortPoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.RetentionRatePoint;
import dk.trustworks.intranet.aggregates.executive.people.HrCareerBandMapper;
import dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsRepository;
import dk.trustworks.intranet.aggregates.executive.people.PeopleCompensationGroup;
import dk.trustworks.intranet.aggregates.executive.people.PeopleEmploymentSpellSupport;
import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterParams;
import dk.trustworks.intranet.aggregates.executive.people.PeopleManagementScope;
import dk.trustworks.intranet.aggregates.executive.people.PeoplePopulationScope;
import dk.trustworks.intranet.aggregates.executive.people.PeoplePopulationSqlSupport;
import dk.trustworks.intranet.aggregates.executive.people.PeopleSalaryType;
import dk.trustworks.intranet.userservice.model.enums.CareerTrack;
import dk.trustworks.intranet.userservice.model.enums.PrimarySkillType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.Tuple;
import jakarta.ws.rs.BadRequestException;

import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.toDouble;
import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.toDoubleBoxed;
import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.toLong;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.meta;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.percentage;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.round2;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.suppresses;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.visibleCount;

/** Continuous-spell retention and contractual-pay analytics. */
@ApplicationScoped
public class ExecutivePeopleRetentionPayService {

    @Inject
    PeopleAnalyticsRepository repository;

    public Response<List<RetentionRatePoint>> retentionRate(PeopleFilterParams filters) {
        requireEmployedPopulation(filters, "retention-rate");
        String scope = retentionSnapshotScope(filters);
        String sql = "WITH " + PeoplePopulationSqlSupport.monthSpineCte() + "," +
                PeopleEmploymentSpellSupport.sqlCtes() +
                ", retention_status_candidates AS (" +
                " SELECT ms.snapshot_date,us.*," +
                PeoplePopulationSqlSupport.transferDestinationCase("us", "paired_status") + " transfer_destination," +
                PeoplePopulationSqlSupport.sameCompanyRehireCase("us", "same_company_exit") + " same_company_rehire" +
                " FROM month_spine ms JOIN userstatus us" +
                " ON us.statusdate<=DATE_SUB(ms.snapshot_date,INTERVAL 12 MONTH)" +
                "), retention_status_ranked AS (" +
                " SELECT rsc.*,ROW_NUMBER() OVER (PARTITION BY rsc.snapshot_date,rsc.useruuid" +
                " ORDER BY " + retentionStatusTemporalOrder("rsc") + ") rn" +
                " FROM retention_status_candidates rsc" +
                "), retention_status AS (SELECT * FROM retention_status_ranked WHERE rn=1)" +
                ", retention_career_ranked AS (" +
                " SELECT ms.snapshot_date,ucl.*,ROW_NUMBER() OVER (PARTITION BY ms.snapshot_date,ucl.useruuid" +
                " ORDER BY ucl.active_from DESC,ucl.created_at DESC,ucl.uuid DESC) rn" +
                " FROM month_spine ms JOIN user_career_level ucl" +
                " ON ucl.active_from<=DATE_SUB(ms.snapshot_date,INTERVAL 12 MONTH)" +
                "), retention_career AS (SELECT * FROM retention_career_ranked WHERE rn=1)" +
                " SELECT ms.snapshot_date," +
                " COUNT(DISTINCT ss.useruuid) starting_employees," +
                " COUNT(DISTINCT CASE WHEN sp.end_date IS NULL OR sp.end_date > ms.snapshot_date" +
                "  THEN ss.useruuid END) retained_employees" +
                " FROM month_spine ms" +
                " JOIN retention_status ss ON ss.snapshot_date=ms.snapshot_date" +
                " JOIN `user` u ON u.uuid=ss.useruuid" +
                " LEFT JOIN retention_career sc ON sc.snapshot_date=ms.snapshot_date AND sc.useruuid=ss.useruuid" +
                " JOIN employment_spells sp ON sp.useruuid=ss.useruuid" +
                "  AND sp.start_date<=DATE_SUB(ms.snapshot_date, INTERVAL 12 MONTH)" +
                "  AND (sp.end_date IS NULL OR sp.end_date>DATE_SUB(ms.snapshot_date, INTERVAL 12 MONTH))" +
                " WHERE " + scope +
                " GROUP BY ms.snapshot_date ORDER BY ms.snapshot_date";
        List<Tuple> rows = repository.tuples("retention-rate", sql, PeoplePopulationSqlSupport.trendBindings(filters));
        List<RetentionRatePoint> data = new ArrayList<>(rows.size());
        long latestSample = 0;
        boolean latestPartialSuppression = false;
        for (Tuple row : rows) {
            long starting = toLong(row.get("starting_employees"));
            long retained = toLong(row.get("retained_employees"));
            long departures = Math.max(0, starting - retained);
            boolean pointSuppressed = suppresses(starting) || suppresses(retained) || suppresses(departures);
            data.add(new RetentionRatePoint(
                    YearMonth.from(localDate(row.get("snapshot_date"))).toString(),
                    visibleCount(starting, pointSuppressed),
                    visibleCount(retained, pointSuppressed),
                    visibleCount(departures, pointSuppressed),
                    percentage(retained, starting, pointSuppressed)));
            latestSample = starting;
            latestPartialSuppression = pointSuppressed;
        }
        return new Response<>(meta(filters, PeoplePopulationSqlSupport.periodStart(filters), filters.asOfDate(),
                filters.months(), null, latestPartialSuppression ? -1 : latestSample, 0,
                suppresses(latestSample), YearMonth.from(filters.asOfDate()),
                List.of(
                        "Each point measures continuous group employment over the trailing 12 months ending at that snapshot.",
                        "Starting employees form the denominator; retained employees remain in the same continuous employment spell.",
                        "Rehires begin a new spell and company transfers do not count as departures.",
                        "There is no target line; comparisons use historical actuals.")), data);
    }

    public Response<List<RetentionCohort>> retentionCohorts(PeopleFilterParams filters) {
        requireEmployedPopulation(filters, "retention-cohorts");
        LocalDate cohortStart = filters.asOfDate().minusYears(7).withDayOfYear(1);
        // dim_date starts at 2014-01-01; use an in-range anchor to generate 0..months.
        LocalDate numberBase = LocalDate.of(2014, 1, 1);
        LocalDate numberEnd = numberBase.plusMonths(filters.months());
        String scope = cohortScope(filters);
        String sql = "WITH " + PeopleEmploymentSpellSupport.sqlCtes() +
                ", month_numbers AS (" +
                " SELECT TIMESTAMPDIFF(MONTH,:numberBase,dd.date_key) month_number" +
                " FROM dim_date dd WHERE dd.date_key BETWEEN :numberBase AND :numberEnd AND dd.day=1" +
                "), cohort_spells AS (" +
                " SELECT sp.useruuid,sp.spell_number,sp.start_date,sp.end_date,YEAR(sp.start_date) cohort_year" +
                " FROM employment_spells sp" +
                " JOIN spell_status_day ss ON ss.useruuid=sp.useruuid AND ss.statusdate=sp.start_date" +
                " JOIN `user` u ON u.uuid=sp.useruuid" +
                " LEFT JOIN user_career_level ucl ON ucl.useruuid=sp.useruuid AND ucl.active_from<=sp.start_date" +
                "  AND NOT EXISTS (SELECT 1 FROM user_career_level newer" +
                "   WHERE newer.useruuid=ucl.useruuid AND newer.active_from<=sp.start_date" +
                "   AND (newer.active_from>ucl.active_from" +
                "    OR (newer.active_from=ucl.active_from AND newer.created_at>ucl.created_at)" +
                "    OR (newer.active_from=ucl.active_from AND newer.created_at=ucl.created_at AND newer.uuid>ucl.uuid)))" +
                " WHERE sp.start_date BETWEEN :cohortStart AND :asOfDate AND " + scope +
                "), cohort_sizes AS (" +
                " SELECT cohort_year,COUNT(*) cohort_size FROM cohort_spells GROUP BY cohort_year" +
                ") SELECT cs.cohort_year,mn.month_number,cz.cohort_size," +
                " SUM(CASE WHEN TIMESTAMPDIFF(MONTH,cs.start_date,COALESCE(cs.end_date,:asOfDate))>=mn.month_number" +
                "  THEN 1 ELSE 0 END) at_risk," +
                " SUM(CASE WHEN cs.end_date IS NOT NULL" +
                "  AND TIMESTAMPDIFF(MONTH,cs.start_date,cs.end_date)=mn.month_number THEN 1 ELSE 0 END) events" +
                " FROM cohort_spells cs JOIN cohort_sizes cz ON cz.cohort_year=cs.cohort_year" +
                " CROSS JOIN month_numbers mn" +
                " GROUP BY cs.cohort_year,mn.month_number,cz.cohort_size" +
                " HAVING at_risk>0 ORDER BY cs.cohort_year,mn.month_number";

        Map<String, Object> bindings = PeoplePopulationSqlSupport.filterBindings(filters);
        bindings.put("asOfDate", Date.valueOf(filters.asOfDate()));
        bindings.put("cohortStart", Date.valueOf(cohortStart));
        bindings.put("numberBase", Date.valueOf(numberBase));
        bindings.put("numberEnd", Date.valueOf(numberEnd));
        List<Tuple> rows = repository.tuples("retention-cohorts-v2", sql, bindings);

        Map<String, CohortAccumulator> cohorts = new LinkedHashMap<>();
        long totalSpells = 0;
        for (Tuple row : rows) {
            String cohort = String.valueOf(toLong(row.get("cohort_year")));
            long cohortSize = toLong(row.get("cohort_size"));
            CohortAccumulator acc = cohorts.computeIfAbsent(cohort, ignored -> new CohortAccumulator(cohortSize));
            if (acc.points.isEmpty()) totalSpells += cohortSize;
            long atRisk = toLong(row.get("at_risk"));
            long events = toLong(row.get("events"));
            int month = (int) toLong(row.get("month_number"));
            boolean pointSuppressed = acc.survivalSuppressed || suppresses(atRisk) || suppresses(events);
            if (pointSuppressed) acc.survivalSuppressed = true;
            if (!acc.survivalSuppressed && atRisk > 0) {
                acc.survival *= 1.0d - ((double) events / atRisk);
            }
            acc.points.add(new RetentionCohortPoint(
                    month,
                    visibleCount(atRisk, pointSuppressed),
                    visibleCount(events, pointSuppressed),
                    visibleCount(Math.max(0, atRisk - events), pointSuppressed),
                    acc.survivalSuppressed ? null : round2(acc.survival * 100.0d),
                    pointSuppressed));
        }
        List<RetentionCohort> data = new ArrayList<>(cohorts.size());
        boolean anySuppressed = false;
        for (Map.Entry<String, CohortAccumulator> entry : cohorts.entrySet()) {
            CohortAccumulator acc = entry.getValue();
            anySuppressed |= acc.cohortSuppressed || acc.survivalSuppressed;
            data.add(new RetentionCohort(
                    entry.getKey(),
                    visibleCount(acc.cohortSize, acc.cohortSuppressed),
                    acc.cohortSuppressed,
                    acc.cohortSuppressed ? List.of() : acc.points));
        }
        return new Response<>(meta(filters, cohortStart, filters.asOfDate(), filters.months(), null,
                anySuppressed ? -1 : totalSpells, 0, suppresses(totalSpells), YearMonth.from(filters.asOfDate()),
                List.of(
                        "Each hire or rehire creates a new continuous-employment spell in its actual start-year cohort.",
                        "Cohort curves are shown through the selected 12, 24, or 36-month horizon (24 months by default).",
                        "Survival is Kaplan–Meier style: at-risk counts exclude employees whose observation window ended before that month.",
                        "Active spells are right-censored at the reporting date; future status rows are excluded.",
                        "A suppressed event interval also suppresses later survival values to prevent differencing.")), data);
    }

    public Response<List<PayEquityRow>> payEquity(PeopleFilterParams filters) {
        requireEmployedPopulation(filters, "pay-equity");
        String groupExpression = compensationGroupExpression(filters.compensationGroup(), "fp");
        String salaryExpression = salaryExpression(filters.salaryType(), "cs", "fp");
        String salaryPredicate = salaryPredicate(filters.salaryType(), "cs", "fp");
        String sql = "WITH " + PeoplePopulationSqlSupport.snapshotPopulationCtes(filters, "asOfDate") +
                ", salary_ranked AS (" +
                " SELECT s.*,ROW_NUMBER() OVER (PARTITION BY s.useruuid ORDER BY " +
                salaryTemporalOrder("s") + ") rn" +
                " FROM salary s WHERE s.activefrom<=:asOfDate" +
                "), current_salary AS (SELECT * FROM salary_ranked WHERE rn=1)," +
                " compensation AS (" +
                " SELECT fp.useruuid,fp.gender," + groupExpression + " group_key," + salaryExpression + " pay_value" +
                " FROM filtered_population fp JOIN current_salary cs ON cs.useruuid=fp.useruuid" +
                " WHERE " + salaryPredicate + " AND fp.gender IN ('MALE','FEMALE')" +
                "), gender_stats AS (" +
                " SELECT group_key,gender," +
                " COUNT(*) OVER (PARTITION BY group_key,gender) people_count," +
                " AVG(pay_value) OVER (PARTITION BY group_key,gender) mean_pay," +
                " PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY pay_value)" +
                "  OVER (PARTITION BY group_key,gender) median_pay" +
                " FROM compensation" +
                "), one_gender_row AS (" +
                " SELECT DISTINCT group_key,gender,people_count,mean_pay,median_pay FROM gender_stats" +
                ") SELECT group_key," +
                " MAX(CASE WHEN gender='MALE' THEN people_count ELSE 0 END) male_count," +
                " MAX(CASE WHEN gender='FEMALE' THEN people_count ELSE 0 END) female_count," +
                " MAX(CASE WHEN gender='MALE' THEN median_pay END) male_median," +
                " MAX(CASE WHEN gender='FEMALE' THEN median_pay END) female_median," +
                " MAX(CASE WHEN gender='MALE' THEN mean_pay END) male_mean," +
                " MAX(CASE WHEN gender='FEMALE' THEN mean_pay END) female_mean" +
                " FROM one_gender_row GROUP BY group_key";
        Map<String, Object> bindings = PeoplePopulationSqlSupport.snapshotBindings(
                filters, "asOfDate", filters.asOfDate());
        bindings.put("salaryType", filters.salaryType().name());
        List<Tuple> rows = repository.tuples("pay-equity", sql, bindings);
        List<PayEquityRow> data = new ArrayList<>(rows.size());
        long eligible = 0;
        boolean anySuppressed = false;
        for (Tuple row : rows) {
            String group = row.get("group_key", String.class);
            long maleCount = toLong(row.get("male_count"));
            long femaleCount = toLong(row.get("female_count"));
            eligible += maleCount + femaleCount;
            boolean suppressed = maleCount < 3 || femaleCount < 3;
            Double maleMedianRaw = suppressed ? null : toDoubleBoxed(row.get("male_median"));
            Double femaleMedianRaw = suppressed ? null : toDoubleBoxed(row.get("female_median"));
            Double maleMedian = maleMedianRaw == null ? null : round2(maleMedianRaw);
            Double femaleMedian = femaleMedianRaw == null ? null : round2(femaleMedianRaw);
            Double maleMean = suppressed ? null : rounded(row.get("male_mean"));
            Double femaleMean = suppressed ? null : rounded(row.get("female_mean"));
            Double gap = suppressed || maleMedianRaw == null || maleMedianRaw == 0d || femaleMedianRaw == null
                    ? null : round2((maleMedianRaw - femaleMedianRaw) / maleMedianRaw * 100.0d);
            data.add(new PayEquityRow(
                    group,
                    group,
                    compensationGroupSort(filters.compensationGroup(), group),
                    filters.salaryType().name(),
                    visibleCount(maleCount, suppressed),
                    visibleCount(femaleCount, suppressed),
                    maleMedian,
                    femaleMedian,
                    maleMean,
                    femaleMean,
                    gap,
                    suppressed));
            anySuppressed |= suppressed;
        }
        data.sort(java.util.Comparator.comparingInt(PayEquityRow::sortOrder));
        long population = payPopulationCount(filters);
        long excluded = Math.max(0, population - eligible);
        if (complementSmallExcludedPayEquity(data, excluded)) anySuppressed = true;
        return new Response<>(meta(filters, filters.asOfDate(), filters.asOfDate(), null, null,
                anySuppressed ? -1 : eligible, excluded, suppresses(eligible), YearMonth.from(filters.asOfDate()),
                List.of(
                        "Pay is the latest contractual salary on or before the reporting date; owner cost overrides are never used.",
                        filters.salaryType() == PeopleSalaryType.NORMAL
                                ? "NORMAL pay is monthly DKK normalized to 37 hours; salaries below 1,000 and zero allocations are excluded."
                                : "HOURLY pay is the raw contractual DKK-per-hour rate in a separate view.",
                        "Each group requires at least three people of each recorded gender.",
                        "If one or two people are excluded, one eligible group is also hidden to prevent total differencing.",
                        "Pay gap = (male median − female median) / male median × 100; the signed value is descriptive, not a target.")), data);
    }

    public Response<List<PayTrendPoint>> payTrend(PeopleFilterParams filters) {
        requireEmployedPopulation(filters, "pay-trend");
        String condition = PeoplePopulationSqlSupport.snapshotFilters(
                filters, "msr", "u", "mc", "ms.snapshot_date");
        String salaryExpression = salaryExpression(filters.salaryType(), "msal", "msr");
        String salaryPredicate = salaryPredicate(filters.salaryType(), "msal", "msr");
        String sql = "WITH " + PeoplePopulationSqlSupport.monthlyPopulationCtes() +
                ", monthly_salary_ranked AS (" +
                " SELECT ms.snapshot_date,s.*,ROW_NUMBER() OVER (PARTITION BY ms.snapshot_date,s.useruuid" +
                "  ORDER BY " + salaryTemporalOrder("s") + ") rn" +
                " FROM month_spine ms JOIN salary s ON s.activefrom<=ms.snapshot_date" +
                "), monthly_salary AS (SELECT * FROM monthly_salary_ranked WHERE rn=1)," +
                " compensation AS (" +
                " SELECT ms.snapshot_date,msr.useruuid,u.gender," + salaryExpression + " pay_value" +
                " FROM month_spine ms" +
                " JOIN monthly_status msr ON msr.snapshot_date=ms.snapshot_date" +
                " JOIN `user` u ON u.uuid=msr.useruuid" +
                " LEFT JOIN monthly_career mc ON mc.snapshot_date=ms.snapshot_date AND mc.useruuid=msr.useruuid" +
                " JOIN monthly_salary msal ON msal.snapshot_date=ms.snapshot_date AND msal.useruuid=msr.useruuid" +
                " WHERE " + condition + " AND " + salaryPredicate +
                "), stats AS (" +
                " SELECT snapshot_date," +
                " COUNT(*) OVER (PARTITION BY snapshot_date) people_count," +
                " SUM(CASE WHEN gender='MALE' THEN 1 ELSE 0 END) OVER (PARTITION BY snapshot_date) male_count," +
                " SUM(CASE WHEN gender='FEMALE' THEN 1 ELSE 0 END) OVER (PARTITION BY snapshot_date) female_count," +
                " AVG(pay_value) OVER (PARTITION BY snapshot_date) mean_pay," +
                " PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY pay_value) OVER (PARTITION BY snapshot_date) median_pay" +
                " FROM compensation" +
                "), one_month AS (" +
                " SELECT DISTINCT snapshot_date,people_count,male_count,female_count,mean_pay,median_pay FROM stats" +
                ") SELECT ms.snapshot_date,COALESCE(om.people_count,0) people_count," +
                " COALESCE(om.male_count,0) male_count,COALESCE(om.female_count,0) female_count," +
                " om.mean_pay,om.median_pay" +
                " FROM month_spine ms LEFT JOIN one_month om ON om.snapshot_date=ms.snapshot_date" +
                " ORDER BY ms.snapshot_date";
        Map<String, Object> bindings = PeoplePopulationSqlSupport.trendBindings(filters);
        bindings.put("salaryType", filters.salaryType().name());
        List<Tuple> rows = repository.tuples("pay-trend", sql, bindings);
        List<PayTrendPoint> data = new ArrayList<>(rows.size());
        long latestSample = 0;
        boolean latestPartialSuppression = false;
        for (Tuple row : rows) {
            long count = toLong(row.get("people_count"));
            long male = toLong(row.get("male_count"));
            long female = toLong(row.get("female_count"));
            boolean suppressed = male < 3 || female < 3;
            data.add(new PayTrendPoint(
                    YearMonth.from(localDate(row.get("snapshot_date"))).toString(),
                    filters.salaryType().name(),
                    visibleCount(count, suppressed),
                    suppressed ? null : rounded(row.get("median_pay")),
                    suppressed ? null : rounded(row.get("mean_pay")),
                    suppressed));
            latestSample = count;
            latestPartialSuppression = suppressed;
        }
        long population = payPopulationCount(filters);
        long excluded = Math.max(0, population - latestSample);
        if (complementSmallExcludedPayTrend(data, excluded)) latestPartialSuppression = true;
        return new Response<>(meta(filters, PeoplePopulationSqlSupport.periodStart(filters), filters.asOfDate(),
                filters.months(), null, latestPartialSuppression ? -1 : latestSample, excluded,
                suppresses(latestSample), YearMonth.from(filters.asOfDate()),
                List.of(
                        "Median and mean use the contractual pay unit shown by salaryType.",
                        "NORMAL and HOURLY values are never combined in one series.",
                        "Excluded count is the reporting-date employed population without an eligible contractual pay record.",
                        "If one or two people are excluded, the current eligible pay point is also hidden to prevent total differencing.",
                        "A month is suppressed unless it contains at least three recorded male and three recorded female employees.")), data);
    }

    private String retentionSnapshotScope(PeopleFilterParams filters) {
        StringBuilder sql = new StringBuilder(
                "ss.`type` IN (:employeeTypes) AND ss.status IN (:populationStatuses)");
        if (filters.companyId() != null) sql.append(" AND ss.companyuuid=:companyId");
        if (!filters.practices().isEmpty()) sql.append(" AND u.practice IN (:practices)");
        if (!filters.careerTracks().isEmpty()) sql.append(" AND sc.career_track IN (:careerTracks)");
        if (!filters.careerLevels().isEmpty()) sql.append(" AND sc.career_level IN (:careerLevels)");
        if (filters.managementScope() == PeopleManagementScope.PEOPLE_LEADERS) {
            sql.append(" AND EXISTS (SELECT 1 FROM teamroles tr WHERE tr.useruuid=ss.useruuid")
                    .append(" AND tr.membertype='LEADER'")
                    .append(" AND tr.startdate<=DATE_SUB(ms.snapshot_date, INTERVAL 12 MONTH)")
                    .append(" AND (tr.enddate IS NULL OR tr.enddate>=DATE_SUB(ms.snapshot_date, INTERVAL 12 MONTH)))");
        } else if (filters.managementScope() == PeopleManagementScope.SENIOR_LEADERSHIP) {
            sql.append(" AND ss.`type`='CONSULTANT' AND sc.career_track IN (:seniorLeadershipTracks)");
        }
        return sql.toString();
    }

    private String cohortScope(PeopleFilterParams filters) {
        StringBuilder sql = new StringBuilder(
                "ss.`type` IN (:employeeTypes) AND ss.status IN (:populationStatuses)");
        if (filters.companyId() != null) sql.append(" AND ss.companyuuid=:companyId");
        if (!filters.practices().isEmpty()) sql.append(" AND u.practice IN (:practices)");
        if (!filters.careerTracks().isEmpty()) sql.append(" AND ucl.career_track IN (:careerTracks)");
        if (!filters.careerLevels().isEmpty()) sql.append(" AND ucl.career_level IN (:careerLevels)");
        if (filters.managementScope() == PeopleManagementScope.PEOPLE_LEADERS) {
            sql.append(" AND EXISTS (SELECT 1 FROM teamroles tr WHERE tr.useruuid=sp.useruuid")
                    .append(" AND tr.membertype='LEADER' AND tr.startdate<=sp.start_date")
                    .append(" AND (tr.enddate IS NULL OR tr.enddate>=sp.start_date))");
        } else if (filters.managementScope() == PeopleManagementScope.SENIOR_LEADERSHIP) {
            sql.append(" AND ss.`type`='CONSULTANT' AND ucl.career_track IN (:seniorLeadershipTracks)");
        }
        return sql.toString();
    }

    private long payPopulationCount(PeopleFilterParams filters) {
        String sql = "WITH " + PeoplePopulationSqlSupport.snapshotPopulationCtes(filters, "asOfDate") +
                " SELECT COUNT(DISTINCT useruuid) people_count FROM filtered_population";
        List<Tuple> rows = repository.tuples("pay-population-count", sql,
                PeoplePopulationSqlSupport.snapshotBindings(filters, "asOfDate", filters.asOfDate()));
        return rows.isEmpty() ? 0 : toLong(rows.getFirst().get("people_count"));
    }

    private static String compensationGroupExpression(PeopleCompensationGroup group, String alias) {
        return switch (group) {
            case CAREER_BAND -> HrCareerBandMapper.toSqlCase(alias + ".career_level");
            case PRACTICE -> "COALESCE(" + alias + ".practice,'UNASSIGNED')";
            case CAREER_TRACK -> "CASE WHEN " + alias + ".career_level='JUNIOR_CONSULTANT' THEN 'ENTRY'" +
                    " WHEN " + alias + ".career_track IS NULL THEN 'UNASSIGNED' ELSE " + alias + ".career_track END";
        };
    }

    private static int compensationGroupSort(PeopleCompensationGroup group, String value) {
        return switch (group) {
            case CAREER_BAND -> HrCareerBandMapper.sortOrder(value);
            case PRACTICE -> {
                List<String> order = new ArrayList<>();
                for (PrimarySkillType practice : PrimarySkillType.values()) order.add(practice.name());
                order.add("UNASSIGNED");
                int index = order.indexOf(value);
                yield index < 0 ? order.size() : index;
            }
            case CAREER_TRACK -> {
                List<String> order = List.of("ENTRY", CareerTrack.DELIVERY.name(), CareerTrack.ADVISORY.name(),
                        CareerTrack.LEADERSHIP.name(), CareerTrack.CLIENT_ENGAGEMENT.name(),
                        CareerTrack.PARTNER.name(), CareerTrack.C_LEVEL.name(), "UNASSIGNED");
                int index = order.indexOf(value);
                yield index < 0 ? order.size() : index;
            }
        };
    }

    private static String salaryExpression(PeopleSalaryType type, String salaryAlias, String statusAlias) {
        return type == PeopleSalaryType.NORMAL
                ? salaryAlias + ".salary * 37.0 / " + statusAlias + ".allocation"
                : salaryAlias + ".salary * 1.0";
    }

    private static String salaryPredicate(PeopleSalaryType type, String salaryAlias, String statusAlias) {
        String common = salaryAlias + ".`type`=:salaryType";
        return type == PeopleSalaryType.NORMAL
                ? common + " AND " + salaryAlias + ".salary>=1000 AND " + statusAlias + ".allocation>0"
                : common + " AND " + salaryAlias + ".salary>0";
    }

    static String salaryTemporalOrder(String alias) {
        return alias + ".activefrom DESC," + alias + ".created_at DESC," + alias + ".uuid DESC";
    }

    static String retentionStatusTemporalOrder(String alias) {
        return alias + ".statusdate DESC," + alias + ".transfer_destination DESC," +
                alias + ".same_company_rehire DESC," + alias + ".created_at DESC," + alias + ".uuid DESC";
    }

    static boolean complementSmallExcludedPayEquity(List<PayEquityRow> rows, long excluded) {
        if (!suppresses(excluded) || rows.stream().anyMatch(PayEquityRow::suppressed)) return false;
        int candidate = java.util.stream.IntStream.range(0, rows.size())
                .boxed()
                .filter(index -> rows.get(index).maleCount() != null && rows.get(index).femaleCount() != null)
                .min(java.util.Comparator
                        .comparingLong((Integer index) -> rows.get(index).maleCount() + rows.get(index).femaleCount())
                        .thenComparing(index -> rows.get(index).groupKey()))
                .orElse(-1);
        if (candidate < 0) return false;
        PayEquityRow row = rows.get(candidate);
        rows.set(candidate, new PayEquityRow(
                row.groupKey(), row.groupLabel(), row.sortOrder(), row.salaryType(),
                null, null, null, null, null, null, null, true));
        return true;
    }

    static boolean complementSmallExcludedPayTrend(List<PayTrendPoint> points, long excluded) {
        if (!suppresses(excluded) || points.isEmpty()) return false;
        int latestIndex = points.size() - 1;
        PayTrendPoint latest = points.get(latestIndex);
        if (latest.suppressed()) return false;
        points.set(latestIndex, new PayTrendPoint(
                latest.month(), latest.salaryType(), null, null, null, true));
        return true;
    }

    private static Double rounded(Object value) {
        Double number = toDoubleBoxed(value);
        return number == null ? null : round2(number);
    }

    private static LocalDate localDate(Object value) {
        if (value instanceof LocalDate localDate) return localDate;
        if (value instanceof Date date) return date.toLocalDate();
        if (value instanceof java.util.Date date) return new Date(date.getTime()).toLocalDate();
        return LocalDate.parse(String.valueOf(value));
    }

    private static void requireEmployedPopulation(PeopleFilterParams filters, String route) {
        if (filters.population() != PeoplePopulationScope.EMPLOYED) {
            throw new BadRequestException(route + " requires population=EMPLOYED for a consistent denominator");
        }
    }

    private static final class CohortAccumulator {
        private final long cohortSize;
        private final boolean cohortSuppressed;
        private final List<RetentionCohortPoint> points = new ArrayList<>();
        private double survival = 1.0d;
        private boolean survivalSuppressed;

        private CohortAccumulator(long cohortSize) {
            this.cohortSize = cohortSize;
            this.cohortSuppressed = suppresses(cohortSize);
            this.survivalSuppressed = cohortSuppressed;
        }
    }
}
