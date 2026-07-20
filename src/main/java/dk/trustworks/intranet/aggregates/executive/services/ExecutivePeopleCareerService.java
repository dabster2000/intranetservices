package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.CareerLadderRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.CareerMixRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.LeadershipCoverageDetail;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.LeadershipCoverageRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PracticeCareerCell;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.Response;
import dk.trustworks.intranet.aggregates.executive.people.HrCareerBandMapper;
import dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsRepository;
import dk.trustworks.intranet.aggregates.executive.people.PeopleComplementarySuppression;
import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterParams;
import dk.trustworks.intranet.aggregates.executive.people.PeopleManagementScope;
import dk.trustworks.intranet.aggregates.executive.people.PeoplePopulationScope;
import dk.trustworks.intranet.aggregates.executive.people.PeoplePopulationSqlSupport;
import dk.trustworks.intranet.services.PracticeService;
import dk.trustworks.intranet.userservice.model.enums.CareerLevel;
import dk.trustworks.intranet.userservice.model.enums.CareerTrack;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.Tuple;
import jakarta.ws.rs.NotFoundException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.toLong;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.meta;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.percentage;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.round2;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.suppresses;
import static dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsSupport.visibleCount;

/** Career progression and leadership metrics for the Executive HR dashboard. */
@ApplicationScoped
public class ExecutivePeopleCareerService {

    static final int PRIOR_YEAR_CAREER_COVERAGE_PERCENT = 80;

    private static final List<String> CAREER_CAVEATS = List.of(
            "Career views include employed internal consultants only; staff, students, and externals are excluded.",
            "Career records are resolved to the latest value on or before each reporting date.",
            "Entry is shared across tracks and missing career records are shown as Unassigned.");

    private static final List<String> MATRIX_TRACK_ORDER = List.of(
            "ENTRY",
            CareerTrack.DELIVERY.name(),
            CareerTrack.ADVISORY.name(),
            CareerTrack.LEADERSHIP.name(),
            CareerTrack.CLIENT_ENGAGEMENT.name(),
            CareerTrack.PARTNER.name(),
            CareerTrack.C_LEVEL.name(),
            "UNASSIGNED");

    @Inject
    PeopleAnalyticsRepository repository;

    @Inject
    PracticeService practiceService;

    public Response<List<CareerLadderRow>> careerLadder(PeopleFilterParams filters) {
        String sql = "WITH " + PeoplePopulationSqlSupport.snapshotPopulationCtes(filters, "asOfDate") +
                " SELECT fp.career_level, COUNT(DISTINCT fp.useruuid) people_count" +
                " FROM filtered_population fp WHERE fp.`type` = 'CONSULTANT'" +
                " GROUP BY fp.career_level";
        List<Tuple> rows = repository.tuples("career-ladder", sql,
                PeoplePopulationSqlSupport.snapshotBindings(filters, "asOfDate", filters.asOfDate()));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Tuple row : rows) {
            String level = row.get("career_level", String.class);
            counts.put(level == null ? "UNASSIGNED" : level, toLong(row.get("people_count")));
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        boolean responseSuppressed = suppresses(total);

        List<CareerLevel> levels = new ArrayList<>(List.of(CareerLevel.values()));
        Collections.reverse(levels); // business display is senior to junior
        Map<String, Long> completeCounts = new LinkedHashMap<>();
        for (CareerLevel level : levels) completeCounts.put(level.name(), counts.getOrDefault(level.name(), 0L));
        completeCounts.put("UNASSIGNED", counts.getOrDefault("UNASSIGNED", 0L));
        Set<String> hidden = PeopleComplementarySuppression.suppressedKeys(completeCounts);
        List<CareerLadderRow> data = new ArrayList<>(levels.size() + 1);
        boolean anyCellSuppressed = false;
        for (int i = 0; i < levels.size(); i++) {
            CareerLevel level = levels.get(i);
            long count = counts.getOrDefault(level.name(), 0L);
            boolean cellSuppressed = responseSuppressed || hidden.contains(level.name());
            anyCellSuppressed |= cellSuppressed;
            data.add(new CareerLadderRow(
                    level.name(),
                    level == CareerLevel.JUNIOR_CONSULTANT ? "ENTRY" : level.getTrack().name(),
                    i,
                    visibleCount(count, cellSuppressed),
                    percentage(count, total, cellSuppressed),
                    cellSuppressed));
        }
        long unassigned = counts.getOrDefault("UNASSIGNED", 0L);
        boolean unassignedSuppressed = responseSuppressed || hidden.contains("UNASSIGNED");
        anyCellSuppressed |= unassignedSuppressed;
        data.add(new CareerLadderRow(
                "UNASSIGNED", null, levels.size(),
                visibleCount(unassigned, unassignedSuppressed),
                percentage(unassigned, total, unassignedSuppressed),
                unassignedSuppressed));

        return new Response<>(meta(filters, filters.asOfDate(), filters.asOfDate(), null, null,
                anyCellSuppressed ? -1 : total, 0, responseSuppressed, YearMonth.from(filters.asOfDate()), CAREER_CAVEATS), data);
    }

