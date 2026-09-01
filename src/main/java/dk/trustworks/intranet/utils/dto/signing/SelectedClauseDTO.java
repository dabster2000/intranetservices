package dk.trustworks.intranet.utils.dto.signing;

import java.util.Map;

/**
 * One clause the preparer selected for a signing bundle — either a
 * library clause ({@code clauseUuid} set, {@code parameterValues} carry
 * its typed parameters) or a free-text "Individuel aftale"
 * ({@code clauseUuid} null, {@code customTitle}/{@code customText} set).
 * <p>
 * The same shape is stored as the dossier draft's {@code clauses_json},
 * frozen into {@code candidate_dossier_revisions.clauses_snapshot}, and
 * snapshotted per case into {@code signing_case_clauses}.
 *
 * @param clauseUuid      library clause UUID; null for Individuel aftale
 * @param parameterValues values for the clause's placeholders (raw,
 *                        unformatted — the backend applies type-aware
 *                        formatting like the base form values)
 * @param customTitle     Individuel aftale title (required when custom)
 * @param customText      Individuel aftale free text (required when custom)
 * @param displayOrder    order in the clause step and in rendered output
 */
public record SelectedClauseDTO(
        String clauseUuid,
        Map<String, String> parameterValues,
        String customTitle,
        String customText,
        Integer displayOrder
) {
    public boolean isCustom() {
        return clauseUuid == null || clauseUuid.isBlank();
    }

    public int orderOrDefault(int fallback) {
        return displayOrder != null ? displayOrder : fallback;
    }
}
