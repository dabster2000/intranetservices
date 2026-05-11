package dk.trustworks.intranet.aggregates.invoice.economics.supplier.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Paged response wrapper for e-conomic GET /suppliers.
 * Only the {@code collection} field is mapped; pagination/meta fields are ignored.
 */
@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SuppliersPage {

    @JsonProperty("collection")
    private List<SupplierDto> collection = new ArrayList<>();
}
