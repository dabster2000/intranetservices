package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

/**
 * Resolves the per-practice specialization catalog (spec §4.1: the catalog
 * "lives in settings, keyed by practice uuid" — mirroring the Airtable
 * per-practice <em>faglighed</em> selects without hardcoding them).
 * <p>
 * Storage: one {@code app_settings} row per practice, key
 * {@code recruitment.specializations.<practice_uuid>}, value a JSON string
 * array. V435 seeds starter catalogs for the active practices; admins edit
 * the JSON on the /settings page. A practice with no row (or an empty /
 * unparseable value) has an empty catalog — the UI hides the picker.
 * <p>
 * Read per call like the {@link RecruitmentFeatureFlag} idiom — the
 * settings table is tiny, and no cache means no invalidation story.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentSpecializationCatalog {

    static final String KEY_PREFIX = "recruitment.specializations.";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    @Inject
    AppSettingService appSettingService;

    /**
     * @return the specialization options for the practice, or an empty list
     *         when the practice has no catalog (missing row, blank or
     *         malformed JSON — malformed logs a warning and degrades to
     *         empty rather than failing the caller)
     */
    public List<String> forPractice(String practiceUuid) {
        if (practiceUuid == null || practiceUuid.isBlank()) {
            return List.of();
        }
        return appSettingService.findByKey(KEY_PREFIX + practiceUuid)
                .map(AppSetting::getSettingValue)
                .map(json -> parse(json, practiceUuid))
                .orElse(List.of());
    }

    private static List<String> parse(String json, String practiceUuid) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, TYPE);
        } catch (Exception e) {
            log.warnf("Malformed specialization catalog for practice %s — treating as empty: %s",
                    practiceUuid, e.getMessage());
            return List.of();
        }
    }
}
