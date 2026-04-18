package dk.trustworks.intranet.aggregates.invoice.economics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Standard e-conomic POST response envelope for resources whose primary key is
 * surfaced as {@code number}: Q2C draft invoices, Customers/Contacts, lines, etc.
 *
 * <p>Per OpenAPI {@code CreatedResult} schema (Q2C v5.1.0, Customers v3.1.0).
 * The full resource is NOT echoed back — callers that need other fields must
 * GET the resource by the returned number.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreatedResult {
    private Integer number;
}
