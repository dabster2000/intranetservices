package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Single source of truth for the {@code recruitment.dossier.enabled} feature
 * flag. Read from the {@code app_settings} table on every call (settings
 * tables are tiny; caching is unnecessary and would only complicate the
 * invalidation story when an admin toggles the flag).
 * <p>
 * The flag defaults to {@code false} when the row is missing or the value is
 * unparseable — recruitment is opt-in.
 */
@ApplicationScoped
public class RecruitmentFeatureFlag {

    static final String SETTING_KEY = "recruitment.dossier.enabled";

    @Inject
    AppSettingService appSettingService;

    /**
     * @return true iff the {@code recruitment.dossier.enabled} setting is
     *         present and parses to {@code true} via
     *         {@link Boolean#parseBoolean(String)}; false otherwise
     *         (missing row, null value, or any non-"true" value).
     */
    public boolean isEnabled() {
        Optional<AppSetting> setting = appSettingService.findByKey(SETTING_KEY);
        return setting
                .map(AppSetting::getSettingValue)
                .map(Boolean::parseBoolean)
                .orElse(Boolean.FALSE);
    }
}