    public Response<List<CareerMixRow>> careerMix(PeopleFilterParams filters) {
        Map<String, Long> current = careerBandCounts(filters, filters.asOfDate());
        LocalDate priorDate = filters.asOfDate().minusYears(1);
        Map<String, Long> prior = careerBandCounts(filters, priorDate);
        long currentTotal = current.values().stream().mapToLong(Long::longValue).sum();
        long priorTotal = prior.values().stream().mapToLong(Long::longValue).sum();
        boolean responseSuppressed = suppresses(currentTotal);
        Map<String, Long> completeCurrent = new LinkedHashMap<>();
        Map<String, Long> completePrior = new LinkedHashMap<>();
        for (String band : HrCareerBandMapper.BAND_ORDER) {
            completeCurrent.put(band, current.getOrDefault(band, 0L));
            completePrior.put(band, prior.getOrDefault(band, 0L));
        }
        Set<String> hiddenCurrent = PeopleComplementarySuppression.suppressedKeys(completeCurrent);
        Set<String> candidateHiddenPrior = PeopleComplementarySuppression.suppressedKeys(completePrior);
        boolean priorComparisonAvailable = hasComparablePriorCareerMix(prior, candidateHiddenPrior);
        Set<String> hiddenPrior = priorComparisonAvailable
                ? candidateHiddenPrior
                : Set.of();
        boolean anyCellSuppressed = false;
        List<CareerMixRow> data = new ArrayList<>(HrCareerBandMapper.BAND_ORDER.size());
        for (int i = 0; i < HrCareerBandMapper.BAND_ORDER.size(); i++) {
            String band = HrCareerBandMapper.BAND_ORDER.get(i);
            long currentCount = current.getOrDefault(band, 0L);
            long priorCount = prior.getOrDefault(band, 0L);
            boolean cellSuppressed = responseSuppressed
                    || hiddenCurrent.contains(band)
                    || (priorComparisonAvailable && hiddenPrior.contains(band));
            anyCellSuppressed |= cellSuppressed;
            data.add(new CareerMixRow(
                    band,
                    i,
                    visibleCount(currentCount, cellSuppressed),
                    priorComparisonAvailable ? visibleCount(priorCount, cellSuppressed) : null,
                    percentage(currentCount, currentTotal, cellSuppressed),
                    priorComparisonAvailable ? percentage(priorCount, priorTotal, cellSuppressed) : null,
                    cellSuppressed));
        }
        List<String> caveats = new ArrayList<>(List.of(
                "Seniority mix compares the reporting-date population with the same date one year earlier.",
                "The HR band mapper treats engagement roles as leadership and DIRECTOR as Partner / Director.",
                "Prior-year values use the career and employment records valid on that historical date."));
        if (!priorComparisonAvailable) {
            caveats.add("The prior-year comparison is unavailable because less than "
                    + PRIOR_YEAR_CAREER_COVERAGE_PERCENT
                    + "% of the employed-consultant population has assigned, privacy-visible career data at that snapshot; zeros would be misleading.");
        }
        return new Response<>(meta(filters, priorDate, filters.asOfDate(), 12, null,
                anyCellSuppressed ? -1 : currentTotal, 0, responseSuppressed, YearMonth.from(filters.asOfDate()),
                caveats), data);
    }

