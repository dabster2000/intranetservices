package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Synchronises a Trustworks {@link Contract}'s billing attention to the
 * e-conomic Customers v3.1.0 {@code /Contacts} resource, per Trustworks
 * agreement (one row per {@code (client, company, contact_name)} tuple in
 * {@code client_economics_contacts}).
 *
 * <p>Mirrors {@link EconomicsCustomerSyncService}'s POST/PUT + 409 retry
 * protocol. Skips gracefully when the customer pairing row is missing or the
 * contract has no {@code billingAttention}.
 *
 * <p>Failures are recorded on the shared
 * {@code client_economics_sync_failures} table — one row per
 * {@code (client_uuid, company_uuid)}; contact and customer sync share the
 * retry channel because they target the same agreement.
 *
 * SPEC-INV-001 §3.3.2, §6.8, §7.1 Phase G2, §13.1 item 10.
 */
@ApplicationScoped
public class EconomicsCustomerContactSyncService {

    private static final Logger LOG = Logger.getLogger(EconomicsCustomerContactSyncService.class);

    private static final int MAX_CONFLICT_RETRIES = 2;

    private final ClientEconomicsCustomerRepository customerRepo;
    private final ClientEconomicsContactRepository contactRepo;
    private final ClientEconomicsSyncFailureRepository failures;
    private final SyncFailureRecorder failureRecorder;
    private final ContactAgreementResolver agreementResolver;
    private final AgreementDefaultsRegistry agreementDefaults;
    private final ContractToEconomicsContactMapper mapper;

    @Inject
    public EconomicsCustomerContactSyncService(ClientEconomicsCustomerRepository customerRepo,
                                               ClientEconomicsContactRepository contactRepo,
                                               ClientEconomicsSyncFailureRepository failures,
                                               SyncFailureRecorder failureRecorder,
                                               ContactAgreementResolver agreementResolver,
                                               AgreementDefaultsRegistry agreementDefaults,
                                               ContractToEconomicsContactMapper mapper) {
        this.customerRepo = customerRepo;
        this.contactRepo = contactRepo;
        this.failures = failures;
        this.failureRecorder = failureRecorder;
        this.agreementResolver = agreementResolver;
        this.agreementDefaults = agreementDefaults;
        this.mapper = mapper;
    }

    // --------------------------------------------------- public API

    /**
     * Syncs the contract's contact to every configured agreement. Non-blocking:
     * per-agreement errors are logged at WARN and recorded for retry.
     */
    public void syncContactToAllCompanies(Contract contract, Client billingClient) {
        Objects.requireNonNull(contract, "contract must not be null");
        Objects.requireNonNull(billingClient, "billingClient must not be null");

        if (contract.getBillingAttention() == null || contract.getBillingAttention().isBlank()) {
            LOG.debugf("Contract %s has no billing attention — skipping contact sync", contract.getUuid());
            return;
        }

        Set<String> companies = agreementDefaults.listConfiguredCompanies();
        for (String companyUuid : companies) {
            try {
                syncContactToCompany(contract, billingClient, companyUuid);
            } catch (SyncFailedException e) {
                LOG.warnf(e, "Contact sync failed for client %s / company %s", billingClient.getUuid(), companyUuid);
            }
        }
    }

