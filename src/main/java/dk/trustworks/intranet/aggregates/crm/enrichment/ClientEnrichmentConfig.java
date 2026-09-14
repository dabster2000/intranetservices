package dk.trustworks.intranet.aggregates.crm.enrichment;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * The knobs of the nightly client-enrichment jobs, from {@code dk.trustworks.crm.enrichment.*}
 * in {@code application.yml}.
 *
 * <p>Model ids and reasoning efforts are deployment decisions and live here; the runtime
 * on/off switch is the {@code app_settings} row read by {@link ClientEnrichmentFeatureFlag}.
 * The caps are per job and per night: the backlog on the day this shipped was 183 rows
 * without a CVR, 63 with a CVR but no registry data, 123 filed under OTHER and roughly 30
 * clients with no logo, and clearing all of that in one night would burn the Virkdata
 * quota and a few hundred image generations on a job nobody has watched run yet.
 */
@ApplicationScoped
public class ClientEnrichmentConfig {

    @ConfigProperty(name = "dk.trustworks.crm.enrichment.enabled", defaultValue = "true")
    boolean enabled;

    /**
     * The V-series staging kill-switch idiom. Staging's database is a nightly copy of
     * production's and its S3 bucket, Virkdata key and OpenAI key are the same as
     * production's, so the nightly cron there would redo production's work every night for
     * ever. The cron refuses to run where this is {@code staging}; the manual trigger does
     * not, so staging can still rehearse a run on demand.
     */
    @ConfigProperty(name = "dk.trustworks.environment.id", defaultValue = "production")
    String environmentId;

    @ConfigProperty(name = "dk.trustworks.crm.enrichment.cvr.nightly-cap", defaultValue = "20")
    int cvrNightlyCap;

    @ConfigProperty(name = "dk.trustworks.crm.enrichment.cvr.retry-after-days", defaultValue = "7")
    int cvrRetryAfterDays;

    @ConfigProperty(name = "dk.trustworks.crm.enrichment.cvr.finder-model", defaultValue = "gpt-5.6-terra")
    String cvrFinderModel;

    @ConfigProperty(name = "dk.trustworks.crm.enrichment.logo.nightly-cap", defaultValue = "10")
    int logoNightlyCap;

    @ConfigProperty(name = "dk.trustworks.crm.enrichment.logo.retry-after-days", defaultValue = "14")
    int logoRetryAfterDays;

    @ConfigProperty(name = "dk.trustworks.crm.enrichment.logo.finder-model", defaultValue = "gpt-5.6-terra")
    String logoFinderModel;

    @ConfigProperty(name = "dk.trustworks.crm.enrichment.logo.image-model", defaultValue = "gpt-image-1")
    String logoImageModel;

    @ConfigProperty(name = "dk.trustworks.crm.enrichment.logo.max-download-bytes", defaultValue = "5242880")
    long logoMaxDownloadBytes;

    @ConfigProperty(name = "dk.trustworks.crm.enrichment.sector.nightly-cap", defaultValue = "20")
    int sectorNightlyCap;

    @ConfigProperty(name = "dk.trustworks.crm.enrichment.sector.retry-after-days", defaultValue = "7")
    int sectorRetryAfterDays;

    @ConfigProperty(name = "dk.trustworks.crm.enrichment.sector.model", defaultValue = "gpt-5.6-terra")
    String sectorModel;

    /**
     * Optional so an EMPTY env value omits the reasoning node — the SRCFG00040 trap
     * documented on every other reasoning-effort key in application.yml.
     */
    @ConfigProperty(name = "dk.trustworks.crm.enrichment.reasoning-effort", defaultValue = "low")
    Optional<String> reasoningEffort;

    public boolean enabled() { return enabled; }

    public boolean nightlyAllowedHere() { return !"staging".equalsIgnoreCase(environmentId); }

    public String environmentId() { return environmentId; }

    public int cvrNightlyCap() { return clamp(cvrNightlyCap); }

    public int cvrRetryAfterDays() { return Math.max(1, cvrRetryAfterDays); }

    public String cvrFinderModel() { return cvrFinderModel; }

    public int logoNightlyCap() { return clamp(logoNightlyCap); }

    public int logoRetryAfterDays() { return Math.max(1, logoRetryAfterDays); }

    public String logoFinderModel() { return logoFinderModel; }

    public String logoImageModel() { return logoImageModel; }

    public long logoMaxDownloadBytes() { return Math.max(65_536L, logoMaxDownloadBytes); }

    public int sectorNightlyCap() { return clamp(sectorNightlyCap); }

    public int sectorRetryAfterDays() { return Math.max(1, sectorRetryAfterDays); }

    public String sectorModel() { return sectorModel; }

    /** Null when the configured value is blank, so the request omits the reasoning node. */
    public String reasoningEffort() {
        return reasoningEffort.filter(e -> !e.isBlank()).map(String::trim).orElse(null);
    }

    /** A cap is a cap: zero means "run nothing tonight", and nothing above 500 is sane. */
    static int clamp(int cap) {
        return Math.max(0, Math.min(cap, 500));
    }
}
