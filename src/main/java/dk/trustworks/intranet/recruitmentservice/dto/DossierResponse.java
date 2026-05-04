package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Read model for a candidate's dossier — the working draft of placeholders,
 * signer configuration and appendices, plus its lifecycle status. The actual
 * dossier-template binding ({@code templateUuid}) is fixed at create time and
 * cannot be changed via PATCH.
 */
public record DossierResponse(
        String uuid,
        String candidateUuid,
        String templateUuid,
        Map<String, String> placeholderValues,
        List<SignerConfigDto> signersConfig,
        String status,
        List<AppendixDto> appendices,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
