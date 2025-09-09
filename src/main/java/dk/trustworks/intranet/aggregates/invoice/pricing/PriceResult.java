// src/main/java/dk/trustworks/intranet/aggregates/invoice/pricing/PriceResult.java
package dk.trustworks.intranet.aggregates.invoice.pricing;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import java.math.BigDecimal;
import java.util.List;

public class PriceResult {
    public BigDecimal sumBeforeDiscounts;
    public BigDecimal sumAfterDiscounts;
    public BigDecimal vatAmount;
    public BigDecimal grandTotal;
    public List<CalculationBreakdownLine> breakdown;
    public List<InvoiceItem> syntheticItems; // origin = CALCULATED (1 x rate)
}