    public Response<List<PracticeCareerCell>> practiceCareerMatrix(PeopleFilterParams filters) {
        String sql = "WITH " + PeoplePopulationSqlSupport.snapshotPopulationCtes(filters, "asOfDate") +
                " SELECT COALESCE(fp.practice, 'UD') practice," +
                " CASE WHEN fp.career_level = 'JUNIOR_CONSULTANT' THEN 'ENTRY'" +
                " WHEN fp.career_track IS NULL THEN 'UNASSIGNED' ELSE fp.career_track END career_track," +
                " COUNT(DISTINCT fp.useruuid) people_count" +
                " FROM filtered_population fp WHERE fp.`type` = 'CONSULTANT'" +
                " GROUP BY COALESCE(fp.practice, 'UD'), career_track";
        List<Tuple> rows = repository.tuples("practice-career-matrix", sql,
                PeoplePopulationSqlSupport.snapshotBindings(filters, "asOfDate", filters.asOfDate()));
        Map<String, Long> counts = new LinkedHashMap<>();
        long total = 0;
        for (Tuple row : rows) {
            String practice = row.get("practice", String.class);
            if (practice == null) practice = "UNASSIGNED";
            String track = row.get("career_track", String.class);
            long count = toLong(row.get("people_count"));
            counts.put(practice + "|" + track, count);
            total += count;
        }
        boolean responseSuppressed = suppresses(total);
        boolean anyCellSuppressed = false;
        // Registry-derived grid (Phase 3): every registry practice in business
        // order (sort_order) — including the UD "No practice" bucket, whose
        // users are real data until the Phase 4 operational-NULL flip — plus
        // the Unassigned bucket for users without a practice value at all.
        List<String> practices = new ArrayList<>(practiceService.orderedRegistryCodes());
        practices.add("UNASSIGNED");
        Map<String, Long> completeCounts = new LinkedHashMap<>();
        for (String practice : practices) {
            for (String track : MATRIX_TRACK_ORDER) {
                String key = practice + "|" + track;
                completeCounts.put(key, counts.getOrDefault(key, 0L));
            }
        }
        Set<String> hidden = PeopleComplementarySuppression.suppressedKeys(completeCounts);
        List<PracticeCareerCell> data = new ArrayList<>(practices.size() * MATRIX_TRACK_ORDER.size());
        for (String practice : practices) {
            for (String track : MATRIX_TRACK_ORDER) {
                long count = counts.getOrDefault(practice + "|" + track, 0L);
                boolean cellSuppressed = responseSuppressed || hidden.contains(practice + "|" + track);
                anyCellSuppressed |= cellSuppressed;
                data.add(new PracticeCareerCell(practice, track,
                        visibleCount(count, cellSuppressed), cellSuppressed));
            }
        }
        return new Response<>(meta(filters, filters.asOfDate(), filters.asOfDate(), null, null,
                anyCellSuppressed ? -1 : total, 0, responseSuppressed, YearMonth.from(filters.asOfDate()),
                List.of(
                        "The matrix includes every practice registry entry in business order.",
                        "Practice is current-state only and is not applied retroactively.",
                        "Entry and Unassigned are shown separately from the six career tracks.")), data);
    }

