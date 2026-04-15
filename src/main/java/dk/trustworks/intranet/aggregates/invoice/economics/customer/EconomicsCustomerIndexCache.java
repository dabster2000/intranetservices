package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.financeservice.model.IntegrationKey.IntegrationKeyValue;
import dk.trustworks.intranet.model.Company;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-agreement cache of {@link EconomicsCustomerIndex}. Warmed on startup
 * for every Trustworks company with e-conomic credentials configured, so
 * pairing UI requests never block on a full customer re-page.
 *
 * <p>TTL is 1 hour. Admin UI can force a reload of a single agreement via
 * {@link #invalidate(String)} / {@link #getIndex(String)}, or all agreements
 * via {@link #refreshAll()}.
 *
 * SPEC-INV-001 §3.3.1 (index pre-warming).
 */
@ApplicationScoped
public class EconomicsCustomerIndexCache {

    private static final Logger LOG = Logger.getLogger(EconomicsCustomerIndexCache.class);
    private static final Duration TTL = Duration.ofHours(1);
    private static final int PAGE_SIZE = 1000;
    /** Hard safety cap on page count: 100 pages × 1000 rows = 100k customers. */
    private static final int MAX_PAGES = 100;

    @Inject EconomicsCustomersApiClientFactory clientFactory;

    private final Map<String, Entry> byAgreement = new ConcurrentHashMap<>();

    /** Cache value: the index itself + the time it was loaded (for TTL checks). */
    public record Entry(EconomicsCustomerIndex index, Instant loadedAt) {
        boolean isExpired() {
            return Duration.between(loadedAt, Instant.now()).compareTo(TTL) > 0;
        }
    }

    /** Warm caches for every company with e-conomic tokens configured. */
    void onStartup(@Observes StartupEvent event) {
        List<String> agreements = listAgreementCompanyUuids();
        LOG.infof("Pre-warming e-conomic customer index for %d agreement(s)", agreements.size());
        for (String agreementNumber : agreements) {
            try {
                load(agreementNumber);
            } catch (Exception e) {
                LOG.warnf(e, "Could not pre-warm e-conomic customer index for agreement %s", agreementNumber);
            }
        }
    }

    /**
     * Returns the cached index for an agreement, loading or refreshing it on
     * cache miss / expiry.
     *
     * @param agreementNumber Trustworks company UUID (one agreement per company)
     * @return a non-null index
     */
    public EconomicsCustomerIndex getIndex(String agreementNumber) {
        Entry e = byAgreement.get(agreementNumber);
        if (e == null || e.isExpired()) return load(agreementNumber);
        return e.index();
    }

    /** Drops the cached entry for an agreement so the next {@link #getIndex} call reloads. */
    public void invalidate(String agreementNumber) {
        byAgreement.remove(agreementNumber);
    }

    /** Forces a reload of every known agreement's index. */
    public void refreshAll() {
        for (String agreementNumber : listAgreementCompanyUuids()) {
            try {
                load(agreementNumber);
            } catch (Exception e) {
                LOG.warnf(e, "Refresh failed for agreement %s", agreementNumber);
            }
        }
    }

    /** Time the cached entry was most recently loaded, or {@code null} if absent. */
    @Nullable
    public Instant loadedAt(String agreementNumber) {
        Entry e = byAgreement.get(agreementNumber);
        return e == null ? null : e.loadedAt();
    }

    /* -------------------------------------------------- internals */

    private synchronized EconomicsCustomerIndex load(String agreementNumber) {
        Entry existing = byAgreement.get(agreementNumber);
        if (existing != null && !existing.isExpired()) return existing.index();

        Company company = Company.findById(agreementNumber);
        if (company == null) {
            throw new IllegalArgumentException("Unknown company UUID: " + agreementNumber);
        }
        IntegrationKeyValue keys = IntegrationKey.getIntegrationKeyValue(company);
        EconomicsCustomerApiClient api = clientFactory.build(keys);

        List<EconomicsCustomerDto> all = new ArrayList<>();
        int skipPages = 0;
        while (skipPages < MAX_PAGES) {
            EconomicsCustomersPage page = api.listCustomers(PAGE_SIZE, skipPages);
            if (page == null || page.getItems() == null || page.getItems().isEmpty()) break;
            all.addAll(page.getItems());
            if (page.getItems().size() < PAGE_SIZE) break;
            skipPages++;
        }
        if (skipPages >= MAX_PAGES) {
            LOG.warnf("Aborting customer index load for agreement %s after %d pages (%d records)",
                    agreementNumber, MAX_PAGES, all.size());
        }

        EconomicsCustomerIndex idx = new EconomicsCustomerIndex(all);
        byAgreement.put(agreementNumber, new Entry(idx, Instant.now()));
        LOG.infof("Loaded e-conomic customer index for agreement %s: %d customers",
                agreementNumber, all.size());
        return idx;
    }

    /** Returns distinct company UUIDs that have at least one {@code integration_keys} row. */
    @SuppressWarnings("unchecked")
    private List<String> listAgreementCompanyUuids() {
        return IntegrationKey.<IntegrationKey>findAll().stream()
                .map(ik -> ik.getCompany() == null ? null : ik.getCompany().getUuid())
                .filter(uuid -> uuid != null && !uuid.isBlank())
                .distinct()
                .toList();
    }
}