    /**
     * Syncs the contract's contact to one agreement. Throws
     * {@link SyncFailedException} on error (the failure row is already
     * persisted). Skips without error when the customer pairing is missing.
     */
    @Transactional
    public void syncContactToCompany(Contract contract, Client billingClient, String companyUuid) {
        Objects.requireNonNull(contract, "contract must not be null");
        Objects.requireNonNull(billingClient, "billingClient must not be null");
        Objects.requireNonNull(companyUuid, "companyUuid must not be null");

        if (contract.getBillingAttention() == null || contract.getBillingAttention().isBlank()) {
            LOG.debugf("Contract %s has no billing attention — skipping contact sync", contract.getUuid());
            return;
        }

        Optional<ClientEconomicsCustomer> customer = customerRepo.findByClientAndCompany(
                billingClient.getUuid(), companyUuid);
        if (customer.isEmpty()) {
            LOG.warnf("No customer pairing for client %s in company %s — skipping contact sync",
                    billingClient.getUuid(), companyUuid);
            return;
        }
        int customerNumber = customer.get().getCustomerNumber();

        EconomicsContactApiClient api = agreementResolver.apiFor(companyUuid);
        String attention = contract.getBillingAttention();

        try {
            Optional<ClientEconomicsContact> existing = contactRepo.findByClientCompanyAndName(
                    billingClient.getUuid(), companyUuid, attention);

            EconomicsContactDto body = mapper.toUpsertBody(contract, billingClient, customerNumber);
            if (existing.isEmpty()) {
                postContact(api, billingClient, companyUuid, attention, body);
            } else {
                putContactWithConcurrency(api, billingClient, companyUuid, attention, existing.get(), body);
            }
            // REQUIRES_NEW — see EconomicsCustomerSyncService for rationale.
            failureRecorder.clear(billingClient.getUuid(), companyUuid);
        } catch (WebApplicationException e) {
            String status = e.getResponse() == null ? "?" : Integer.toString(e.getResponse().getStatus());
            failureRecorder.record(billingClient.getUuid(), companyUuid,
                    "Contact sync HTTP " + status + ": " + safeMessage(e));
            throw new SyncFailedException("Contact sync failed for "
                    + billingClient.getUuid() + "/" + companyUuid, e);
        } catch (RuntimeException e) {
            failureRecorder.record(billingClient.getUuid(), companyUuid,
                    "Contact sync " + e.getClass().getSimpleName() + ": " + safeMessage(e));
            throw new SyncFailedException("Contact sync failed for "
                    + billingClient.getUuid() + "/" + companyUuid, e);
        }
    }

    // --------------------------------------------------- HTTP helpers

    private void postContact(EconomicsContactApiClient api,
                             Client billingClient, String companyUuid,
                             String attention, EconomicsContactDto body) {
        // POST returns CreatedResult{number}; re-GET to obtain objectVersion.
        var created = api.createContact(body);
        int number = Objects.requireNonNull(created.getNumber(),
                "e-conomic returned no number for contact " + attention);
        EconomicsContactDto full = api.getContact(number);
        upsertContactMapping(billingClient.getUuid(), companyUuid, attention,
                number, full.getObjectVersion());
    }

    private void putContactWithConcurrency(EconomicsContactApiClient api,
                                           Client billingClient, String companyUuid,
                                           String attention,
                                           ClientEconomicsContact existing,
                                           EconomicsContactDto body) {
        int attempts = 0;
        while (true) {
            EconomicsContactDto fresh = api.getContact(existing.getCustomerContactNumber());
            body.setCustomerContactNumber(existing.getCustomerContactNumber());
            body.setObjectVersion(fresh.getObjectVersion());
            try {
                api.updateContact(existing.getCustomerContactNumber(), body);
                // PUT returns empty body — re-GET to capture the new objectVersion.
                EconomicsContactDto after = api.getContact(existing.getCustomerContactNumber());
                upsertContactMapping(billingClient.getUuid(), companyUuid, attention,
                        existing.getCustomerContactNumber(), after.getObjectVersion());
                return;
            } catch (WebApplicationException e) {
                int status = e.getResponse() == null ? 0 : e.getResponse().getStatus();
                if (status == Response.Status.CONFLICT.getStatusCode() && attempts < MAX_CONFLICT_RETRIES) {
                    attempts++;
                    LOG.infof("PUT conflict for contact %d; retrying with fresh objectVersion (attempt %d/%d)",
                            existing.getCustomerContactNumber(), attempts, MAX_CONFLICT_RETRIES);
                    continue;
                }
                throw e;
            }
        }
    }

    // --------------------------------------------------- persistence helpers

    private void upsertContactMapping(String clientUuid, String companyUuid, String contactName,
                                      int customerContactNumber, String objectVersion) {
        ClientEconomicsContact row = contactRepo
                .findByClientCompanyAndName(clientUuid, companyUuid, contactName)
                .orElseGet(() -> {
                    ClientEconomicsContact n = new ClientEconomicsContact();
                    n.setUuid(UUID.randomUUID().toString());
                    n.setClientUuid(clientUuid);
                    n.setCompanyUuid(companyUuid);
                    n.setContactName(contactName);
                    return n;
                });
        row.setCustomerContactNumber(customerContactNumber);
        row.setObjectVersion(objectVersion);
        row.setReceiveEInvoices(true);
        row.setSyncedAt(LocalDateTime.now());
        contactRepo.persist(row);
    }

    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }
}
