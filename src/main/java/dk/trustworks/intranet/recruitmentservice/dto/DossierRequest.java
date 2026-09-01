package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.utils.dto.signing.SelectedClauseDTO;

import java.util.List;
import java.util.Map;

/**
 * Autosave payload for the dossier draft. Both fields are nullable — the
 * frontend may PATCH only the section the user just edited (placeholders or
 * signers) and leave the other section untouched. The application service
 * only mutates the JSON columns whose corresponding field on this DTO is
 * non-null.
 *
 * @param placeholderValues map of placeholder key (e.g. {@code "START_DATE"})
 *                          to its current draft value
 * @param signersConfig     ordered list of signer entries (signing order is
 *                          the list order)
 * @param clauses           ordered clause selection on the draft
 *                          (template-clauses Phase 2); null leaves the
 *                          stored selection untouched, an empty list clears it
 */
public record DossierRequest(
        Map<String, String> placeholderValues,
        List<SignerConfigDto> signersConfig,
        List<SelectedClauseDTO> clauses
) {
    public DossierRequest(Map<String, String> placeholderValues, List<SignerConfigDto> signersConfig) {
        this(placeholderValues, signersConfig, null);
    }
}
