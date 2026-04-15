package dk.trustworks.intranet.aggregates.invoice.economics;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;

/**
 * Immutable value carrier for everything the {@link InvoiceToEconomicsDraftMapper} needs to
 * build a Q2C v5.1.0 draft invoice. Assembled by the application service before mapping begins.
 * SPEC-INV-001 §6.4.
 *
 * @param invoice             The invoice to map (required).
 * @param contract            The linked contract — may be null for phantom / orphan invoices.
 *                            The mapper throws loudly when contract-specific fields are needed.
 * @param billingClient       The resolved billing client (required).
 * @param layoutNumber        e-conomic layout number for the company/agreement.
 * @param termOfPaymentNumber e-conomic payment term number for the company/agreement.
 * @param vatZoneNumber       e-conomic VAT zone number for the company/agreement.
 * @param productNumber       e-conomic product number used on every priced line.
 */
public record DraftContext(
        Invoice  invoice,
        Contract contract,
        Client   billingClient,
        int      layoutNumber,
        int      termOfPaymentNumber,
        int      vatZoneNumber,
        String   productNumber
) {}
