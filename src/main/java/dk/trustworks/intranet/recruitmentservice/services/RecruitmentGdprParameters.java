package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The P19 GDPR sweep cadence parameters, read from {@code app_settings}
 * on every call (the {@link RecruitmentSlaThresholds} idiom — no cache,
 * an admin edit takes effect on the next sweep without a redeploy).
 * Seeded by V448; a missing or unparseable row falls back to the plan's
 * defaults, never to "off" — the engine's on/off switch is
 * {@code recruitment.gdpr.enabled}, not these.
 * <p>
 * Deliberately NOT here: the 6-month retention period
 * ({@code RecruitmentApplicationService.RETENTION_MONTHS}) and the
 * 12-month consent validity ({@link RecruitmentConsentService#CONSENT_MONTHS}).
 * Those are the DPO-signed-off policy constants (spec §5.5) — changing
 * them is a reviewed code change, not an admin toggle.
 */
@ApplicationScoped
public class RecruitmentGdprParameters {

    static final String RENEWAL_FIRST_DAYS_KEY = "recruitment.gdpr.renewal-first-days";
    static final String RENEWAL_SECOND_DAYS_KEY = "recruitment.gdpr.renewal-second-days";
    static final String ART14_WARNING_DAYS_KEY = "recruitment.gdpr.art14-warning-days";

    static final int DEFAULT_RENEWAL_FIRST_DAYS = 30;
    static final int DEFAULT_RENEWAL_SECOND_DAYS = 7;
    static final int DEFAULT_ART14_WARNING_DAYS = 7;

    @Inject
    AppSettingService appSettingService;

    /** Days before the retention deadline the FIRST consent-renewal email goes out. */
    public int renewalFirstDays() {
        return readPositiveInt(RENEWAL_FIRST_DAYS_KEY, DEFAULT_RENEWAL_FIRST_DAYS);
    }

    /** Days before the retention deadline the SECOND (final) renewal email goes out. */
    public int renewalSecondDays() {
        return readPositiveInt(RENEWAL_SECOND_DAYS_KEY, DEFAULT_RENEWAL_SECOND_DAYS);
    }

    /** Days before the Art. 14 deadline a candidate enters the DPO queue. */
    public int art14WarningDays() {
        return readPositiveInt(ART14_WARNING_DAYS_KEY, DEFAULT_ART14_WARNING_DAYS);
    }

    private int readPositiveInt(String key, int defaultValue) {
        String value = appSettingService.findByKey(key)
                .map(AppSetting::getSettingValue)
                .orElse(null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
