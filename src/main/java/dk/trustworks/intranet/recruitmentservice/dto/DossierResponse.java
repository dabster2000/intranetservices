package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.DossierStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record DossierResponse(
        String uuid,
        String candidateUuid,
        String templateUuid,
        Map<String, String> placeholderValues,
        List<SignerConfigDto> signersConfig,
        DossierStatus status,
        List<AppendixDto> appendices,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
