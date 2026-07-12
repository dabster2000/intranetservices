package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.GenderTrendPoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.HeadcountCompositionPoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.Response;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.StatusTrendPoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.TenureBand;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.UpcomingChangeDetail;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.UpcomingChangeSummary;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.UpcomingChanges;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.WorkforceFlowPoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.WorkforceSummary;
import dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsRepository;
import dk.trustworks.intranet.aggregates.executive.people.PeopleComplementarySuppression;
import dk.trustworks.intranet.aggregates.executive.people.PeopleEmploymentSpellSupport;
import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterParams;
import dk.trustworks.intranet.aggregates.executive.people.PeoplePopulationSqlSupport;
import dk.trustworks.intranet.aggregates.executive.people.PeopleWorkforceEventType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.Tuple;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.toDouble;
import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.toLong;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.meta;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.percentage;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.round2;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.suppresses;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.visibleCount;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.visibleNumber;

/** Workforce metrics built on the canonical temporal population. */
@ApplicationScoped
public class ExecutivePeopleWorkforceService {

    private static final List<String> WORKFORCE_CAVEATS = List.of(
            "Internal employees are CONSULTANT, STAFF, or STUDENT with ACTIVE or leave status.",
            "EXTERNAL contractors are reported separately and never enter employee or FTE totals.",
            "Practice is current-state only; historical practice filters are not retroactive.");

    @Inject
    PeopleAnalyticsRepository repository;

    public Response<WorkforceSummary> workforceSummary(PeopleFilterParams filters) {
        PeopleFilterParams summaryFilters = employedFilters(filters);
        String sql = "WITH " + PeoplePopulationSqlSupport.snapshotPopulationCtes(summaryFilters, "asOfDate") +
                " SELECT" +
                " COUNT(DISTINCT fp.useruuid) employee_count," +
                " COUNT(DISTINCT CASE WHEN fp.status = 'ACTIVE' THEN fp.useruuid END) active_count," +
                " COUNT(DISTINCT CASE WHEN fp.status IN ('PAID_LEAVE','MATERNITY_LEAVE','NON_PAY_LEAVE') THEN fp.useruuid END) leave_count," +
                " COALESCE(SUM(fp.allocation), 0) / 37.0 contracted_fte," +
                " COALESCE(SUM(CASE WHEN fp.status = 'ACTIVE' THEN fp.allocation ELSE 0 END), 0) / 37.0 active_fte," +
                " (SELECT COUNT(DISTINCT ext.useruuid)" +
                "  FROM latest_status ext JOIN `user` eu ON eu.uuid = ext.useruuid" +
                "  WHERE " + PeoplePopulationSqlSupport.externalSnapshotFilters(summaryFilters, "ext", "eu") +
                " ) external_count" +
                " FROM filtered_population fp";

        Tuple row = repository.tuples("workforce-summary", sql,
                PeoplePopulationSqlSupport.snapshotBindings(summaryFilters, "asOfDate", filters.asOfDate())).getFirst();
        long employees = toLong(row.get("employee_count"));
        long active = toLong(row.get("active_count"));
        long leave = toLong(row.get("leave_count"));
        long external = toLong(row.get("external_count"));
        boolean suppressed = suppresses(employees);
        boolean breakdownSuppressed = suppressed || suppresses(active) || suppresses(leave);

        WorkforceSummary data = new WorkforceSummary(
                visibleCount(employees, breakdownSuppressed),
                visibleCount(active, breakdownSuppressed),
                visibleCount(leave, breakdownSuppressed),
                visibleCount(external, suppresses(external)),
                visibleNumber(toDouble(row.get("contracted_fte")), breakdownSuppressed),
                visibleNumber(toDouble(row.get("active_fte")), breakdownSuppressed));
        return new Response<>(meta(filters, filters.asOfDate(), filters.asOfDate(), null, null,
                breakdownSuppressed ? -1 : employees, 0, suppressed, YearMonth.from(filters.asOfDate()),
                List.of(
                        "Employee Count is always the canonical EMPLOYED population: ACTIVE plus the three leave statuses.",
                        "The population filter is not applicable to this invariant summary endpoint.",
                        "EXTERNAL contractors are reported separately and never enter employee or FTE totals.")), data);
    }

