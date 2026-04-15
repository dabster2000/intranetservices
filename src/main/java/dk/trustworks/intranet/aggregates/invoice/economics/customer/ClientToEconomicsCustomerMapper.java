package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.utils.CountryCodeMapper;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Maps a Trustworks {@link Client} to the full Customers v3.1.0 {@code /Customers}
 * upsert body per SPEC-INV-001 §6.3. Pure — no persistence, no API calls — so
 * it stays trivially unit-testable.
 *
 * <p>Two use cases:
 * <ul>
 *   <li>{@link #toMinimalCreateBody} — minimum-viable POST body used by
 *       legacy pairing flows that only need customerNumber/name/group/etc.</li>
 *   <li>{@link #toFullUpsertBody} — full §6.3 payload for Phase G2 sync:
 *       address1/address2 split, postCode/city/country, email, EAN-derived
 *       NemHandel fields, defaultDisableEInvoicing.</li>
 * </ul>
 *
 * SPEC-INV-001 §6.3.
 */
@ApplicationScoped
public class ClientToEconomicsCustomerMapper {

    /** Max chars per address line in Customers v3.1.0 (address1 and address2). */
    private static final int ADDRESS_LINE_MAX = 255;

    /**
     * Minimum-viable POST body — only the fields required by Customers v3.1.0
     * to create a customer (customerNumber, name, group, zone, currency,
     * paymentTermId). Used by the legacy pairing "create" flow which immediately
     * follows up with {@link #toFullUpsertBody} for a PUT that fills in the rest.
     */
    public EconomicsCustomerDto toMinimalCreateBody(
            Client client,
            int customerGroupNumber,
            int paymentTermNumber,
            int vatZoneNumber) {
        EconomicsCustomerDto body = new EconomicsCustomerDto();
        body.setName(client.getName());
        body.setCvrNo(client.getCvr());
        body.setCurrency(client.getCurrency());
        body.setCustomerGroupNumber(customerGroupNumber);
        body.setPaymentTermId(paymentTermNumber);
        body.setZone(vatZoneNumber);
        return body;
    }

    /**
     * Full §6.3 upsert body. All optional fields are populated when the
     * underlying {@link Client} has a value; missing values remain {@code null}
     * so e-conomic clears / keeps existing data per the server's patch rules.
     *
     * <p>Address handling: if {@code billing_address} exceeds 255 chars, the
     * excess spills into {@code address2} (up to another 255 chars). Extra
     * overflow beyond 510 chars is silently dropped — real addresses never
     * come close.
     *
     * <p>NemHandel: when {@code client.ean} is present, sets
     * {@code nemHandelReceiverType = 1} (EAN) AND enables e-invoicing via
     * {@code defaultDisableEInvoicing = false}. When EAN is absent, leaves
     * {@code nemHandelReceiverType = null} and disables e-invoicing
     * ({@code defaultDisableEInvoicing = true}) so e-conomic falls back to
     * classic email delivery.
     */
    public EconomicsCustomerDto toFullUpsertBody(
            Client client,
            int customerGroupNumber,
            int paymentTermNumber,
            int vatZoneNumber) {
        EconomicsCustomerDto body = toMinimalCreateBody(client, customerGroupNumber, paymentTermNumber, vatZoneNumber);

        // Address1 / address2 split (spec §6.3: address1 ≤255, address2 overflow).
        String addr = client.getBillingAddress();
        if (addr != null && !addr.isBlank()) {
            if (addr.length() > ADDRESS_LINE_MAX) {
                body.setAddress1(addr.substring(0, ADDRESS_LINE_MAX));
                int end = Math.min(addr.length(), ADDRESS_LINE_MAX * 2);
                body.setAddress2(addr.substring(ADDRESS_LINE_MAX, end));
            } else {
                body.setAddress1(addr);
            }
        }

        body.setPostCode(nullIfBlank(client.getBillingZipcode()));
        body.setCity(nullIfBlank(client.getBillingCity()));
        body.setEmail(nullIfBlank(client.getBillingEmail()));
        body.setPhone(nullIfBlank(client.getPhone()));

        // Country: ISO-2 → e-conomic full name. Leave null when unset so the
        // server keeps whatever was there (e-conomic rejects empty strings).
        if (client.getBillingCountry() != null && !client.getBillingCountry().isBlank()) {
            body.setCountry(CountryCodeMapper.toEconomicsName(client.getBillingCountry()));
        }

        // EAN + NemHandel + e-invoicing — §6.3 rule.
        String ean = nullIfBlank(client.getEan());
        if (ean != null) {
            body.setEanLocationNumber(ean);
            body.setNemHandelReceiverType(1);           // 1 = EAN
            body.setDefaultDisableEInvoicing(false);    // e-invoicing enabled
        } else {
            body.setNemHandelReceiverType(null);
            body.setDefaultDisableEInvoicing(true);     // fall back to email delivery
        }

        return body;
    }

    private static String nullIfBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
