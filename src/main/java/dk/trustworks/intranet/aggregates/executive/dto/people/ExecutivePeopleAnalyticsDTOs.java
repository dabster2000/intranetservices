package dk.trustworks.intranet.aggregates.executive.dto.people;

import java.time.LocalDate;
import java.util.List;

/** Wire contracts for the versioned Executive HR & People API. */
public final class ExecutivePeopleAnalyticsDTOs {

    public static final int PRIVACY_THRESHOLD = 3;

    private ExecutivePeopleAnalyticsDTOs() {
    }

    public record Response<T>(PeopleMeta meta, T data) {
    }

    public record PeopleMeta(
            LocalDate asOfDate,
            LocalDate periodStart,
            LocalDate periodEnd,
            Integer months,
            Integer horizonDays,
            Long sampleSize,
            Long excludedCount,
            boolean suppressed,
            int privacyThreshold,
            String sourceMonth,
            List<String> caveats
    ) {
        public PeopleMeta {
            caveats = caveats == null ? List.of() : List.copyOf(caveats);
        }
    }

    public record WorkforceSummary(
            Long employeeCount,
            Long activeCount,
            Long onLeaveCount,
            Long externalCount,
            Double contractedFte,
            Double activeFte
    ) {
    }

    public record HeadcountCompositionPoint(
            LocalDate date,
            Long consultant,
            Long staff,
            Long student,
            Long external,
            Long employeeTotal,
            Double contractedFte
    ) {
    }

    public record StatusTrendPoint(
            LocalDate date,
            Long active,
            Long onLeave,
            Long paidLeave,
            Long maternityLeave,
            Long nonPayLeave,
            Long employeeTotal,
            boolean suppressed,
            String suppressionReason
    ) {
    }

    public record GenderTrendPoint(
            LocalDate date,
            Long male,
            Long female,
            Long unknown,
            Long total,
            Double femalePct
    ) {
    }

    public record WorkforceFlowPoint(
            String month,
            Long firstHires,
            Long rehires,
            Long departures,
            Long transfersIn,
            Long transfersOut,
            Long leaveStarts,
            Long leaveReturns,
            Long netEmployeeChange
    ) {
    }

    public record UpcomingChangeSummary(
            LocalDate date,
            String type,
            Long count,
            boolean suppressed,
            boolean detailAvailable,
            String detailUnavailableReason
    ) {
    }

    public record UpcomingChanges(
            List<UpcomingChangeSummary> summary,
            boolean detailAvailable
    ) {
        public UpcomingChanges {
            summary = summary == null ? List.of() : List.copyOf(summary);
        }
    }

    public record UpcomingChangeDetail(
            String userUuid,
            String displayName,
            LocalDate effectiveDate,
            String type,
            String fromValue,
            String toValue
    ) {
    }

    public record TenureBand(
            String key,
            String label,
            int sortOrder,
            Long count,
            Double sharePct,
            boolean suppressed
    ) {
    }

    public record CareerLadderRow(
            String level,
            String track,
            int sortOrder,
            Long count,
            Double sharePct,
            boolean suppressed
    ) {
    }

    public record CareerMixRow(
            String band,
            int sortOrder,
            Long currentCount,
            Long priorYearCount,
            Double currentSharePct,
            Double priorYearSharePct,
            boolean suppressed
    ) {
    }

    public record PracticeCareerCell(
            String practice,
            String careerTrack,
            Long count,
            boolean suppressed
    ) {
    }

    public record LeadershipCoverageRow(
            String teamUuid,
            String teamName,
            Long memberCount,
            Long leaderCount,
            Double spanPerLeader,
            String coverageStatus,
            boolean suppressed,
            boolean detailAvailable,
            String detailUnavailableReason,
            String detailPrivacyReason
    ) {
    }

    public record LeadershipCoverageDetail(
            String teamUuid,
            String teamName,
            String userUuid,
            String displayName,
            String role,
            String careerLevel
    ) {
    }

    public record RetentionRatePoint(
            String month,
            Long startingEmployees,
            Long retainedEmployees,
            Long departures,
            Double retentionRatePct
    ) {
    }

    public record RetentionCohort(
            String cohort,
            Long cohortSize,
            boolean suppressed,
            List<RetentionCohortPoint> points
    ) {
        public RetentionCohort {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    public record RetentionCohortPoint(
            int month,
            Integer intervalStartMonth,
            Long atRisk,
            Long events,
            Long intervalEvents,
            Long retained,
            Double survivalPct,
            boolean suppressed,
            boolean eventsSuppressed,
            String suppressionReason
    ) {
    }

    public record PayEquityRow(
            String groupKey,
            String groupLabel,
            int sortOrder,
            String salaryType,
            Long maleCount,
            Long femaleCount,
            Double maleMedian,
            Double femaleMedian,
            Double maleMean,
            Double femaleMean,
            Double payGapPct,
            Double meanPayGapPct,
            Boolean reviewThresholdMet,
            String reviewReason,
            boolean suppressed
    ) {
    }

    public record PayQuartileRow(
            String key,
            String label,
            int sortOrder,
            String salaryType,
            Long maleCount,
            Long femaleCount,
            Double maleSharePct,
            Double femaleSharePct,
            boolean suppressed
    ) {
    }

    public record PayTrendPoint(
            String month,
            String salaryType,
            Long count,
            Double median,
            Double mean,
            boolean suppressed
    ) {
    }
}
