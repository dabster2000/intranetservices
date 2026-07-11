package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.contracts.model.enums.ContractStatus;

import java.math.BigDecimal;
import java.util.List;

/** Agreement contract identity plus rolling last-12-month external invoice revenue. */
public record AgreementContractRevenueDTO(
        String uuid,
        String name,
        String clientUuid,
        String clientName,
        ContractStatus status,
        List<ContractTypeItemDTO> parameters,
        BigDecimal revenueLast12Months) {
}
