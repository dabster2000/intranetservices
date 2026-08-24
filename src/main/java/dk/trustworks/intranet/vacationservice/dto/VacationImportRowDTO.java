package dk.trustworks.intranet.vacationservice.dto;

import java.util.Map;

/**
 * One parsed CSV line. {@code years} carries the day figures per ferieår;
 * the raw DKK strings ride along for display only.
 */
public record VacationImportRowDTO(
        String uuid,
        int lineNo,
        String danlonName,
        String useruuid,
        String matchedFullname,
        String matchStatus,
        Map<Integer, YearFiguresDTO> years) {

    public record YearFiguresDTO(double earnedDays, double usedDays, String earnedKrRaw, String provisionKrRaw) {
    }
}
