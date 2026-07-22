package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Single source of truth for the recruitment module's core feature flags.
 * Read from the {@code app_settings} table on every call (settings tables
 * are tiny; caching is unnecessary and would only complicate the
 * invalidation story when an admin toggles a flag).
 * <p>
 * Every flag defaults to {@code false} when the row is missing or the value
 * is unparseable — recruitment features are opt-in.
 * <ul>
 *   <li>{@code recruitment.dossier.enabled} — the pre-ATS dossier feature
 *       ({@link #isEnabled()}).</li>
 *   <li>{@code recruitment.pipeline.enabled} — ATS expansion core flag 1
 *       (spec §11), gating the P2–P10 surfaces starting with the positions
 *       page ({@link #isPipelineEnabled()}). Seeded {@code false} by V433.</li>
 * </ul>
 */
@ApplicationScoped
public class RecruitmentFeatureFlag {

    static final String SETTING_KEY = "recruitment.dossier.enabled";
    static final String PIPELINE_SETTING_KEY = "recruitment.pipeline.enabled";

    @Inject
    AppSettingService appSettingService;

    /**
     * @return true iff the {@code recruitment.dossier.enabled} setting is
     *         present and parses to {@code true} via
     *         {@link Boolean#parseBoolean(String)}; false otherwise
     *         (missing row, null value, or any non-"true" value).
     */
    public boolean isEnabled() {
        return readFlag(SETTING_KEY);
    }

    /**
     * @return true iff the {@code recruitment.pipeline.enabled} setting is
     *         present and parses to {@code true}; false otherwise. Same
     *         missing-means-off semantics as {@link #isEnabled()}.
     */
    public boolean isPipelineEnabled() {
        return readFlag(PIPELINE_SETTING_KEY);
    }

    private boolean readFlag(String key) {
        Optional<AppSetting> setting = appSettingService.findByKey(key);
        return setting
                .map(AppSetting::getSettingValue)
                .map(Boolean::parseBoolean)
                .orElse(Boolean.FALSE);
    }
}
