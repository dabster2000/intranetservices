package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.contracts.model.enums.ContractStatus;

import java.util.List;

/** Lightweight contract identity used by agreement pickers and parameter surfaces. */
public record AgreementContractDTO(
        String uuid,
        String name,
        String clientUuid,
        String clientName,
        ContractStatus status,
        List<ContractTypeItemDTO> parameters) {
}
