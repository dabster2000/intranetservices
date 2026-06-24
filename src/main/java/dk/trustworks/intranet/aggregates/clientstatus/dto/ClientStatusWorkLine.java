package dk.trustworks.intranet.aggregates.clientstatus.dto;

/** One registered-work line in the drill-down (consultant × project). */
public record ClientStatusWorkLine(
        String consultantUuid,
        String consultantName,
        String projectUuid,
        String projectName,
        double hours,
        double avgRate,
        double value            // hours × rate (− discount)
) {}
