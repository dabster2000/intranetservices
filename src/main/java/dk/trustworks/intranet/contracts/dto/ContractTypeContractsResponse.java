package dk.trustworks.intranet.contracts.dto;

import java.util.List;

/** Response for GET /api/contract-types/{code}/contracts. */
public record ContractTypeContractsResponse(
        String contractTypeCode,
        long totalCount,
        List<AgreementContractDTO> contracts,
        List<AgreementContractRevenueDTO> topContractsByRevenue) {
}