    public Response<List<HeadcountCompositionPoint>> headcountComposition(PeopleFilterParams filters) {
        String condition = PeoplePopulationSqlSupport.snapshotFilters(
                filters, "msr", "u", "mc", "ms.snapshot_date");
        String externalCondition = PeoplePopulationSqlSupport.externalSnapshotFilters(filters, "msr", "u");
        String sql = "WITH " + PeoplePopulationSqlSupport.monthlyPopulationCtes() +
                " SELECT ms.snapshot_date," +
                countCase(condition + " AND msr.`type` = 'CONSULTANT'", "consultant") +
                countCase(condition + " AND msr.`type` = 'STAFF'", "staff") +
                countCase(condition + " AND msr.`type` = 'STUDENT'", "student") +
                countCase(externalCondition, "external") +
                countCase(condition, "employee_total") +
                " COALESCE(SUM(CASE WHEN " + condition + " THEN msr.allocation ELSE 0 END),0) / 37.0 contracted_fte" +
                " FROM month_spine ms" +
                " LEFT JOIN monthly_status msr ON msr.snapshot_date = ms.snapshot_date" +
                " LEFT JOIN `user` u ON u.uuid = msr.useruuid" +
                " LEFT JOIN monthly_career mc ON mc.snapshot_date = ms.snapshot_date AND mc.useruuid = msr.useruuid" +
                " GROUP BY ms.snapshot_date ORDER BY ms.snapshot_date";

        List<Tuple> rows = repository.tuples("headcount-composition", sql,
                PeoplePopulationSqlSupport.trendBindings(filters));
        List<HeadcountCompositionPoint> data = new ArrayList<>(rows.size());
        long latestSample = 0;
        boolean latestSuppressed = false;
        for (Tuple row : rows) {
            long consultant = toLong(row.get("consultant"));
            long staff = toLong(row.get("staff"));
            long student = toLong(row.get("student"));
            long external = toLong(row.get("external"));
            long total = toLong(row.get("employee_total"));
            boolean pointSuppressed = suppresses(total);
            Map<String, Long> partition = Map.of(
                    "CONSULTANT", consultant, "STAFF", staff, "STUDENT", student);
            Set<String> hidden = PeopleComplementarySuppression.suppressedKeys(partition);
            boolean anyInternalCellSuppressed = pointSuppressed || !hidden.isEmpty();
            latestSuppressed = anyInternalCellSuppressed;
            data.add(new HeadcountCompositionPoint(
                    localDate(row.get("snapshot_date")),
                    visibleCount(consultant, pointSuppressed || hidden.contains("CONSULTANT")),
                    visibleCount(staff, pointSuppressed || hidden.contains("STAFF")),
                    visibleCount(student, pointSuppressed || hidden.contains("STUDENT")),
                    visibleCount(external, suppresses(external)),
                    visibleCount(total, anyInternalCellSuppressed),
                    visibleNumber(toDouble(row.get("contracted_fte")), anyInternalCellSuppressed)));
            latestSample = total;
        }
        return trendResponse(filters, data, latestSuppressed ? -1 : latestSample, suppresses(latestSample), WORKFORCE_CAVEATS);
    }

    public Response<List<StatusTrendPoint>> statusTrend(PeopleFilterParams filters) {
        String condition = PeoplePopulationSqlSupport.snapshotFilters(
                filters, "msr", "u", "mc", "ms.snapshot_date");
        String sql = "WITH " + PeoplePopulationSqlSupport.monthlyPopulationCtes() +
                " SELECT ms.snapshot_date," +
                countCase(condition + " AND msr.status = 'ACTIVE'", "active") +
                countCase(condition + " AND msr.status = 'PAID_LEAVE'", "paid_leave") +
                countCase(condition + " AND msr.status = 'MATERNITY_LEAVE'", "maternity_leave") +
                countCase(condition + " AND msr.status = 'NON_PAY_LEAVE'", "non_pay_leave") +
                countCase(condition, "employee_total").replaceFirst(",$", "") +
                " FROM month_spine ms" +
                " LEFT JOIN monthly_status msr ON msr.snapshot_date = ms.snapshot_date" +
                " LEFT JOIN `user` u ON u.uuid = msr.useruuid" +
                " LEFT JOIN monthly_career mc ON mc.snapshot_date = ms.snapshot_date AND mc.useruuid = msr.useruuid" +
                " GROUP BY ms.snapshot_date ORDER BY ms.snapshot_date";

        List<Tuple> rows = repository.tuples("status-trend", sql, PeoplePopulationSqlSupport.trendBindings(filters));
        List<StatusTrendPoint> data = new ArrayList<>(rows.size());
        long latestSample = 0;
        boolean latestSuppressed = false;
        for (Tuple row : rows) {
            long active = toLong(row.get("active"));
            long paid = toLong(row.get("paid_leave"));
            long maternity = toLong(row.get("maternity_leave"));
            long nonPay = toLong(row.get("non_pay_leave"));
            long total = toLong(row.get("employee_total"));
            long onLeave = paid + maternity + nonPay;
            boolean pointSuppressed = suppresses(total);
            boolean activeLeaveSuppressed = pointSuppressed || suppresses(active) || suppresses(onLeave);
            boolean subtypeSuppressed = suppresses(paid) || suppresses(maternity) || suppresses(nonPay);
            latestSuppressed = activeLeaveSuppressed;
            data.add(new StatusTrendPoint(
                    localDate(row.get("snapshot_date")),
                    visibleCount(active, activeLeaveSuppressed),
                    visibleCount(onLeave, activeLeaveSuppressed),
                    visibleCount(paid, pointSuppressed || subtypeSuppressed),
                    visibleCount(maternity, pointSuppressed || subtypeSuppressed),
                    visibleCount(nonPay, pointSuppressed || subtypeSuppressed),
                    visibleCount(total, activeLeaveSuppressed),
                    activeLeaveSuppressed,
                    activeLeaveSuppressed ? "BELOW_PRIVACY_THRESHOLD" : null));
            latestSample = total;
        }
        return trendResponse(filters, data, latestSuppressed ? -1 : latestSample, suppresses(latestSample), WORKFORCE_CAVEATS);
    }