    public Response<List<LeadershipCoverageRow>> leadershipCoverage(PeopleFilterParams filters) {
        PeopleFilterParams coverageFilters = coverageFilters(filters);
        String sql = "WITH " + PeoplePopulationSqlSupport.snapshotPopulationCtes(coverageFilters, "asOfDate") +
                " SELECT t.uuid team_uuid, t.name team_name," +
                " COUNT(DISTINCT CASE WHEN tr.membertype IN ('MEMBER','LEADER') THEN fp.useruuid END) member_count," +
                " COUNT(DISTINCT CASE WHEN tr.membertype = 'LEADER' THEN fp.useruuid END) leader_count" +
                " FROM team t" +
                " JOIN teamroles tr ON tr.teamuuid = t.uuid" +
                "  AND tr.startdate <= :asOfDate AND (tr.enddate IS NULL OR tr.enddate >= :asOfDate)" +
                " JOIN filtered_population fp ON fp.useruuid = tr.useruuid" +
                " WHERE tr.membertype IN ('MEMBER','LEADER')" +
                " GROUP BY t.uuid,t.name ORDER BY t.name";
        List<Tuple> rows = repository.tuples("leadership-coverage", sql,
                PeoplePopulationSqlSupport.snapshotBindings(coverageFilters, "asOfDate", filters.asOfDate()));
        List<LeadershipCoverageRow> data = new ArrayList<>(rows.size());
        long sample = 0;
        boolean anySuppressed = false;
        for (Tuple row : rows) {
            long members = toLong(row.get("member_count"));
            long leaders = toLong(row.get("leader_count"));
            boolean suppressed = suppresses(members);
            boolean leaderSuppressed = suppresses(leaders);
            boolean rowSuppressed = suppressed || leaderSuppressed;
            boolean detailAvailable = leadershipDetailAvailable(members, leaders);
            data.add(new LeadershipCoverageRow(
                    row.get("team_uuid", String.class),
                    row.get("team_name", String.class),
                    visibleCount(members, suppressed),
                    visibleCount(leaders, rowSuppressed),
                    rowSuppressed ? null : spanPerLeader(members, leaders),
                    leaders > 0 ? "COVERED" : "UNCOVERED",
                    rowSuppressed,
                    detailAvailable,
                    detailAvailable ? null : leadershipDetailUnavailableReason(members),
                    leadershipDetailPrivacyReason(members, leaders)));
            sample += members;
            anySuppressed |= suppressed || leaderSuppressed;
        }
        return new Response<>(meta(filters, filters.asOfDate(), filters.asOfDate(), null, null,
                anySuppressed ? -1 : sample, 0, suppresses(sample), YearMonth.from(filters.asOfDate()),
                List.of(
                        "Team membership and LEADER roles must be active on the reporting date.",
                        "Only MEMBER and LEADER roles enter team size; GUEST and SPONSOR roles are excluded.",
                        "Career-track leadership never substitutes for an active team LEADER role.",
                        "A person on multiple teams is counted once in each relevant team.")), data);
    }

    public Response<List<LeadershipCoverageDetail>> leadershipCoverageDetail(
            PeopleFilterParams filters, String teamId) {
        PeopleFilterParams coverageFilters = coverageFilters(filters);
        String sql = "WITH " + PeoplePopulationSqlSupport.snapshotPopulationCtes(coverageFilters, "asOfDate") +
                ", team_population AS (" +
                " SELECT t.uuid team_uuid,t.name team_name,fp.useruuid,fp.firstname,fp.lastname," +
                " CASE WHEN MAX(CASE WHEN tr.membertype='LEADER' THEN 1 ELSE 0 END)=1" +
                "  THEN 'LEADER' ELSE 'MEMBER' END membertype,fp.career_level" +
                " FROM team t JOIN teamroles tr ON tr.teamuuid=t.uuid" +
                " JOIN filtered_population fp ON fp.useruuid=tr.useruuid" +
                " WHERE t.uuid=:teamId AND tr.membertype IN ('MEMBER','LEADER')" +
                " AND tr.startdate<=:asOfDate AND (tr.enddate IS NULL OR tr.enddate>=:asOfDate)" +
                " GROUP BY t.uuid,t.name,fp.useruuid,fp.firstname,fp.lastname,fp.career_level" +
                "), team_sized AS (" +
                " SELECT team_population.*,COUNT(*) OVER (PARTITION BY team_uuid) team_size," +
                " SUM(CASE WHEN membertype='LEADER' THEN 1 ELSE 0 END)" +
                " OVER (PARTITION BY team_uuid) leader_count FROM team_population" +
                ") SELECT team_uuid,team_name,useruuid,CONCAT(firstname,' ',lastname) display_name," +
                " membertype," +
                " career_level FROM team_sized" +
                " ORDER BY CASE membertype WHEN 'LEADER' THEN 0 ELSE 1 END,lastname,firstname";
        Map<String, Object> bindings = PeoplePopulationSqlSupport.snapshotBindings(
                coverageFilters, "asOfDate", filters.asOfDate());
        bindings.put("teamId", teamId);
        List<Tuple> rows = repository.tuples("leadership-coverage-detail", sql, bindings);
        if (rows.isEmpty()) {
            // Deliberately does not reveal whether the team was absent or suppressed.
            throw new NotFoundException("Leadership detail is unavailable");
        }
        List<LeadershipCoverageDetail> data = rows.stream().map(row -> new LeadershipCoverageDetail(
                row.get("team_uuid", String.class),
                row.get("team_name", String.class),
                row.get("useruuid", String.class),
                row.get("display_name", String.class),
                row.get("membertype", String.class),
                row.get("career_level", String.class))).toList();
        return new Response<>(meta(filters, filters.asOfDate(), filters.asOfDate(), null, null,
                data.size(), 0, false, YearMonth.from(filters.asOfDate()),
                List.of("Named team detail is ADMIN-only.")), data);
    }

