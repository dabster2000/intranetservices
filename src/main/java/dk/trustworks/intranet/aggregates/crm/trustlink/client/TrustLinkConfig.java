package dk.trustworks.intranet.aggregates.crm.trustlink.client;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Everything about the TrustLink integration that an environment is allowed to change
 * without a deploy.
 *
 * <p>Two of these knobs are load-bearing rather than cosmetic.
 *
 * <p>{@code enabled} defaults to FALSE, like the calendar sync. The feature ships dark and
 * is turned on per environment, so merging it cannot start writing third-party personal
 * data into a database on its own.
 *
 * <p>{@code api-key} is an {@link Optional} because TrustLink requires no authentication of
 * any kind today. That is a fact about the upstream service, not a decision — so the header
 * is wired up now and simply not sent while the value is absent, and the day a key appears
 * it is an environment variable rather than a code change. The key is sent as
 * {@code X-Api-Key} and must never be logged, persisted or echoed in an error.
 *
 * <p>Methods, not fields, are read by collaborators: this is a normal-scoped bean, so a
 * call goes through the Arc client proxy to the contextual instance — the same reason
 * {@code ClientNewsConfig} exposes accessors.
 */
@ApplicationScoped
public class TrustLinkConfig {

    static final String DEFAULT_BASE_URL = "https://trustlink-blazor.azurewebsites.net";

    @ConfigProperty(name = "dk.trustworks.crm.trustlink.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "dk.trustworks.crm.trustlink.base-url", defaultValue = DEFAULT_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = "dk.trustworks.crm.trustlink.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "dk.trustworks.crm.trustlink.page-size", defaultValue = "500")
    int pageSize;

    @ConfigProperty(name = "dk.trustworks.crm.trustlink.company-batch-size", defaultValue = "100")
    int companyBatchSize;

    /** Feature flag. Callers decide what "off" means; the HTTP client itself does not read it. */
    public boolean enabled() {
        return enabled;
    }

    /** Without a trailing slash, so paths can be appended verbatim. */
    public String baseUrl() {
        return baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
    }

    /** Present only when an environment has been given a key; absent means "send no header". */
    public Optional<String> apiKey() {
        return apiKey == null ? Optional.empty() : apiKey.filter(key -> !key.isBlank());
    }

    /**
     * Page size for the connection search. Clamped to 1000, which is the largest value the
     * API has been observed to honour — and it must honour it exactly, because
     * {@link TrustLinkClient} treats a page size it did not ask for as a corrupt response.
     */
    public int pageSize() {
        return Math.max(1, Math.min(pageSize, 1000));
    }

    /**
     * How many company names go into one search request. The union of 294 names in a single
     * POST worked, so 100 is comfortable; it is a knob mainly so a slow day upstream can be
     * met with smaller asks rather than a redeploy.
     */
    public int companyBatchSize() {
        return Math.max(1, Math.min(companyBatchSize, 300));
    }
}
