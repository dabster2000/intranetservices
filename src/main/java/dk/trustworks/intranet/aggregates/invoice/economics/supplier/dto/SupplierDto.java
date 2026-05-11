package dk.trustworks.intranet.aggregates.invoice.economics.supplier.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Minimal view of an e-conomic supplier returned by GET /suppliers.
 * Only the fields the resolver needs are mapped; all others are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SupplierDto {

    @JsonProperty("supplierNumber")
    public int supplierNumber;

    @JsonProperty("corporateIdentificationNumber")
    public String corporateIdentificationNumber;

    public SupplierDto() {}

    public int getSupplierNumber() {
        return supplierNumber;
    }

    public void setSupplierNumber(int supplierNumber) {
        this.supplierNumber = supplierNumber;
    }

    public String getCorporateIdentificationNumber() {
        return corporateIdentificationNumber;
    }

    public void setCorporateIdentificationNumber(String corporateIdentificationNumber) {
        this.corporateIdentificationNumber = corporateIdentificationNumber;
    }
}
