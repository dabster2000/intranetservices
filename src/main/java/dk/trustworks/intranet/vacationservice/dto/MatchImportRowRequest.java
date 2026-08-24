package dk.trustworks.intranet.vacationservice.dto;

/** Resolve one unmatched row: pick a user, or ignore the row entirely. */
public record MatchImportRowRequest(
        String useruuid,
        boolean ignore) {
}
