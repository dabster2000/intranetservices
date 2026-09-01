package dk.trustworks.intranet.documentservice.dto;

import java.util.List;

/**
 * Per-field prefill decision for one template + subject person
 * (template-clauses spec §5.1): who fills which field, with what value,
 * and how the form should present it.
 *
 * @param companyUuid         derived company (employee's active company /
 *                            candidate's target company); null when none
 * @param companyName         display name for the read-only company chip
 * @param companySource       {@code EMPLOYEE} or {@code CANDIDATE} — what
 *                            the company was derived from
 * @param missingCompanyFacts fact keys the template references that the
 *                            derived company has not filled — the form
 *                            warns and prepare fails closed on these
 * @param fields              one entry per template placeholder
 */
public record PlaceholderPrefillResponse(
        String companyUuid,
        String companyName,
        String companySource,
        List<String> missingCompanyFacts,
        List<PrefillField> fields) {

    /**
     * One placeholder's prefill decision.
     *
     * @param key            placeholder key ({@code {{KEY}}} in the document)
     * @param source         the placeholder's DataSource name
     * @param sourceField    explicit source field, null for legacy keyword matching
     * @param value          resolved value to prefill (null when nothing resolves,
     *                       or when the value is server-resolved and masked)
     * @param provenance     where the value came from: {@code COMPANY_FACT},
     *                       {@code PROFILE}, {@code CANDIDATE}, {@code SYSTEM};
     *                       null when nothing resolves
     * @param autoResolved   not an input — rendered in the collapsed
     *                       "Udfyldes automatisk" summary (COMPANY, SYSTEM_DATE)
     * @param serverResolved value resolves server-side at document generation
     *                       and renders masked in the form (CPR, current salary);
     *                       {@code maskedPreview} is the display stand-in
     * @param maskedPreview  masked display value for server-resolved fields
     * @param suggestions    click-to-apply interview-fact suggestions
     *                       (dossier flow only) — never auto-inserted
     */
    public record PrefillField(
            String key,
            String source,
            String sourceField,
            String value,
            String provenance,
            boolean autoResolved,
            boolean serverResolved,
            String maskedPreview,
            List<FactSuggestion> suggestions) {
    }

    /**
     * One interview-fact suggestion: free-text value with its derived state
     * and when it was stated. CONFIRMED is preferred; STALE is flagged.
     */
    public record FactSuggestion(String value, String state, String statedAt) {
    }
}
