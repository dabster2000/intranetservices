package dk.trustworks.intranet.documentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Retention/upload policy numbers for the employee document store
 * (spec §6.11), read from {@code app_settings} per call (the
 * {@code RecruitmentGdprParameters} idiom — no cache, an admin edit takes
 * effect on the next run/request without a redeploy). Seeded by V452;
 * missing or garbage values fall back to the compiled defaults, never to
 * "off" — the on/off switch is {@code employee_documents.retention.enabled},
 * not these.
 */
@ApplicationScoped
public class EmployeeDocumentsParameters {

    static final String RETENTION_YEARS_KEY = "employee_documents.retention.years";
    static final String NIGHTLY_USER_CAP_KEY = "employee_documents.retention.nightly-user-cap";
    static final String UPLOAD_MAX_SIZE_MB_KEY = "employee_documents.upload.max-size-mb";

    static final int DEFAULT_RETENTION_YEARS = 5;
    static final int DEFAULT_NIGHTLY_USER_CAP = 10;
    static final int DEFAULT_UPLOAD_MAX_SIZE_MB = 25;

    /** Compiled floor — NOT admin-overridable (spec §6.10). */
    static final int MIN_RETENTION_YEARS = 1;

    /** Hard ceiling from D6 / the deploy's max-body-size headroom. */
    static final int HARD_MAX_UPLOAD_MB = 25;

    @Inject
    AppSettingService appSettingService;

    /** Years after end of employment before documents are hard-deleted (D4 default 5; floor 1). */
    public int retentionYears() {
        return Math.max(MIN_RETENTION_YEARS,
                readPositiveInt(RETENTION_YEARS_KEY, DEFAULT_RETENTION_YEARS));
    }

    /** Max ex-employees erased per nightly retention run (blast-radius cap). */
    public int nightlyUserCap() {
        return readPositiveInt(NIGHTLY_USER_CAP_KEY, DEFAULT_NIGHTLY_USER_CAP);
    }

    /**
     * Interactive upload cap in MB — admin-tunable DOWN from the hard
     * 25 MB ceiling (D6), never above it. Migration writes bypass this
     * (spec §6.3).
     */
    public int uploadMaxSizeMb() {
        return Math.min(HARD_MAX_UPLOAD_MB,
                readPositiveInt(UPLOAD_MAX_SIZE_MB_KEY, DEFAULT_UPLOAD_MAX_SIZE_MB));
    }

    public long uploadMaxSizeBytes() {
        return uploadMaxSizeMb() * 1024L * 1024L;
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
