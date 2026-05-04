package dk.trustworks.intranet.recruitmentservice.dto;

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
 */
public record DossierRequest(
        Map<String, String> placeholderValues,
        List<SignerConfigDto> signersConfig
) {
}
