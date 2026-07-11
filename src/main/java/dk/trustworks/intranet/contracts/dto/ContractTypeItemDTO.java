package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.contracts.model.ContractTypeItem;

/** Public contract-parameter shape; deliberately omits the owning contract UUID. */
public record ContractTypeItemDTO(int id, String key, String value) {

    public static ContractTypeItemDTO fromEntity(ContractTypeItem item) {
        return new ContractTypeItemDTO(item.getId(), item.getKey(), item.getValue());
    }
}
