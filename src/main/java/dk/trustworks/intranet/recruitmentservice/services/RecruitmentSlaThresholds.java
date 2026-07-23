package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The three P17 SLA thresholds, read from {@code app_settings} on every
 * call (the {@link RecruitmentFeatureFlag} idiom — the table is tiny, and
 * no cache means an admin edit takes effect on the next sweep without a
 * redeploy). Seeded by V447; a missing or unparseable row falls back to
 * the plan's defaults (24 h / 7 d / 48 h), never to "off" — the sweep's
 * on/off switch is {@code recruitment.interviews.enabled}, not these.
 */
@ApplicationScoped
public class RecruitmentSlaThresholds {

    static final String SCORECARD_OVERDUE_HOURS_KEY = "recruitment.sla.scorecard-overdue-hours";
    static final String CANDIDATE_IDLE_DAYS_KEY = "recruitment.sla.candidate-idle-days";
    static final String DEBRIEF_STALLED_HOURS_KEY = "recruitment.sla.debrief-stalled-hours";

    static final int DEFAULT_SCORECARD_OVERDUE_HOURS = 24;
    static final int DEFAULT_CANDIDATE_IDLE_DAYS = 7;
    static final int DEFAULT_DEBRIEF_STALLED_HOURS = 48;

    @Inject
    AppSettingService appSettingService;

    /** Hours after a round interview's time before the missing-scorecard DM. */
    public int scorecardOverdueHours() {
        return readPositiveInt(SCORECARD_OVERDUE_HOURS_KEY, DEFAULT_SCORECARD_OVERDUE_HOURS);
    }

    /** Days an open application may sit in one stage before the owner ping. */
    public int candidateIdleDays() {
        return readPositiveInt(CANDIDATE_IDLE_DAYS_KEY, DEFAULT_CANDIDATE_IDLE_DAYS);
    }

    /** Hours a debrief-ready round may sit unactioned before the owner ping. */
    public int debriefStalledHours() {
        return readPositiveInt(DEBRIEF_STALLED_HOURS_KEY, DEFAULT_DEBRIEF_STALLED_HOURS);
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
