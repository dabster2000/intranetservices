package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PayEquityRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PayQuartileRow;
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
import dk.trustworks.intranet.services.PracticeService;
import dk.trustworks.intranet.userservice.model.enums.CareerTrack;
import dk.trustworks.intranet.userservice.model.enums.DstEmploymentFunction;
import dk.trustworks.intranet.userservice.model.enums.DstEmploymentStatus;
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

    private static final String OVERALL_GROUP = "OVERALL";
    private static final List<Integer> COHORT_MILESTONES = List.of(0, 6, 12, 24, 36);

    @Inject
    PeopleAnalyticsRepository repository;

    @Inject
    PracticeService practiceService;

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
            if (acc.observations.isEmpty()) totalSpells += cohortSize;
            acc.observations.add(new CohortObservation(
                    (int) toLong(row.get("month_number")),
                    toLong(row.get("at_risk")),
                    toLong(row.get("events"))));
        }
        List<RetentionCohort> data = new ArrayList<>(cohorts.size());
        for (Map.Entry<String, CohortAccumulator> entry : cohorts.entrySet()) {
            CohortAccumulator acc = entry.getValue();
            List<RetentionCohortPoint> points = privacySafeMilestones(
                    acc.cohortSize, acc.observations, filters.months());
            data.add(new RetentionCohort(
                    entry.getKey(),
                    visibleCount(acc.cohortSize, acc.cohortSuppressed),
                    acc.cohortSuppressed,
                    acc.cohortSuppressed ? List.of() : points));
        }
        return new Response<>(meta(filters, cohortStart, filters.asOfDate(), filters.months(), null,
                totalSpells, 0, suppresses(totalSpells), YearMonth.from(filters.asOfDate()),
                List.of(
                        "Each hire or rehire creates a new continuous-employment spell in its actual start-year cohort.",
                        "Cohort survival is shown only at observed month 0, 6, 12, 24, and 36 milestones inside the selected horizon.",
                        "Survival is Kaplan–Meier style: at-risk counts exclude employees whose observation window ended before that month.",
                        "Active spells are right-censored at the reporting date; future status rows are excluded.",
                        "intervalEvents shows departures since the previous milestone; retained is populated only for the month-zero baseline.",
                        "This ADMIN-only aggregate view is a privacy-screening control, not a differential-privacy guarantee.")), data);
    }

    public Response<List<PayEquityRow>> payEquity(PeopleFilterParams filters) {
        requireEmployedPopulation(filters, "pay-equity");
        String groupExpression = compensationGroupExpression(filters.compensationGroup(), "fp", "cds");
        String salaryExpression = salaryExpression(filters.salaryType(), "cs", "fp");
        String salaryPredicate = salaryPredicate(filters.salaryType(), "cs", "fp");
        String sql = "WITH " + PeoplePopulationSqlSupport.snapshotPopulationCtes(filters, "asOfDate") +
                ", salary_ranked AS (" +
                " SELECT s.*,ROW_NUMBER() OVER (PARTITION BY s.useruuid ORDER BY " +
                salaryTemporalOrder("s") + ") rn" +
                " FROM salary s WHERE s.activefrom<=:asOfDate" +
                "), current_salary AS (SELECT * FROM salary_ranked WHERE rn=1)," +
                " dst_ranked AS (" +
                " SELECT uds.*,ROW_NUMBER() OVER (PARTITION BY uds.useruuid" +
                "  ORDER BY uds.active_date DESC,uds.uuid DESC) rn" +
                " FROM user_dst_statistics uds WHERE uds.active_date<=:asOfDate" +
                "), current_dst AS (SELECT * FROM dst_ranked WHERE rn=1)," +
                " compensation_base AS (" +
                " SELECT fp.useruuid,fp.gender," + groupExpression + " group_key," + salaryExpression + " pay_value" +
                " FROM filtered_population fp JOIN current_salary cs ON cs.useruuid=fp.useruuid" +
                " LEFT JOIN current_dst cds ON cds.useruuid=fp.useruuid" +
                " WHERE " + salaryPredicate + " AND fp.gender IN ('MALE','FEMALE')" +
                "), compensation AS (" +
                " SELECT useruuid,gender,group_key,pay_value FROM compensation_base" +
                " UNION ALL SELECT useruuid,gender,'" + OVERALL_GROUP + "',pay_value FROM compensation_base" +
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
        long groupedEligible = 0;
        Long overallEligible = null;
        long unassignedGrouping = 0;
        boolean anySuppressed = false;
        for (Tuple row : rows) {
            String group = row.get("group_key", String.class);
            long maleCount = toLong(row.get("male_count"));
            long femaleCount = toLong(row.get("female_count"));
            if (OVERALL_GROUP.equals(group)) {
                overallEligible = maleCount + femaleCount;
            } else {
                groupedEligible += maleCount + femaleCount;
                if (group.startsWith("UNASSIGNED|") || group.endsWith("|UNASSIGNED")) {
                    unassignedGrouping += maleCount + femaleCount;
                }
            }
            boolean suppressed = false; // privacy floor removed — ADMIN-only full-detail view
            Double maleMedianRaw = suppressed ? null : toDoubleBoxed(row.get("male_median"));
            Double femaleMedianRaw = suppressed ? null : toDoubleBoxed(row.get("female_median"));
            Double maleMedian = maleMedianRaw == null ? null : round2(maleMedianRaw);
            Double femaleMedian = femaleMedianRaw == null ? null : round2(femaleMedianRaw);
            Double maleMeanRaw = suppressed ? null : toDoubleBoxed(row.get("male_mean"));
            Double femaleMeanRaw = suppressed ? null : toDoubleBoxed(row.get("female_mean"));
            Double maleMean = maleMeanRaw == null ? null : round2(maleMeanRaw);
            Double femaleMean = femaleMeanRaw == null ? null : round2(femaleMeanRaw);
            Double medianGap = signedGapPct(maleMedianRaw, femaleMedianRaw);
            Double meanGap = signedGapPct(maleMeanRaw, femaleMeanRaw);
            Boolean reviewThresholdMet = meanGap == null ? null : Math.abs(meanGap) >= 5.0d;
            data.add(new PayEquityRow(
                    group,
                    compensationGroupLabel(filters.compensationGroup(), group),
                    compensationGroupSort(filters.compensationGroup(), group),
                    filters.salaryType().name(),
                    visibleCount(maleCount, suppressed),
                    visibleCount(femaleCount, suppressed),
                    maleMedian,
                    femaleMedian,
                    maleMean,
                    femaleMean,
                    medianGap,
                    meanGap,
                    reviewThresholdMet,
                    reviewReason(suppressed, reviewThresholdMet),
                    suppressed));
            anySuppressed |= suppressed;
        }
        data.sort(java.util.Comparator.comparingInt(PayEquityRow::sortOrder));
        long eligible = overallEligible == null ? groupedEligible : overallEligible;
        long population = payPopulationCount(filters);
        long excluded = Math.max(0, population - eligible);
        if (complementSmallExcludedPayEquity(data, excluded)) anySuppressed = true;
        List<String> caveats = new ArrayList<>(List.of(
                "Pay is the latest contractual salary on or before the reporting date; owner cost overrides are never used.",
                filters.salaryType() == PeopleSalaryType.NORMAL
                        ? "NORMAL pay is monthly DKK normalized to 37 hours; salaries below 1,000 and zero allocations are excluded."
                        : "HOURLY pay is the raw contractual DKK-per-hour rate in a separate view.",
                "Median gap = (male median − female median) / male median × 100; mean gap uses the same signed formula with means.",
                "reviewThresholdMet is a neutral screen for an absolute mean contractual-pay gap of at least 5%; it is not a finding of discrimination or a legal joint-pay-assessment trigger.",
                filters.compensationGroup() == PeopleCompensationGroup.DISCO_FUNCTION
                        ? "DISCO-08 functions and statutory employee categories are the latest classifications on or before the reporting date; they support like-work readiness screening but do not alone validate equal-value categories."
                        : "Career bands are management groupings, not legally validated equal-work or equal-value worker categories.",
                "Contractual salary excludes variable pay, supplements, lump sums, pension, and other remuneration required for a complete pay-transparency report."));
        if (filters.compensationGroup() == PeopleCompensationGroup.DISCO_FUNCTION) {
            caveats.add(discoCoverageCaveat(eligible, unassignedGrouping));
            caveats.add(filters.companyId() == null
                    ? "Group scope spans multiple legal employers, so no Danish reporting-eligibility conclusion may be drawn from these combined counts; select one company first."
                    : "For a single company, the current Danish threshold of at least ten women and ten men in the same six-digit DISCO function and statutory employee category can be pre-screened from the displayed counts; legal applicability remains an employer assessment.");
            caveats.add("SPECIAL_EMPLOYEES remains a separate source category; its statutory mapping requires HR/legal confirmation rather than automatic reassignment.");
            caveats.add("This is a current-survivor contractual-pay pre-screen. Danish statutory statistics use prior-calendar-year gross pay, include leavers and variable/in-kind remuneration, and cannot be reproduced by this endpoint.");
        }
        return new Response<>(meta(filters, filters.asOfDate(), filters.asOfDate(), null, null,
                anySuppressed ? -1 : eligible, excluded, suppresses(eligible), YearMonth.from(filters.asOfDate()),
                caveats), data);
    }

    public Response<List<PayQuartileRow>> payQuartiles(PeopleFilterParams filters) {
        requireEmployedPopulation(filters, "pay-quartiles");
        String salaryExpression = salaryExpression(filters.salaryType(), "cs", "fp");
        String salaryPredicate = salaryPredicate(filters.salaryType(), "cs", "fp");
        String sql = "WITH " + PeoplePopulationSqlSupport.snapshotPopulationCtes(filters, "asOfDate") +
                ", salary_ranked AS (" +
                " SELECT s.*,ROW_NUMBER() OVER (PARTITION BY s.useruuid ORDER BY " +
                salaryTemporalOrder("s") + ") rn" +
                " FROM salary s WHERE s.activefrom<=:asOfDate" +
                "), current_salary AS (SELECT * FROM salary_ranked WHERE rn=1)," +
                " eligible_pay AS (" +
                " SELECT fp.useruuid,fp.gender," + salaryExpression + " pay_value" +
                " FROM filtered_population fp JOIN current_salary cs ON cs.useruuid=fp.useruuid" +
                " WHERE " + salaryPredicate + " AND fp.gender IN ('MALE','FEMALE')" +
                "), ranked_pay AS (" +
                " SELECT eligible_pay.*,NTILE(4) OVER (ORDER BY pay_value,useruuid) quartile_number" +
                " FROM eligible_pay" +
                ") SELECT quartile_number," +
                " COUNT(DISTINCT CASE WHEN gender='MALE' THEN useruuid END) male_count," +
                " COUNT(DISTINCT CASE WHEN gender='FEMALE' THEN useruuid END) female_count" +
                " FROM ranked_pay GROUP BY quartile_number ORDER BY quartile_number";
        Map<String, Object> bindings = PeoplePopulationSqlSupport.snapshotBindings(
                filters, "asOfDate", filters.asOfDate());
        bindings.put("salaryType", filters.salaryType().name());
        List<Tuple> rows = repository.tuples("pay-quartiles", sql, bindings);
        Map<Integer, long[]> counts = new LinkedHashMap<>();
        for (Tuple row : rows) {
            counts.put((int) toLong(row.get("quartile_number")), new long[]{
                    toLong(row.get("male_count")), toLong(row.get("female_count"))});
        }
        List<PayQuartileRow> data = new ArrayList<>(4);
        long eligible = 0;
        boolean anySuppressed = false;
        for (int quartile = 1; quartile <= 4; quartile++) {
            long[] genderCounts = counts.getOrDefault(quartile, new long[]{0, 0});
            long male = genderCounts[0];
            long female = genderCounts[1];
            long total = male + female;
            eligible += total;
            boolean suppressed = false; // privacy floor removed — ADMIN-only full-detail view
            data.add(new PayQuartileRow(
                    quartileKey(quartile),
                    quartileLabel(quartile),
                    quartile - 1,
                    filters.salaryType().name(),
                    visibleCount(male, suppressed),
                    visibleCount(female, suppressed),
                    percentage(male, total, suppressed),
                    percentage(female, total, suppressed),
                    suppressed));
            anySuppressed |= suppressed;
        }
        long population = payPopulationCount(filters);
        long excluded = Math.max(0, population - eligible);
        if (suppressQuartileComplement(data, suppresses(excluded))) anySuppressed = true;
        List<String> caveats = new ArrayList<>(List.of(
                "Quartiles use NTILE(4) over the eligible current snapshot, ordered from lowest to highest contractual base pay.",
                "NORMAL pay is normalized to 37 weekly hours; HOURLY pay remains a separate unit and is never mixed with NORMAL.",
                "This is a current-survivor contractual-base-pay distribution, not prior-calendar-year statutory gross pay.",
                "Variable pay, supplements, lump sums, pension, benefits in kind, and leavers are not included; equal-work/equal-value categories are not validated."));
        caveats.add(filters.companyId() == null
                ? "Group scope spans multiple legal employers, so the quartiles cannot be used as a legal-employer reporting result."
                : "Single-company scope is an analytical pre-screen only; legal reporting eligibility and completeness require HR/legal review.");
        return new Response<>(meta(filters, filters.asOfDate(), filters.asOfDate(), null, null,
                anySuppressed ? -1 : eligible, excluded, suppresses(eligible), YearMonth.from(filters.asOfDate()),
                caveats), data);
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
            boolean suppressed = false; // privacy floor removed — ADMIN-only full-detail view
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
                        "Excluded count is the reporting-date employed population without an eligible contractual pay record.")), data);
    }

    private String retentionSnapshotScope(PeopleFilterParams filters) {
        StringBuilder sql = new StringBuilder(
                "ss.`type` IN (:employeeTypes) AND ss.status IN (:populationStatuses)");
        if (filters.companyId() != null) sql.append(" AND ss.companyuuid=:companyId");
        if (!filters.practices().isEmpty()) sql.append(" AND COALESCE((SELECT prc.code FROM practice prc WHERE prc.uuid = u.practice_uuid), 'UD') IN (:practices)");
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
        if (!filters.practices().isEmpty()) sql.append(" AND COALESCE((SELECT prc.code FROM practice prc WHERE prc.uuid = u.practice_uuid), 'UD') IN (:practices)");
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

    private static String compensationGroupExpression(
            PeopleCompensationGroup group, String alias, String dstAlias) {
        return switch (group) {
            case CAREER_BAND -> HrCareerBandMapper.toSqlCase(alias + ".career_level");
            case PRACTICE -> // Phase 4: NULL practice IS the 'UD' member (pre-flip rows stored 'UD')
                    "COALESCE(" + alias + ".practice,'UD')";
            case CAREER_TRACK -> "CASE WHEN " + alias + ".career_level='JUNIOR_CONSULTANT' THEN 'ENTRY'" +
                    " WHEN " + alias + ".career_track IS NULL THEN 'UNASSIGNED' ELSE " + alias + ".career_track END";
            case DISCO_FUNCTION -> "CONCAT(" + discoCodeCase(dstAlias) + ",'|'," +
                    "COALESCE(" + dstAlias + ".job_status,'UNASSIGNED'))";
        };
    }

    private static String compensationGroupLabel(PeopleCompensationGroup group, String value) {
        if (OVERALL_GROUP.equals(value)) return "Overall eligible population";
        if (group != PeopleCompensationGroup.DISCO_FUNCTION) return value;
        String[] parts = value.split("\\|", -1);
        String code = parts.length > 0 ? parts[0] : "UNASSIGNED";
        String status = parts.length > 1 ? parts[1] : "UNASSIGNED";
        String functionLabel = "UNASSIGNED".equals(code)
                ? "Unassigned / missing DISCO-08"
                : code + " · " + discoFunctionLabel(code);
        String statusLabel = "UNASSIGNED".equals(status) ? "Unassigned employee category" : status;
        try {
            statusLabel = DstEmploymentStatus.valueOf(status).getName();
        } catch (IllegalArgumentException ignored) {
            // Preserve an unknown stored category verbatim for data-quality review.
        }
        return functionLabel + " — " + statusLabel;
    }

    private int compensationGroupSort(PeopleCompensationGroup group, String value) {
        if (OVERALL_GROUP.equals(value)) return -1;
        return switch (group) {
            case CAREER_BAND -> HrCareerBandMapper.sortOrder(value);
            case PRACTICE -> {
                // Registry-derived business order (Phase 3): every registry code
                // in sort_order — including the UD bucket — then Unassigned.
                List<String> order = new ArrayList<>(practiceService.orderedRegistryCodes());
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
            case DISCO_FUNCTION -> {
                String[] parts = value.split("\\|", -1);
                if (parts.length < 2 || "UNASSIGNED".equals(parts[0])) yield Integer.MAX_VALUE;
                try {
                    int code = Integer.parseInt(parts[0]);
                    int statusOrder = "UNASSIGNED".equals(parts[1])
                            ? 99 : DstEmploymentStatus.valueOf(parts[1]).ordinal();
                    yield code * 100 + statusOrder;
                } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ignored) {
                    yield Integer.MAX_VALUE - 1;
                }
            }
        };
    }

    static String discoCodeCase(String alias) {
        StringBuilder sql = new StringBuilder("CASE ").append(alias).append(".employement_function");
        for (DstEmploymentFunction function : DstEmploymentFunction.values()) {
            sql.append(" WHEN '").append(function.name()).append("' THEN '")
                    .append(function.getCode()).append("'");
        }
        return sql.append(" ELSE 'UNASSIGNED' END").toString();
    }

    static String discoGroupKey(DstEmploymentFunction function, DstEmploymentStatus status) {
        return function.getCode() + "|" + status.name();
    }

    private static String discoFunctionLabel(String code) {
        return java.util.Arrays.stream(DstEmploymentFunction.values())
                .filter(function -> String.valueOf(function.getCode()).equals(code))
                .map(DstEmploymentFunction::getName)
                .distinct()
                .collect(java.util.stream.Collectors.joining(" / "));
    }

    static String discoCoverageCaveat(long eligible, long unassigned) {
        long classified = Math.max(0, eligible - unassigned);
        if (eligible == 0) {
            return "No eligible recorded-gender contractual-pay records are available for DISCO-08 coverage.";
        }
        if (suppresses(unassigned)) {
            return "DISCO-08 coverage is available for the eligible recorded-gender contractual-pay population; the missing classification count is privacy-suppressed.";
        }
        return "DISCO-08 function/category classification covers " + classified + " of " + eligible +
                " eligible recorded-gender contractual-pay records; " + unassigned + " remain unassigned.";
    }

    static String quartileKey(int quartile) {
        return switch (quartile) {
            case 1 -> "Q1_LOWEST";
            case 2 -> "Q2_LOWER_MIDDLE";
            case 3 -> "Q3_UPPER_MIDDLE";
            case 4 -> "Q4_HIGHEST";
            default -> throw new IllegalArgumentException("quartile must be between 1 and 4");
        };
    }

    private static String quartileLabel(int quartile) {
        return switch (quartile) {
            case 1 -> "Q1 · Lowest pay";
            case 2 -> "Q2 · Lower middle";
            case 3 -> "Q3 · Upper middle";
            case 4 -> "Q4 · Highest pay";
            default -> throw new IllegalArgumentException("quartile must be between 1 and 4");
        };
    }

    static boolean suppressQuartileComplement(List<PayQuartileRow> rows) {
        return suppressQuartileComplement(rows, false);
    }

    static boolean suppressQuartileComplement(List<PayQuartileRow> rows, boolean forceComplement) {
        if (!forceComplement && rows.stream().noneMatch(PayQuartileRow::suppressed)) return false;
        int candidate = java.util.stream.IntStream.range(0, rows.size())
                .boxed()
                .filter(index -> !rows.get(index).suppressed())
                .min(java.util.Comparator
                        .comparingLong((Integer index) -> rows.get(index).maleCount() + rows.get(index).femaleCount())
                        .thenComparing(index -> rows.get(index).key()))
                .orElse(-1);
        if (candidate < 0) return false;
        PayQuartileRow row = rows.get(candidate);
        rows.set(candidate, new PayQuartileRow(
                row.key(), row.label(), row.sortOrder(), row.salaryType(),
                null, null, null, null, true));
        return true;
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
        int overallIndex = java.util.stream.IntStream.range(0, rows.size())
                .boxed()
                .filter(index -> OVERALL_GROUP.equals(rows.get(index).groupKey()))
                .findFirst()
                .orElse(-1);
        if (overallIndex < 0 || rows.get(overallIndex).suppressed()) return false;

        long hiddenPartitions = rows.stream()
                .filter(row -> !OVERALL_GROUP.equals(row.groupKey()) && row.suppressed())
                .count();
        boolean needsComplement = hiddenPartitions == 1 || suppresses(excluded);
        if (!needsComplement || hiddenPartitions >= 2) return false;

        int requiredComplements = (int) (2 - hiddenPartitions);
        List<Integer> candidates = java.util.stream.IntStream.range(0, rows.size())
                .boxed()
                .filter(index -> !OVERALL_GROUP.equals(rows.get(index).groupKey()))
                .filter(index -> !rows.get(index).suppressed())
                .filter(index -> rows.get(index).maleCount() != null && rows.get(index).femaleCount() != null)
                .sorted(java.util.Comparator
                        .comparingLong((Integer index) -> rows.get(index).maleCount() + rows.get(index).femaleCount())
                        .thenComparing(index -> rows.get(index).groupKey()))
                .toList();
        int complements = Math.min(requiredComplements, candidates.size());
        for (int index = 0; index < complements; index++) {
            int candidate = candidates.get(index);
            rows.set(candidate, suppressedPayEquityRow(
                    rows.get(candidate), "COMPLEMENTARY_PRIVACY_SUPPRESSION"));
        }
        if (hiddenPartitions + complements < 2) {
            rows.set(overallIndex, suppressedPayEquityRow(
                    rows.get(overallIndex), "OVERALL_PRIVACY_SUPPRESSION"));
        }
        return complements > 0 || hiddenPartitions + complements < 2;
    }

    private static PayEquityRow suppressedPayEquityRow(PayEquityRow row, String reason) {
        return new PayEquityRow(
                row.groupKey(), row.groupLabel(), row.sortOrder(), row.salaryType(),
                null, null, null, null, null, null, null, null, null,
                reason, true);
    }

    static Double signedGapPct(Double maleValue, Double femaleValue) {
        if (maleValue == null || femaleValue == null || maleValue == 0d) return null;
        return round2((maleValue - femaleValue) / maleValue * 100.0d);
    }

    static String reviewReason(boolean suppressed, Boolean reviewThresholdMet) {
        if (suppressed) return "PAY_CELL_BELOW_PRIVACY_THRESHOLD";
        if (reviewThresholdMet == null) return "INSUFFICIENT_ELIGIBLE_PAY_DATA";
        return reviewThresholdMet
                ? "OBSERVED_ABSOLUTE_MEAN_CONTRACTUAL_PAY_GAP_AT_LEAST_FIVE_PERCENT"
                : "OBSERVED_ABSOLUTE_MEAN_CONTRACTUAL_PAY_GAP_BELOW_FIVE_PERCENT";
    }

    static List<RetentionCohortPoint> privacySafeMilestones(
            long cohortSize, List<CohortObservation> observations, int horizonMonths) {
        if (suppresses(cohortSize)) return List.of();
        List<RetentionCohortPoint> points = new ArrayList<>();
        points.add(new RetentionCohortPoint(
                0, 0, cohortSize, 0L, 0L, cohortSize, 100.0d,
                false, false, null));
        double survival = 1.0d;
        long pendingIntervalEvents = 0;
        int intervalStartMonth = 0;
        for (CohortObservation observation : observations.stream()
                .sorted(java.util.Comparator.comparingInt(CohortObservation::month)).toList()) {
            if (observation.month() > horizonMonths) break;
            if (observation.atRisk() > 0) {
                survival *= 1.0d - ((double) observation.events() / observation.atRisk());
            }
            pendingIntervalEvents += observation.events();
            if (observation.month() == 0 || !COHORT_MILESTONES.contains(observation.month())) continue;

            boolean atRiskSuppressed = suppresses(observation.atRisk());
            boolean intervalEventsSuppressed = suppresses(pendingIntervalEvents);
            boolean pointSuppressed = atRiskSuppressed || intervalEventsSuppressed;
            points.add(new RetentionCohortPoint(
                    observation.month(),
                    intervalStartMonth,
                    visibleCount(observation.atRisk(), pointSuppressed),
                    visibleCount(pendingIntervalEvents, pointSuppressed),
                    visibleCount(pendingIntervalEvents, pointSuppressed),
                    null,
                    pointSuppressed ? null : round2(survival * 100.0d),
                    pointSuppressed,
                    intervalEventsSuppressed,
                    atRiskSuppressed ? "AT_RISK_BELOW_PRIVACY_THRESHOLD"
                            : intervalEventsSuppressed ? "INTERVAL_EVENTS_BELOW_PRIVACY_THRESHOLD" : null));
            if (!pointSuppressed) {
                intervalStartMonth = observation.month();
                pendingIntervalEvents = 0;
            }
        }
        return List.copyOf(points);
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

    record CohortObservation(int month, long atRisk, long events) {
    }

    private static final class CohortAccumulator {
        private final long cohortSize;
        private final boolean cohortSuppressed;
        private final List<CohortObservation> observations = new ArrayList<>();

        private CohortAccumulator(long cohortSize) {
            this.cohortSize = cohortSize;
            this.cohortSuppressed = suppresses(cohortSize);
        }
    }
}
