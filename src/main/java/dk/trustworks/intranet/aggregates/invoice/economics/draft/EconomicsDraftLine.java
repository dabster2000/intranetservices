package dk.trustworks.intranet.aggregates.invoice.economics.draft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter; import lombok.Setter;

/**
 * Q2C v5.1.0 draft-invoice line. Priced lines require productNumber (§6.4).
 * Description-only lines (no productNumber, no quantity, no unitNetPrice)
 * are used for headings/spacers.
 */
@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EconomicsDraftLine {
    private String  productNumber;   // required on priced lines
    private String  description;
    private Double  quantity;
    private Double  unitNetPrice;
    // Echoed back:
    private Double  totalNetAmount;
    private Integer lineNumber;
}
