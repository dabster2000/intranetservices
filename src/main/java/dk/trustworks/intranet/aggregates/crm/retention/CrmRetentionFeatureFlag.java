package dk.trustworks.intranet.aggregates.crm.retention;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The arming switch for the 24-month CRM retention purge (spec §7) — the
 * {@code EmployeeDocumentsFeatureFlag} shape: read from {@code app_settings} on every call
 * (tiny table, no caching), missing or unparseable ⇒ {@code false}.
 *
 * <p><b>This is the second of two switches and it is the one that matters.</b> The first,
 * {@code dk.trustworks.crm.retention.purge.enabled}, stops {@code CrmRetentionPurgeJob} from
 * starting at all and defaults to {@code true} — it is the "stop it right now without a
 * deploy" lever. This one decides whether a run that <em>has</em> started deletes anything,
 * and V608 seeds it {@code 'false'}. <b>Flipping this row to {@code true} is the moment
 * automatic deletion starts</b>, in the exact words the employee-documents and recruitment
 * GDPR job descriptors use about their own arming rows. While it is off the nightly run is a
 * cheap no-op that files a {@code STOPPED} row saying so, which is why shipping the purge
 * dark is safe and why it ships that way: the alternative is a job that erases two years of
 * accumulated third-party data on the afternoon it deploys.
 *
 * <p>The 24-month window itself is deliberately NOT here and NOT a setting — see
 * {@link CrmRetentionParameters}.
 *
 * <p>The dry run ignores this flag on purpose. A preview is precisely what somebody needs to
 * read <em>before</em> arming, and it writes nothing but its own run row.
 */
@ApplicationScoped
public class CrmRetentionFeatureFlag {

    /** Seeded {@code 'false'} by V608. Missing or garbage reads as off. */
    static final String PURGE_ENABLED_KEY = "crm.retention.purge.enabled";

    @Inject
    AppSettingService appSettingService;

    /** Whether a purge run is allowed to delete or redact anything at all. */
    public boolean isPurgeArmed() {
        return appSettingService.findByKey(PURGE_ENABLED_KEY)
                .map(AppSetting::getSettingValue)
                .map(String::trim)
                .map(Boolean::parseBoolean)
                .orElse(false);
    }
}