    private Map<String, Long> careerBandCounts(PeopleFilterParams filters, LocalDate snapshot) {
        String bandCase = HrCareerBandMapper.toSqlCase("fp.career_level");
        String sql = "WITH " + PeoplePopulationSqlSupport.snapshotPopulationCtes(filters, "snapshotDate") +
                " SELECT " + bandCase + " career_band,COUNT(DISTINCT fp.useruuid) people_count" +
                " FROM filtered_population fp WHERE fp.`type`='CONSULTANT' GROUP BY career_band";
        List<Tuple> rows = repository.tuples("career-mix-snapshot", sql,
                PeoplePopulationSqlSupport.snapshotBindings(filters, "snapshotDate", snapshot));
        Map<String, Long> result = new LinkedHashMap<>();
        for (Tuple row : rows) {
            result.put(row.get("career_band", String.class), toLong(row.get("people_count")));
        }
        return result;
    }

    static boolean hasComparablePriorCareerMix(Map<String, Long> counts, Set<String> hiddenKeys) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) return false; // only guard the truly-empty prior year; privacy floor removed
        long assigned = total - counts.getOrDefault(HrCareerBandMapper.UNASSIGNED, 0L);
        long privacyVisible = counts.entrySet().stream()
                .filter(entry -> !hiddenKeys.contains(entry.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
        return assigned * 100 >= total * PRIOR_YEAR_CAREER_COVERAGE_PERCENT
                && privacyVisible * 100 >= total * PRIOR_YEAR_CAREER_COVERAGE_PERCENT;
    }

    private static PeopleFilterParams coverageFilters(PeopleFilterParams filters) {
        return new PeopleFilterParams(
                filters.asOfDate(),
                filters.months(),
                filters.horizonDays(),
                filters.companyId(),
                filters.employeeTypes(),
                PeoplePopulationScope.EMPLOYED,
                java.util.Set.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                PeopleManagementScope.ALL,
                filters.compensationGroup(),
                filters.salaryType());
    }

    static Double spanPerLeader(long peopleIncludingLeaders, long leaders) {
        if (leaders <= 0) return null;
        long nonLeaderReports = Math.max(0, peopleIncludingLeaders - leaders);
        return round2((double) nonLeaderReports / leaders);
    }

    static boolean leadershipDetailAvailable(long peopleIncludingLeaders, long leaders) {
        return !suppresses(peopleIncludingLeaders);
    }

    static String leadershipDetailUnavailableReason(long peopleIncludingLeaders) {
        if (suppresses(peopleIncludingLeaders)) return "TEAM_BELOW_PRIVACY_THRESHOLD";
        return null;
    }

    static String leadershipDetailPrivacyReason(long peopleIncludingLeaders, long leaders) {
        if (suppresses(peopleIncludingLeaders)) return null;
        return suppresses(leaders) ? "LEADER_ROLE_HIDDEN_BELOW_PRIVACY_THRESHOLD" : null;
    }
}