    public Response<List<GenderTrendPoint>> genderTrend(PeopleFilterParams filters) {
        String condition = PeoplePopulationSqlSupport.snapshotFilters(
                filters, "msr", "u", "mc", "ms.snapshot_date");
        String sql = "WITH " + PeoplePopulationSqlSupport.monthlyPopulationCtes() +
                " SELECT ms.snapshot_date," +
                countCase(condition + " AND u.gender = 'MALE'", "male") +
                countCase(condition + " AND u.gender = 'FEMALE'", "female") +
                countCase(condition + " AND (u.gender IS NULL OR u.gender NOT IN ('MALE','FEMALE'))", "unknown") +
                countCase(condition, "total").replaceFirst(",$", "") +
                " FROM month_spine ms" +
                " LEFT JOIN monthly_status msr ON msr.snapshot_date = ms.snapshot_date" +
                " LEFT JOIN `user` u ON u.uuid = msr.useruuid" +
                " LEFT JOIN monthly_career mc ON mc.snapshot_date = ms.snapshot_date AND mc.useruuid = msr.useruuid" +
                " GROUP BY ms.snapshot_date ORDER BY ms.snapshot_date";

        List<Tuple> rows = repository.tuples("gender-trend-v2", sql, PeoplePopulationSqlSupport.trendBindings(filters));
        List<GenderTrendPoint> data = new ArrayList<>(rows.size());
        long latestSample = 0;
        boolean latestSuppressed = false;
        for (Tuple row : rows) {
            long male = toLong(row.get("male"));
            long female = toLong(row.get("female"));
            long unknown = toLong(row.get("unknown"));
            long total = toLong(row.get("total"));
            boolean pointSuppressed = suppresses(total);
            Map<String, Long> partition = Map.of("MALE", male, "FEMALE", female, "UNKNOWN", unknown);
            Set<String> hidden = PeopleComplementarySuppression.suppressedKeys(partition);
            boolean anyGenderSuppressed = pointSuppressed || !hidden.isEmpty();
            latestSuppressed = anyGenderSuppressed;
            data.add(new GenderTrendPoint(
                    localDate(row.get("snapshot_date")),
                    visibleCount(male, pointSuppressed || hidden.contains("MALE")),
                    visibleCount(female, pointSuppressed || hidden.contains("FEMALE")),
                    visibleCount(unknown, pointSuppressed || hidden.contains("UNKNOWN")),
                    visibleCount(total, anyGenderSuppressed),
                    percentage(female, total, anyGenderSuppressed)));
            latestSample = total;
        }
        return trendResponse(filters, data, latestSuppressed ? -1 : latestSample, suppresses(latestSample),
                List.of(
                        "Gender representation includes unknown or unrecorded values.",
                        "Female percentage uses the full displayed population, including unknown gender, as its denominator."));
    }

