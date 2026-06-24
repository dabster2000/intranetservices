package dk.trustworks.intranet.aggregates.clientstatus.dto;

/** One registered-work line in the drill-down (consultant × project). */
public record ClientStatusWorkLine(
        String consultantUuid,
        String consultantName,
        String projectUuid,
        String projectName,
        double hours,
        double avgRate,
        double value,                  // hours × rate (− discount)
        boolean helpColleague,         // true when this row is help-colleague work (workas ≠ self)
        String helpedConsultantName    // nullable; name of the workas user this row was performed on behalf of
) {}
