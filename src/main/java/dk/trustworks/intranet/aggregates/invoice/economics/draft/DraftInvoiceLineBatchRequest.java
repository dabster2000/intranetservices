package dk.trustworks.intranet.aggregates.invoice.economics.draft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Q2C v5.1.0 request envelope for {@code POST /invoices/drafts/{id}/lines/bulk}.
 *
 * <p>The endpoint requires a JSON object with a {@code lines} array, NOT a
 * top-level array. Sending a bare array yields {@code 400 InvalidInput}
 * ("Expected a JSON with an object as the root element").
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DraftInvoiceLineBatchRequest {
    private List<EconomicsDraftLine> lines;
}
