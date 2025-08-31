package dk.trustworks.intranet.aggregates.invoice.rules;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.contracts.model.Contract;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class PricingRulesEngine {

    @Inject
    ContractRulesService bindings;
    @Inject
    RuleRegistry registry;
    @Inject
    RuleContexts contexts;

    @Transactional
    public void reapplyAll(Contract contract, Invoice invoice) {
        // remove existing system lines in DB & memory (idempotent recompute)
        InvoiceItem.delete("invoiceuuid LIKE ?1 AND origin = 'AUTO_RULE'", invoice.getUuid());
        invoice.getInvoiceitems().removeIf(ii -> ii.origin == InvoiceItem.ItemOrigin.AUTO_RULE);

        var ctx = contexts.forInvoice(contract, invoice);

        for (RuleBinding b : bindings.bindingsFor(contract)) {
            var ruleOpt = registry.find(b.code());
            if (ruleOpt.isEmpty()) continue;
            var rule = ruleOpt.get();
            ctx.withParams(b.code(), b.params());
            if (rule.appliesTo(ctx)) rule.apply(ctx);
        }

        // persist newly added system items
        InvoiceItem.persist(
            invoice.getInvoiceitems().stream()
                   .filter(ii -> ii.origin == InvoiceItem.ItemOrigin.AUTO_RULE)
                   .toList()
        );
    }
}
