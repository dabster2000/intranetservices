package dk.trustworks.intranet.aggregates.crm.enrichment.services;

import dk.trustworks.intranet.dao.crm.client.CvrApiResponse;
import dk.trustworks.intranet.dao.crm.model.Client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Writes what the CVR registry says onto a client row — "registry wins", the decision
 * taken 2026-09-14 — and reports every field it changed so the caller can file the same
 * activity-log entries a person's edit would.
 *
 * <p>Two deliberate exceptions to "registry wins":
 * <ul>
 *   <li>A blank registry value never blanks a stored one. Virkdata answers an empty phone
 *       or e-mail for many companies; that is absence of data, not a correction.</li>
 *   <li>{@code billingEmail} is filled only when empty. It is where invoices go, it was
 *       typed by whoever set the client up for billing, and the registry's contact address
 *       is the switchboard, not accounts payable.</li>
 * </ul>
 * {@code companyCode} is left alone: Virkdata does not expose the legal-form code
 * ({@code CvrApiResponse.companycode} is always 0), only its description.
 *
 * <p>Pure and static, so the fast tier can pin every rule without a database.
 */
public final class RegistryApplier {

    private RegistryApplier() {
    }

    /** One field the registry changed, in the activity log's own vocabulary. */
    public record FieldChange(String field, String oldValue, String newValue) {}

    /**
     * @param cvr the CVR the registry was asked about — stored on the row when it differs,
     *            which is the "AI found the missing CVR" case
     */
    public static List<FieldChange> apply(Client client, CvrApiResponse registry, String cvr) {
        List<FieldChange> changes = new ArrayList<>();
        if (cvr != null && !cvr.isBlank() && !Objects.equals(client.getCvr(), cvr)) {
            changes.add(new FieldChange("cvr", client.getCvr(), cvr));
            client.setCvr(cvr);
        }
        overwrite(changes, "name", client.getName(), registry.name, client::setName);
        overwrite(changes, "billingAddress", client.getBillingAddress(), registry.address, client::setBillingAddress);
        overwrite(changes, "billingZipcode", client.getBillingZipcode(), registry.zipcode, client::setBillingZipcode);
        overwrite(changes, "billingCity", client.getBillingCity(), registry.city, client::setBillingCity);
        overwrite(changes, "phone", client.getPhone(), registry.phone, client::setPhone);
        overwrite(changes, "industryDesc", client.getIndustryDesc(), registry.industrydesc, client::setIndustryDesc);
        overwrite(changes, "companyDesc", client.getCompanyDesc(), registry.companydesc, client::setCompanyDesc);
        if (registry.industrycode > 0 && !Objects.equals(client.getIndustryCode(), registry.industrycode)) {
            changes.add(new FieldChange("industryCode", String.valueOf(client.getIndustryCode()), String.valueOf(registry.industrycode)));
            client.setIndustryCode(registry.industrycode);
        }
        if (isBlank(client.getBillingEmail()) && !isBlank(registry.email)) {
            changes.add(new FieldChange("billingEmail", client.getBillingEmail(), registry.email.trim()));
            client.setBillingEmail(registry.email.trim());
        }
        return changes;
    }

    private static void overwrite(List<FieldChange> changes, String field, String current, String incoming,
                                  java.util.function.Consumer<String> setter) {
        if (isBlank(incoming)) return;
        String value = incoming.trim();
        if (Objects.equals(current, value)) return;
        changes.add(new FieldChange(field, current, value));
        setter.accept(value);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
