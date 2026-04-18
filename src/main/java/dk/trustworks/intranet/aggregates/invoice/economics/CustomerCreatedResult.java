package dk.trustworks.intranet.aggregates.invoice.economics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * e-conomic POST /Customers response envelope.
 *
 * <p>Per OpenAPI {@code CustomerCreatedResult} schema (Customers v3.1.0).
 * Only {@code customerNumber} is returned; the full Customer is NOT echoed back.
 * Callers that need other fields must GET the customer by number.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomerCreatedResult {
    private Integer customerNumber;
}