    public Response<List<WorkforceFlowPoint>> workforceFlow(PeopleFilterParams filters) {
        String scopedEvent = eventScope(filters);
        String companyIn = filters.companyId() == null ? "1=1" : "h.companyuuid = :companyId";
        String companyOut = filters.companyId() == null ? "1=1" : "h.previous_companyuuid = :companyId";
        String isEmployed = PeoplePopulationSqlSupport.internalEmployedPredicate("h");
        String wasEmployed = previousEmployedPredicate("h");
        String isTransfer = isEmployed + " AND " + wasEmployed +
                " AND NOT (h.companyuuid <=> h.previous_companyuuid)";
        String sameCompanyRehire = "h.same_company_rehire=1 AND COALESCE(h.prior_employed_rows,0)>0";
        String sql = "WITH " + PeoplePopulationSqlSupport.monthSpineCte() +
                ", " + PeoplePopulationSqlSupport.canonicalStatusDayCtes("event", ":asOfDate") +
                ", history AS (" +
                " SELECT esd.*," +
                " LAG(esd.status) OVER w previous_status," +
                " LAG(esd.`type`) OVER w previous_type," +
                " LAG(esd.companyuuid) OVER w previous_companyuuid," +
                " SUM(CASE WHEN " + PeoplePopulationSqlSupport.internalEmployedPredicate("esd") +
                " THEN 1 ELSE 0 END)" +
                " OVER (PARTITION BY esd.useruuid ORDER BY esd.statusdate,esd.created_at,esd.uuid" +
                " ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) prior_employed_rows" +
                " FROM event_status_day esd" +
                " WINDOW w AS (PARTITION BY esd.useruuid ORDER BY esd.statusdate,esd.created_at,esd.uuid)" +
                "), scoped_events AS (" +
                " SELECT h.*, u.practice, ec.career_track, ec.career_level," +
                " CASE WHEN " + isEmployed + " AND NOT (" + wasEmployed + ")" +
                "  AND COALESCE(h.prior_employed_rows,0)=0 AND " + companyIn + " THEN 1 ELSE 0 END first_hire," +
                " CASE WHEN ((" + isEmployed + " AND NOT (" + wasEmployed + ")" +
                "  AND COALESCE(h.prior_employed_rows,0)>0) OR (" + sameCompanyRehire + "))" +
                "  AND " + companyIn + " THEN 1 ELSE 0 END rehire," +
                " CASE WHEN ((" + wasEmployed + " AND NOT (" + isEmployed + "))" +
                "  OR (" + sameCompanyRehire + ")) AND " + companyOut + " THEN 1 ELSE 0 END departure," +
                " CASE WHEN " + isTransfer + " AND " + companyIn + " THEN 1 ELSE 0 END transfer_in," +
                " CASE WHEN " + isTransfer + " AND " + companyOut + " THEN 1 ELSE 0 END transfer_out," +
                " CASE WHEN " + isTransfer + " THEN 1 ELSE 0 END company_transfer," +
                " CASE WHEN h.status IN ('PAID_LEAVE','MATERNITY_LEAVE','NON_PAY_LEAVE')" +
                "  AND h.previous_status='ACTIVE' AND " + companyIn + " THEN 1 ELSE 0 END leave_start," +
                " CASE WHEN h.status='ACTIVE' AND h.previous_status IN ('PAID_LEAVE','MATERNITY_LEAVE','NON_PAY_LEAVE')" +
                "  AND " + companyIn + " THEN 1 ELSE 0 END leave_return" +
                " FROM history h JOIN `user` u ON u.uuid = h.useruuid" +
                " LEFT JOIN user_career_level ec ON ec.useruuid=h.useruuid AND ec.active_from<=h.statusdate" +
                "  AND NOT EXISTS (SELECT 1 FROM user_career_level newer" +
                "   WHERE newer.useruuid=ec.useruuid AND newer.active_from<=h.statusdate" +
                "   AND (newer.active_from>ec.active_from" +
                "    OR (newer.active_from=ec.active_from AND newer.created_at>ec.created_at)" +
                "    OR (newer.active_from=ec.active_from AND newer.created_at=ec.created_at AND newer.uuid>ec.uuid)))" +
                " WHERE h.statusdate BETWEEN :periodStart AND :asOfDate AND " + scopedEvent +
                "), monthly AS (" +
                " SELECT DATE_FORMAT(statusdate,'%Y-%m') month_key," +
                flagCount("first_hire", "first_hires") +
                flagCount("rehire", "rehires") +
                flagCount("departure", "departures") +
                flagCount("transfer_in", "transfers_in") +
                flagCount("transfer_out", "transfers_out") +
                flagCount("leave_start", "leave_starts") +
                flagCount("leave_return", "leave_returns") +
                " COUNT(DISTINCT CASE WHEN first_hire+rehire+departure+transfer_in+transfer_out+leave_start+leave_return>0" +
                " THEN useruuid END) sample_size" +
                " FROM scoped_events GROUP BY DATE_FORMAT(statusdate,'%Y-%m')" +
                ")" +
                " SELECT DATE_FORMAT(ms.snapshot_date,'%Y-%m') month_key," +
                " COALESCE(m.first_hires,0) first_hires, COALESCE(m.rehires,0) rehires," +
                " COALESCE(m.departures,0) departures, COALESCE(m.transfers_in,0) transfers_in," +
                " COALESCE(m.transfers_out,0) transfers_out, COALESCE(m.leave_starts,0) leave_starts," +
                " COALESCE(m.leave_returns,0) leave_returns, COALESCE(m.sample_size,0) sample_size" +
                " FROM month_spine ms LEFT JOIN monthly m ON m.month_key = DATE_FORMAT(ms.snapshot_date,'%Y-%m')" +
                " ORDER BY ms.snapshot_date";

        Map<String, Object> bindings = PeoplePopulationSqlSupport.trendBindings(filters);
        bindings.keySet().removeIf(key -> List.of("populationStatuses", "leaveStatuses").contains(key));
        List<Tuple> rows = repository.tuples("workforce-flow", sql, bindings);
        List<WorkforceFlowPoint> data = new ArrayList<>(rows.size());
        long latestSample = 0;
        boolean anyPartialSuppression = false;
        for (Tuple row : rows) {
            long first = toLong(row.get("first_hires"));
            long rehires = toLong(row.get("rehires"));
            long departures = toLong(row.get("departures"));
            long transferIn = toLong(row.get("transfers_in"));
            long transferOut = toLong(row.get("transfers_out"));
            long leaveStarts = toLong(row.get("leave_starts"));
            long leaveReturns = toLong(row.get("leave_returns"));
            long sample = toLong(row.get("sample_size"));
            boolean rowSuppressed = suppresses(sample);
            boolean componentSuppressed = rowSuppressed || suppresses(first) || suppresses(rehires)
                    || suppresses(departures) || suppresses(transferIn) || suppresses(transferOut)
                    || suppresses(leaveStarts) || suppresses(leaveReturns);
            data.add(new WorkforceFlowPoint(
                    row.get("month_key", String.class),
                    visibleCount(first, rowSuppressed || suppresses(first)),
                    visibleCount(rehires, rowSuppressed || suppresses(rehires)),
                    visibleCount(departures, rowSuppressed || suppresses(departures)),
                    visibleCount(transferIn, rowSuppressed || suppresses(transferIn)),
                    visibleCount(transferOut, rowSuppressed || suppresses(transferOut)),
                    visibleCount(leaveStarts, rowSuppressed || suppresses(leaveStarts)),
                    visibleCount(leaveReturns, rowSuppressed || suppresses(leaveReturns)),
                    visibleCount(first + rehires + transferIn - departures - transferOut, componentSuppressed)));
            latestSample = sample;
            anyPartialSuppression |= componentSuppressed;
        }
        return new Response<>(meta(filters, PeoplePopulationSqlSupport.periodStart(filters), filters.asOfDate(),
                filters.months(), null, anyPartialSuppression ? -1 : latestSample, 0,
                suppresses(latestSample), YearMonth.from(filters.asOfDate()),
                List.of(
                        "First hires, rehires, departures, company transfers, leave starts, and leave returns are distinct events.",
                        "Transfers between group companies are never counted as departures.",
                        "Actual history is capped at the reporting date; scheduled future rows are excluded.")), data);
    }

