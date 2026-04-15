package dk.trustworks.intranet.aggregates.invoice.economics.draft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter; import lombok.Setter;
import java.util.List;

@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EconomicsDraftPage {
    private List<EconomicsDraftInvoice> collection;
    // pagination omitted — pre-retry reconciliation uses pagesize=1 filter
}
