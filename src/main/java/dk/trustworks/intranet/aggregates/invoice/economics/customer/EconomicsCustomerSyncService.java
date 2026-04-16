package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.ClientSyncStatusDto;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.model.Company;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Synchronises a Trustworks {@link Client} to the e-conomic Customers v3.1.0
 * {@code /Customers} resource, per Trustworks agreement (one row per company
 * in {@code client_economics_customer}).
 *
 * <p><b>Protocol</b> — for each configured agreement:
 * <ol>
 *   <li>If no pairing row exists: {@code POST /Customers} with the mapped body,
 *       then persist a {@link PairingSource#CREATED} row.</li>
 *   <li>If a pairing row exists: {@code GET /Customers/{n}} to fetch the current
 *       {@code objectVersion}, then {@code PUT /Customers/{n}} with the fresh
 *       version. On HTTP 409 (concurrent update), retry the GET-merge-PUT loop
 *       up to {@value #MAX_CONFLICT_RETRIES} times.</li>
 * </ol>
 *
 * <p><b>Failure handling</b> — on any non-409 error, the failure is upserted
 * into {@code client_economics_sync_failures} with exponential backoff
 * ({@value #MAX_ATTEMPTS_BEFORE_ABANDON} attempts before status=ABANDONED) and
 * a {@link SyncFailedException} is thrown. Callers at the resource boundary
 * treat this as a non-fatal warning — local persistence must still succeed.
 *
 * <p><b>Success</b> clears any existing failure row for the same (client,company).
 *
 * SPEC-INV-001 §3.3, §6.3, §6.8, §7.1 Phase G2.
 */
@ApplicationScoped
public class EconomicsCustomerSyncService {

    private static final Logger LOG = Logger.getLogger(EconomicsCustomerSyncService.class);

    /** PUT retries on 409 conflict (GET-merge-retry). Each retry re-GETs. */
    private static final int MAX_CONFLICT_RETRIES = 2;

    private final ClientEconomicsCustomerRepository repo;
    private final ClientEconomicsSyncFailureRepository failures;
    private final SyncFailureRecorder failureRecorder;
    private final AgreementResolver agreementResolver;
    private final AgreementDefaultsRegistry agreementDefaults;
    private final ClientToEconomicsCustomerMapper mapper;
    private final EconomicsCustomerIndexCache indexCache;

    @Inject
    public EconomicsCustomerSyncService(ClientEconomicsCustomerRepository repo,
                                        ClientEconomicsSyncFailureRepository failures,
                                        SyncFailureRecorder failureRecorder,
                                        AgreementResolver agreementResolver,
                                        AgreementDefaultsRegistry agreementDefaults,
                                        ClientToEconomicsCustomerMapper mapper,
                                        EconomicsCustomerIndexCache indexCache) {
        this.repo = repo;
        this.failures = failures;
        this.failureRecorder = failureRecorder;
        this.agreementResolver = agreementResolver;
        this.agreementDefaults = agreementDefaults;
        this.mapper = mapper;
        this.indexCache = indexCache;
    }

    // --------------------------------------------------- public API

    /**
     * Syncs one client to every Trustworks agreement with an e-conomic
     * configuration. Non-blocking: per-agreement failures are logged at WARN
     * and recorded for retry, but do not propagate to the caller.
     */
    public void syncToAllCompanies(Client client) {
        Objects.requireNonNull(client, "client must not be null");
        Set<String> companies = agreementDefaults.listConfiguredCompanies();
        for (String companyUuid : companies) {
            try {
                syncToCompany(client, companyUuid);
            } catch (SyncFailedException e) {
                LOG.warnf(e, "Sync failed for client %s to company %s", client.getUuid(), companyUuid);
            }
        }
    }

    /**
     * Syncs one client to a single agreement. Records the failure row and
     * throws {@link SyncFailedException} on any error; clears the failure row
     * on success.
     */
    @Transactional
    public void syncToCompany(Client client, String companyUuid) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(companyUuid, "companyUuid must not be null");

        AgreementDefaults defaults = agreementDefaults.requireFor(companyUuid);
        EconomicsCustomerApiClient api = agreementResolver.apiFor(companyUuid);
        int groupNumber = defaults.groupNumberFor(client.getType());
        int paymentTerm = defaults.paymentTermId();
        int vatZone     = defaults.vatZoneNumber();

        Optional<ClientEconomicsCustomer> existing = repo.findByClientAndCompany(client.getUuid(), companyUuid);
        try {
            if (existing.isPresent()) {
                putWithConcurrency(api, client, companyUuid, existing.get(), groupNumber, paymentTerm, vatZone);
            } else {
                post(api, client, companyUuid, groupNumber, paymentTerm, vatZone);
            }
            // Success → clear failure row in its own transaction so the retry
            // batchlet sees cleared state regardless of what the caller does.
            failureRecorder.clear(client.getUuid(), companyUuid);
        } catch (WebApplicationException e) {
            String status = e.getResponse() == null ? "?" : Integer.toString(e.getResponse().getStatus());
            // REQUIRES_NEW: the failure row must survive the rollback that
            // SyncFailedException triggers in our surrounding @Transactional.
            failureRecorder.record(client.getUuid(), companyUuid,
                    "HTTP " + status + ": " + safeMessage(e));
            throw new SyncFailedException("Customer sync failed for "
                    + client.getUuid() + "/" + companyUuid, e);
        } catch (RuntimeException e) {
            failureRecorder.record(client.getUuid(), companyUuid,
                    e.getClass().getSimpleName() + ": " + safeMessage(e));
            throw new SyncFailedException("Customer sync failed for "
                    + client.getUuid() + "/" + companyUuid, e);
        }
    }

    /**
     * Returns one {@link ClientSyncStatusDto} per configured Trustworks company
     * for the given client, joining the pairing row and any outstanding failure
     * row. Intended for the {@code GET /economics/sync-status} endpoint and the
     * client-detail sync badge. SPEC-INV-001 §7.1 Phase G2, §8.6.
     *
     * <p>Status resolution (most specific wins):
     * <ol>
     *   <li>Failure row with status {@code ABANDONED} → {@code ABANDONED}</li>
     *   <li>Failure row with status {@code PENDING} → {@code PENDING}</li>
     *   <li>Pairing row exists → {@code OK}</li>
     *   <li>No pairing and no failure → {@code UNPAIRED}</li>
     * </ol>
     */
    public List<ClientSyncStatusDto> statusFor(String clientUuid) {
        Objects.requireNonNull(clientUuid, "clientUuid must not be null");
        List<ClientSyncStatusDto> rows = new ArrayList<>();
        for (String companyUuid : agreementDefaults.listConfiguredCompanies()) {
            Optional<ClientEconomicsCustomer> pairing = repo.findByClientAndCompany(clientUuid, companyUuid);
            Optional<ClientEconomicsSyncFailure> failure = failures.findByClientAndCompany(clientUuid, companyUuid);

            String status;
            if (failure.isPresent() && "ABANDONED".equals(failure.get().getStatus())) {
                status = ClientSyncStatusDto.STATUS_ABANDONED;
            } else if (failure.isPresent()) {
                status = ClientSyncStatusDto.STATUS_PENDING;
            } else if (pairing.isPresent()) {
                status = ClientSyncStatusDto.STATUS_OK;
            } else {
                status = ClientSyncStatusDto.STATUS_UNPAIRED;
            }

            String companyName = lookupCompanyName(companyUuid);
            int attemptCount = failure.map(ClientEconomicsSyncFailure::getAttemptCount).orElse(0);
            String lastError = failure.map(ClientEconomicsSyncFailure::getLastError).orElse(null);
            LocalDateTime nextRetryAt = failure
                    .filter(f -> ClientSyncStatusDto.STATUS_PENDING.equals(status))
                    .map(ClientEconomicsSyncFailure::getNextRetryAt).orElse(null);
            LocalDateTime lastAttemptedAt = failure
                    .map(ClientEconomicsSyncFailure::getLastAttemptedAt).orElse(null);

            rows.add(new ClientSyncStatusDto(
                    clientUuid, companyUuid, companyName, status,
                    attemptCount, lastError, nextRetryAt, lastAttemptedAt));
        }
        return rows;
    }

    /** Looks up the company display name; tolerates missing rows. */
    private String lookupCompanyName(String companyUuid) {
        try {
            Company c = Company.findById(companyUuid);
            return c == null ? companyUuid : c.getName();
        } catch (RuntimeException e) {
            LOG.debugf("Could not resolve company name for %s: %s", companyUuid, e.getMessage());
            return companyUuid;
        }
    }

    // --------------------------------------------------- HTTP helpers

    private void post(EconomicsCustomerApiClient api,
                      Client client, String companyUuid,
                      int groupNumber, int paymentTerm, int vatZone) {
        EconomicsCustomerDto body = mapper.toFullUpsertBody(client, groupNumber, paymentTerm, vatZone);
        EconomicsCustomerIndex idx = indexCache.getIndex(companyUuid);
        body.setCustomerNumber(deriveCustomerNumber(client, idx));
        EconomicsCustomerDto created = api.createCustomer(body);
        int customerNumber = Objects.requireNonNull(created.getCustomerNumber(),
                "e-conomic returned no customerNumber for client " + client.getUuid());
        upsertMapping(client.getUuid(), companyUuid, customerNumber,
                created.getObjectVersion(), PairingSource.CREATED);
        // Newly-created customer must appear in the index before the next sync
        // runs (prevents "next-available-number" from reusing this one).
        indexCache.invalidate(companyUuid);
    }

    private void putWithConcurrency(EconomicsCustomerApiClient api,
                                    Client client, String companyUuid,
                                    ClientEconomicsCustomer existing,
                                    int groupNumber, int paymentTerm, int vatZone) {
        int attempts = 0;
        while (true) {
            EconomicsCustomerDto remote = api.getCustomer(existing.getCustomerNumber());
            EconomicsCustomerDto body = mapper.toFullUpsertBody(client, groupNumber, paymentTerm, vatZone);
            body.setCustomerNumber(existing.getCustomerNumber());
            body.setObjectVersion(remote.getObjectVersion());
            try {
                api.updateCustomer(existing.getCustomerNumber(), body);
                // PUT returns empty body — re-GET to pick up the fresh objectVersion.
                EconomicsCustomerDto after = api.getCustomer(existing.getCustomerNumber());
                upsertMapping(client.getUuid(), companyUuid, existing.getCustomerNumber(),
                        after.getObjectVersion(), existing.getPairingSource());
                return;
            } catch (WebApplicationException e) {
                int status = e.getResponse() == null ? 0 : e.getResponse().getStatus();
                if (status == Response.Status.CONFLICT.getStatusCode() && attempts < MAX_CONFLICT_RETRIES) {
                    attempts++;
                    LOG.infof("PUT conflict for customer %d; retrying with fresh objectVersion (attempt %d/%d)",
                            existing.getCustomerNumber(), attempts, MAX_CONFLICT_RETRIES);
                    continue;
                }
                throw e;
            }
        }
    }

    /**
     * Derives the e-conomic {@code customerNumber} for a client.
     *
     * <ol>
     *   <li>Prefer CVR parsed as signed int — but only if the number is not
     *       already taken by a DIFFERENT customer in the agreement's index
     *       (guards against CVR collisions when two Trustworks clients share
     *       a CVR, e.g. subsidiaries under the same PARTNER).</li>
     *   <li>Otherwise hash the UUID into [1, 999_999_999]; if that also
     *       collides, walk forward until a free slot is found.</li>
     * </ol>
     *
     * SPEC-INV-001 §6.3.
     */
    static int deriveCustomerNumber(Client client, EconomicsCustomerIndex index) {
        if (client.getCvr() != null && !client.getCvr().isBlank()) {
            try {
                int parsed = Integer.parseInt(client.getCvr().trim());
                if (index == null || index.getByCustomerNumber(parsed).isEmpty()) {
                    return parsed;
                }
            } catch (NumberFormatException ignore) {
                // fall through
            }
        }
        String uuid = client.getUuid();
        if (uuid == null) {
            throw new IllegalArgumentException("Cannot derive customerNumber: client has no UUID");
        }
        String hex = uuid.replace("-", "");
        long hash = Long.parseUnsignedLong(hex.substring(0, Math.min(hex.length(), 8)), 16);
        int candidate = (int) ((hash % 999_999_999L) + 1);
        if (index == null) return candidate;
        // Linear probing on collision — at 999M slots, collisions are rare but
        // not impossible, so walk forward until a free number is found.
        int attempts = 0;
        while (index.getByCustomerNumber(candidate).isPresent() && attempts < 1000) {
            candidate = (candidate % 999_999_999) + 1;
            attempts++;
        }
        return candidate;
    }

    // --------------------------------------------------- pairing / failure rows

    private void upsertMapping(String clientUuid, String companyUuid,
                               int customerNumber, String objectVersion, PairingSource source) {
        ClientEconomicsCustomer row = repo.findByClientAndCompany(clientUuid, companyUuid)
                .orElseGet(() -> {
                    ClientEconomicsCustomer r = new ClientEconomicsCustomer();
                    r.setUuid(UUID.randomUUID().toString());
                    r.setClientUuid(clientUuid);
                    r.setCompanyUuid(companyUuid);
                    return r;
                });
        row.setCustomerNumber(customerNumber);
        row.setObjectVersion(objectVersion);
        row.setPairingSource(source);
        row.setSyncedAt(LocalDateTime.now());
        repo.persist(row);
    }

    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }
}
