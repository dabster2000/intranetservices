package dk.trustworks.intranet.vacationservice.dto;

import java.util.Map;

/**
 * One parsed CSV line. {@code years} carries the day figures per ferieår;
 * the raw DKK strings ride along for display only.
 *
 * <p>{@code companyAtAsOf} / {@code companyAtAsOfName} are the evidence behind
 * an OTHER_COMPANY verdict — where the userstatus timeline actually put this
 * person on the batch's as-of date. They travel as facts, not as prose: the
 * sentence HR reads is composed in the console, which owns wording and date
 * formatting. A second copy here would drift from the one on screen.</p>
 */
public record VacationImportRowDTO(
        String uuid,
        int lineNo,
        String danlonName,
        String useruuid,
        String matchedFullname,
        String matchStatus,
        String companyAtAsOf,
        String companyAtAsOfName,
        Map<Integer, YearFiguresDTO> years) {

    public record YearFiguresDTO(double earnedDays, double usedDays, String earnedKrRaw, String provisionKrRaw) {
    }
}
