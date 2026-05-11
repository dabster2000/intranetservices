package dk.trustworks.intranet.aggregates.invoice.economics.supplier.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * Minimal view of an e-conomic supplier returned by GET /suppliers.
 * Only the fields the resolver needs are mapped; all others are ignored.
 */
@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SupplierDto {

    @JsonProperty("supplierNumber")
    private int supplierNumber;

    @JsonProperty("corporateIdentificationNumber")
    private String corporateIdentificationNumber;
}
