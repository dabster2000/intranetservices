package dk.trustworks.intranet.aggregates.invoice.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.contracts.model.Contract;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RuleContexts {
    @Inject ObjectMapper om;
    public RuleContext forInvoice(Contract contract, Invoice invoice) {
        return new RuleContext(contract, invoice, om);
    }
}
