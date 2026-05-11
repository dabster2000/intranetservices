package dk.trustworks.intranet.expenseservice.remote.dto.economics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Supplier {

    @JsonProperty("supplierNumber")
    public int supplierNumber;

    public Supplier() {}

    public Supplier(int supplierNumber) {
        this.supplierNumber = supplierNumber;
    }

    @JsonProperty("supplierNumber")
    public int getSupplierNumber() {
        return supplierNumber;
    }

    @JsonProperty("supplierNumber")
    public void setSupplierNumber(int supplierNumber) {
        this.supplierNumber = supplierNumber;
    }

    @Override
    public String toString() {
        return "Supplier{supplierNumber=" + supplierNumber + "}";
    }
}