    public Response<UpcomingChanges> upcomingChanges(PeopleFilterParams filters) {
        String sql = upcomingCtes(filters) +
                " SELECT effective_date,event_type,COUNT(DISTINCT useruuid) people_count" +
                " FROM event_rows GROUP BY effective_date,event_type ORDER BY effective_date,event_type";
        List<Tuple> rows = repository.tuples("upcoming-changes", sql, upcomingBindings(filters));
        List<UpcomingChangeSummary> summaries = new ArrayList<>(rows.size());
        long sample = 0;
        boolean anySuppressed = false;
        boolean anyAvailable = false;
        for (Tuple row : rows) {
            long count = toLong(row.get("people_count"));
            boolean suppressed = suppresses(count);
            summaries.add(new UpcomingChangeSummary(
                    localDate(row.get("effective_date")),
                    row.get("event_type", String.class),
                    visibleCount(count, suppressed),
                    suppressed,
                    !suppressed,
                    suppressed ? "BELOW_PRIVACY_THRESHOLD" : null));
            sample += count;
            anySuppressed |= suppressed;
            anyAvailable |= !suppressed;
        }
        UpcomingChanges data = new UpcomingChanges(summaries, anyAvailable);
        return new Response<>(meta(filters, filters.asOfDate().plusDays(1),
                filters.asOfDate().plusDays(filters.horizonDays()), null, filters.horizonDays(),
                anySuppressed ? -1 : sample, 0, suppresses(sample), null,
                List.of(
                        "Upcoming changes are scheduled user-status rows after the reporting date.",
                        "Actual charts never include these future events.")), data);
    }

