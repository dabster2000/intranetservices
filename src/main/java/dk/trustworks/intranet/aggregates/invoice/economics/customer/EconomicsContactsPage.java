package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Page wrapper for the e-conomic Customers API v3.1.0 {@code GET /Contacts}
 * response. Like {@code /Customers}, the list field is {@code items} and the
 * envelope exposes the same pagination metadata (confirmed by Phase G0 probe).
 *
 * SPEC-INV-001 §3.3.2, §6.1.
 */
@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EconomicsContactsPage {
    private List<EconomicsContactDto> items;
    private EconomicsCustomersPage.Pagination pagination;
}
