package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.model.Company;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * Resolves the intercompany {@link Client} row that corresponds to a debtor
 * {@link Company} by matching {@code client.cvr = company.cvr}.
 *
 * <p>Used at INTERNAL / INTERNAL_SERVICE invoice creation time so the resulting
 * invoice carries a stable {@code billing_client_uuid} pointing at the correct
 * intercompany billing entity — avoiding the contract-based fallback which
 * addresses INTERNAL drafts to the source contract's external client.
 *
 * <p>This is a pure lookup: it never throws on missing data and never logs at
 * ERROR. Callers (see {@link InvoiceService} creation paths and
 * {@link BillingContextResolver}) translate {@code Optional.empty()} to the
 * appropriate HTTP 400 fail-closed response.
 *
 * <p>SPEC: internal-invoice-billing-client-fix § FR-1.
 */
@ApplicationScoped
public class IntercompanyClientResolver {

    /**
     * Finds the intercompany {@link Client} whose CVR matches the debtor
     * Company's CVR.
     *
     * @param debtorCompanyUuid the debtor Company's UUID (from
     *                          {@code Invoice.debtor_companyuuid}).
     * @return {@link Optional#empty()} when the Company is missing, its CVR is
     *         blank, or no Client row has that CVR. Otherwise the matching
     *         Client.
     */
    public Optional<Client> resolveByDebtorCompanyUuid(String debtorCompanyUuid) {
        if (debtorCompanyUuid == null || debtorCompanyUuid.isBlank()) {
            return Optional.empty();
        }
        Company debtorCompany = Company.findById(debtorCompanyUuid);
        if (debtorCompany == null) {
            return Optional.empty();
        }
        String cvr = debtorCompany.getCvr();
        if (cvr == null || cvr.isBlank()) {
            return Optional.empty();
        }
        // Parameter-bound query — never concatenate the CVR value (security §1).
        return Client.find("cvr = ?1", cvr).firstResultOptional();
    }
}