    public Response<List<UpcomingChangeDetail>> upcomingChangesDetail(
            PeopleFilterParams filters,
            LocalDate eventDate,
            PeopleWorkforceEventType eventType) {
        LocalDate horizonEnd = filters.asOfDate().plusDays(filters.horizonDays());
        if (!eventDate.isAfter(filters.asOfDate()) || eventDate.isAfter(horizonEnd)) {
            throw new BadRequestException("eventDate must be inside the selected upcoming horizon");
        }
        String sql = upcomingCtes(filters) +
                ", selected_cell AS (" +
                " SELECT effective_date,event_type,COUNT(DISTINCT useruuid) cell_size" +
                " FROM event_rows WHERE effective_date=:eventDate AND event_type=:eventType" +
                " GROUP BY effective_date,event_type" +
                ") SELECT er.useruuid,er.display_name,er.effective_date,er.event_type,er.from_value,er.to_value" +
                " FROM event_rows er JOIN selected_cell sc" +
                " ON sc.effective_date=er.effective_date AND sc.event_type=er.event_type" +
                " ORDER BY er.display_name";
        Map<String, Object> bindings = upcomingBindings(filters);
        bindings.put("eventDate", Date.valueOf(eventDate));
        bindings.put("eventType", eventType.name());
        List<Tuple> rows = repository.tuples("upcoming-changes-detail", sql, bindings);
        if (rows.isEmpty()) {
            throw new NotFoundException("Upcoming change detail is unavailable");
        }
        List<UpcomingChangeDetail> data = rows.stream().map(row -> new UpcomingChangeDetail(
                row.get("useruuid", String.class),
                row.get("display_name", String.class),
                localDate(row.get("effective_date")),
                row.get("event_type", String.class),
                row.get("from_value", String.class),
                row.get("to_value", String.class))).toList();
        return new Response<>(meta(filters, filters.asOfDate().plusDays(1),
                filters.asOfDate().plusDays(filters.horizonDays()), null, filters.horizonDays(),
                data.size(), 0, false, null,
                List.of("Named detail is ADMIN-only and excludes suppressed groups.")), data);
    }

