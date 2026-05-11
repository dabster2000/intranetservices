package dk.trustworks.intranet.aggregates.invoice.economics.supplier.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Paged response wrapper for e-conomic GET /suppliers.
 * Only the {@code collection} field is mapped; pagination/meta fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SuppliersPage {

    @JsonProperty("collection")
    public List<SupplierDto> collection = new ArrayList<>();

    public SuppliersPage() {}

    public List<SupplierDto> getCollection() {
        return collection;
    }

    public void setCollection(List<SupplierDto> collection) {
        this.collection = collection;
    }
}
