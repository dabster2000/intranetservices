package dk.trustworks.intranet.aggregates.invoice.economics.book;

import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches the result of {@code GET /self} from the e-conomic REST API per company UUID
 * with a 24-hour TTL. This prevents every EAN booking from re-fetching agreement
 * capabilities.
 *
 * SPEC-INV-001 section 4.2 requirement #5.
 */
@ApplicationScoped
public class EconomicsAgreementCapabilityService {

    private static final Logger LOG = Logger.getLogger(EconomicsAgreementCapabilityService.class);
    private static final Duration TTL = Duration.ofHours(24);

    private final EconomicsBookingApiClient bookApi;
    private final EconomicsAgreementResolver agreements;

    private record Entry(EconomicsAgreementSelf self, Instant loadedAt) {
        boolean expired() {
            return Duration.between(loadedAt, Instant.now()).compareTo(TTL) > 0;
        }
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    @Inject
    public EconomicsAgreementCapabilityService(
            @RestClient EconomicsBookingApiClient bookApi,
            EconomicsAgreementResolver agreements) {
        this.bookApi = bookApi;
        this.agreements = agreements;
    }

    /**
     * Returns whether the e-conomic agreement for the given company supports
     * sending electronic invoices (Nemhandel / EAN).
     *
     * @param companyUuid the company UUID to check
     * @return true if the agreement can send electronic invoices, false otherwise
     */
    public boolean canSendElectronicInvoice(String companyUuid) {
        Boolean cap = getOrLoad(companyUuid).self().getCanSendElectronicInvoice();
        return cap != null && cap;
    }

    /**
     * Returns the full {@link EconomicsAgreementSelf} response for the given company,
     * using the cached value if available and not expired.
     *
     * @param companyUuid the company UUID to look up
     * @return the cached or freshly fetched agreement self-description
     */
    public EconomicsAgreementSelf selfOf(String companyUuid) {
        return getOrLoad(companyUuid).self();
    }

    private Entry getOrLoad(String companyUuid) {
        Entry e = cache.get(companyUuid);
        return (e == null || e.expired()) ? load(companyUuid) : e;
    }

    private synchronized Entry load(String companyUuid) {
        // Double-check after acquiring the lock to avoid redundant API calls.
        Entry existing = cache.get(companyUuid);
        if (existing != null && !existing.expired()) {
            return existing;
        }

        var tokens = agreements.tokens(companyUuid);
        LOG.debugf("Fetching GET /self for company %s", companyUuid);
        EconomicsAgreementSelf self = bookApi.getSelf(tokens.appSecret(), tokens.agreementGrant());
        Entry entry = new Entry(self, Instant.now());
        cache.put(companyUuid, entry);
        return entry;
    }
}