    public Response<List<TenureBand>> tenureDistribution(PeopleFilterParams filters) {
        String sql = "WITH " + PeopleEmploymentSpellSupport.sqlCtes() +
                ", spell_starts AS (" +
                " SELECT useruuid,MAX(start_date) spell_start FROM employment_spells" +
                " WHERE end_date IS NULL OR end_date>:asOfDate GROUP BY useruuid" +
                "), " + PeoplePopulationSqlSupport.snapshotPopulationCtes(filters, "asOfDate") +
                ", bucketed AS (" +
                " SELECT fp.useruuid, CASE" +
                " WHEN ss.spell_start IS NULL THEN 'UNASSIGNED'" +
                " WHEN TIMESTAMPDIFF(MONTH,ss.spell_start,:asOfDate) < 12 THEN 'UNDER_1'" +
                " WHEN TIMESTAMPDIFF(MONTH,ss.spell_start,:asOfDate) < 36 THEN 'YEARS_1_2'" +
                " WHEN TIMESTAMPDIFF(MONTH,ss.spell_start,:asOfDate) < 60 THEN 'YEARS_3_4'" +
                " WHEN TIMESTAMPDIFF(MONTH,ss.spell_start,:asOfDate) < 120 THEN 'YEARS_5_9'" +
                " ELSE 'YEARS_10_PLUS' END band" +
                " FROM filtered_population fp LEFT JOIN spell_starts ss ON ss.useruuid = fp.useruuid" +
                ") SELECT band, COUNT(DISTINCT useruuid) people_count FROM bucketed GROUP BY band";

        List<Tuple> rows = repository.tuples("tenure-distribution", sql,
                PeoplePopulationSqlSupport.snapshotBindings(filters, "asOfDate", filters.asOfDate()));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Tuple row : rows) counts.put(row.get("band", String.class), toLong(row.get("people_count")));
        List<String[]> bands = List.of(
                new String[]{"UNDER_1", "Under 1 year"},
                new String[]{"YEARS_1_2", "1–2 years"},
                new String[]{"YEARS_3_4", "3–4 years"},
                new String[]{"YEARS_5_9", "5–9 years"},
                new String[]{"YEARS_10_PLUS", "10+ years"},
                new String[]{"UNASSIGNED", "Unassigned"});
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        boolean responseSuppressed = suppresses(total);
        Map<String, Long> completeCounts = new LinkedHashMap<>();
        for (String[] band : bands) completeCounts.put(band[0], counts.getOrDefault(band[0], 0L));
        Set<String> hidden = PeopleComplementarySuppression.suppressedKeys(completeCounts);
        boolean anyCellSuppressed = false;
        List<TenureBand> data = new ArrayList<>(bands.size());
        for (int i = 0; i < bands.size(); i++) {
            String key = bands.get(i)[0];
            long count = counts.getOrDefault(key, 0L);
            boolean cellSuppressed = responseSuppressed || hidden.contains(key);
            anyCellSuppressed |= cellSuppressed;
            data.add(new TenureBand(key, bands.get(i)[1], i,
                    visibleCount(count, cellSuppressed), percentage(count, total, cellSuppressed), cellSuppressed));
        }
        return new Response<>(meta(filters, filters.asOfDate(), filters.asOfDate(), null, null,
                anyCellSuppressed ? -1 : total, 0, responseSuppressed, YearMonth.from(filters.asOfDate()),
                List.of(
                        "Tenure starts at the beginning of the current continuous internal-employment spell.",
                        "A rehire starts a new tenure spell; leave does not break the spell.",
                        "Age is intentionally not used for people-management decisions.")), data);
    }

    private String upcomingCtes(PeopleFilterParams filters) {
        String companyIn = filters.companyId() == null ? "1=1" : "h.companyuuid=:companyId";
        String companyOut = filters.companyId() == null ? "1=1" : "h.previous_companyuuid=:companyId";
        String isEmployed = PeoplePopulationSqlSupport.internalEmployedPredicate("h");
        String wasEmployed = previousEmployedPredicate("h");
        String isTransfer = isEmployed + " AND " + wasEmployed +
                " AND NOT (h.companyuuid <=> h.previous_companyuuid)";
        String sameCompanyRehire = "h.same_company_rehire=1 AND COALESCE(h.prior_employed_rows,0)>0";
        return "WITH " + PeoplePopulationSqlSupport.canonicalStatusDayCtes("future", ":horizonEnd") +
                ", future_history AS (" +
                " SELECT fsd.*," +
                " LAG(fsd.status) OVER (PARTITION BY fsd.useruuid ORDER BY fsd.statusdate,fsd.created_at,fsd.uuid) previous_status," +
                " LAG(fsd.`type`) OVER (PARTITION BY fsd.useruuid ORDER BY fsd.statusdate,fsd.created_at,fsd.uuid) previous_type," +
                " LAG(fsd.companyuuid) OVER (PARTITION BY fsd.useruuid ORDER BY fsd.statusdate,fsd.created_at,fsd.uuid) previous_companyuuid," +
                " SUM(CASE WHEN " + PeoplePopulationSqlSupport.internalEmployedPredicate("fsd") +
                " THEN 1 ELSE 0 END)" +
                " OVER (PARTITION BY fsd.useruuid ORDER BY fsd.statusdate,fsd.created_at,fsd.uuid" +
                "  ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) prior_employed_rows" +
                " FROM future_status_day fsd" +
                "), classified AS (" +
                " SELECT h.*,CONCAT(u.firstname,' ',u.lastname) display_name," +
                " CONCAT(COALESCE(h.previous_type,'—'),' / ',COALESCE(h.previous_status,'—'),' / ',COALESCE(h.previous_companyuuid,'—')) from_value," +
                " CONCAT(COALESCE(h.`type`,'—'),' / ',COALESCE(h.status,'—'),' / ',COALESCE(h.companyuuid,'—')) to_value," +
                " CASE WHEN " + isEmployed + " AND NOT (" + wasEmployed + ")" +
                "  AND COALESCE(h.prior_employed_rows,0)=0 AND " + companyIn + " THEN 1 ELSE 0 END first_hire," +
                " CASE WHEN ((" + isEmployed + " AND NOT (" + wasEmployed + ")" +
                "  AND COALESCE(h.prior_employed_rows,0)>0) OR (" + sameCompanyRehire + "))" +
                "  AND " + companyIn + " THEN 1 ELSE 0 END rehire," +
                " CASE WHEN ((" + wasEmployed + " AND NOT (" + isEmployed + "))" +
                "  OR (" + sameCompanyRehire + ")) AND " + companyOut + " THEN 1 ELSE 0 END departure," +
                " CASE WHEN " + isTransfer + " AND " + companyIn + " THEN 1 ELSE 0 END transfer_in," +
                " CASE WHEN " + isTransfer + " AND " + companyOut + " THEN 1 ELSE 0 END transfer_out," +
                " CASE WHEN " + isTransfer + " THEN 1 ELSE 0 END company_transfer," +
                " CASE WHEN h.status IN ('PAID_LEAVE','MATERNITY_LEAVE','NON_PAY_LEAVE')" +
                "  AND h.previous_status='ACTIVE' AND " + companyIn + " THEN 1 ELSE 0 END leave_start," +
                " CASE WHEN h.status='ACTIVE' AND h.previous_status IN ('PAID_LEAVE','MATERNITY_LEAVE','NON_PAY_LEAVE')" +
                "  AND " + companyIn + " THEN 1 ELSE 0 END leave_return" +
                " FROM future_history h JOIN `user` u ON u.uuid=h.useruuid" +
                " LEFT JOIN user_career_level ec ON ec.useruuid=h.useruuid AND ec.active_from<=h.statusdate" +
                "  AND NOT EXISTS (SELECT 1 FROM user_career_level newer" +
                "   WHERE newer.useruuid=ec.useruuid AND newer.active_from<=h.statusdate" +
                "   AND (newer.active_from>ec.active_from" +
                "    OR (newer.active_from=ec.active_from AND newer.created_at>ec.created_at)" +
                "    OR (newer.active_from=ec.active_from AND newer.created_at=ec.created_at AND newer.uuid>ec.uuid)))" +
                " WHERE h.statusdate>:asOfDate AND " + eventScope(filters) +
                "), event_rows AS (" +
                eventUnion("FIRST_HIRE", "first_hire", true) +
                eventUnion("REHIRE", "rehire", false) +
                eventUnion("DEPARTURE", "departure", false) +
                (filters.companyId() == null
                        ? eventUnion("COMPANY_TRANSFER", "company_transfer", false)
                        : eventUnion("TRANSFER_IN", "transfer_in", false) +
                          eventUnion("TRANSFER_OUT", "transfer_out", false)) +
                eventUnion("LEAVE_START", "leave_start", false) +
                eventUnion("LEAVE_RETURN", "leave_return", false) +
                ")";
    }

    private Map<String, Object> upcomingBindings(PeopleFilterParams filters) {
        Map<String, Object> bindings = PeoplePopulationSqlSupport.filterBindings(filters);
        bindings.keySet().removeIf(key -> List.of("populationStatuses", "leaveStatuses").contains(key));
        bindings.put("asOfDate", Date.valueOf(filters.asOfDate()));
        bindings.put("horizonEnd", Date.valueOf(filters.asOfDate().plusDays(filters.horizonDays())));
        return bindings;
    }

    private static String eventUnion(String eventType, String flag, boolean first) {
        String prefix = first ? "" : " UNION ALL ";
        return prefix + "SELECT useruuid,display_name,statusdate effective_date,'" + eventType + "' event_type," +
                " from_value,to_value FROM classified WHERE " + flag + "=1";
    }

    private static PeopleFilterParams employedFilters(PeopleFilterParams filters) {
        return new PeopleFilterParams(
                filters.asOfDate(), filters.months(), filters.horizonDays(), filters.companyId(),
                filters.employeeTypes(), dk.trustworks.intranet.aggregates.executive.people.PeoplePopulationScope.EMPLOYED,
                filters.practices(), filters.careerTracks(), filters.careerLevels(), filters.managementScope(),
                filters.compensationGroup(), filters.salaryType());
    }

    private String eventScope(PeopleFilterParams filters) {
        StringBuilder sql = new StringBuilder("COALESCE(NULLIF(h.`type`,'EXTERNAL'),h.previous_type) IN (:employeeTypes)");
        if (!filters.practices().isEmpty()) sql.append(" AND u.practice IN (:practices)");
        if (!filters.careerTracks().isEmpty()) sql.append(" AND ec.career_track IN (:careerTracks)");
        if (!filters.careerLevels().isEmpty()) sql.append(" AND ec.career_level IN (:careerLevels)");
        switch (filters.managementScope()) {
            case ALL -> {
            }
            case PEOPLE_LEADERS -> sql.append(" AND EXISTS (SELECT 1 FROM teamroles tr")
                    .append(" WHERE tr.useruuid=h.useruuid AND tr.membertype='LEADER'")
                    .append(" AND tr.startdate<=h.statusdate AND (tr.enddate IS NULL OR tr.enddate>=h.statusdate))");
            case SENIOR_LEADERSHIP -> sql.append(" AND COALESCE(NULLIF(h.`type`,'EXTERNAL'),h.previous_type)='CONSULTANT'")
                    .append(" AND ec.career_track IN (:seniorLeadershipTracks)");
        }
        return sql.toString();
    }

    private static String countCase(String condition, String alias) {
        return " COUNT(DISTINCT CASE WHEN " + condition + " THEN msr.useruuid END) " + alias + ",";
    }

    private static String flagCount(String flag, String alias) {
        return " COUNT(DISTINCT CASE WHEN " + flag + "=1 THEN useruuid END) " + alias + ",";
    }

    static String previousEmployedPredicate(String alias) {
        return "COALESCE((" + alias + ".previous_type IN ('CONSULTANT','STAFF','STUDENT')" +
                " AND " + alias + ".previous_status IN" +
                " ('ACTIVE','PAID_LEAVE','MATERNITY_LEAVE','NON_PAY_LEAVE')),FALSE)";
    }

    private <T> Response<List<T>> trendResponse(
            PeopleFilterParams filters,
            List<T> data,
            long latestSample,
            boolean suppressed,
            List<String> caveats) {
        return new Response<>(meta(filters, PeoplePopulationSqlSupport.periodStart(filters), filters.asOfDate(),
                filters.months(), null, latestSample, 0, suppressed, YearMonth.from(filters.asOfDate()), caveats), data);
    }

    private static LocalDate localDate(Object value) {
        if (value instanceof LocalDate localDate) return localDate;
        if (value instanceof Date date) return date.toLocalDate();
        if (value instanceof java.util.Date date) return new Date(date.getTime()).toLocalDate();
        return LocalDate.parse(String.valueOf(value));
    }
}
