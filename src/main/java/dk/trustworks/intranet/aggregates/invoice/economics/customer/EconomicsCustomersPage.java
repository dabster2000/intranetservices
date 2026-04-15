package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Page wrapper for the e-conomic Customers API v3.1.0 {@code GET /Customers}
 * response. The list field in the response is {@code items} in v3.1.0.
 *
 * SPEC-INV-001 §6.3.
 */
@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EconomicsCustomersPage {
    private List<EconomicsCustomerDto> items;
    private Pagination pagination;

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pagination {
        private int skipPages;
        private int pageSize;
        private int maxPageSizeAllowed;
        private int results;
        private int resultsWithoutFilter;
        private String firstPage;
        private String nextPage;
        private String previousPage;
        private String lastPage;
    }
}
