package dk.trustworks.intranet.documentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Runtime toggles for the S3-only employee document store (spec §6.11) —
 * the {@link dk.trustworks.intranet.recruitmentservice.services.RecruitmentAiFeatureFlag}
 * shape by design: read from {@code app_settings} on every call (tiny
 * table, no caching — a flip on the settings tab takes effect on the next
 * request/batchlet pass, no restart), missing or unparseable value ⇒
 * {@code false} (the feature ships dark and is armed from the
 * Settings → Employee Documents tab).
 *
 * <ul>
 *   <li>{@code ui.hr-tab} / {@code ui.self-service} — permanent UI
 *       kill-switches (BFF/frontend read them via the app-settings route;
 *       backend surfaces stay scope-guarded regardless).</li>
 *   <li>{@code retention} — arming switch for the nightly retention job
 *       (spec §6.10); enabling it is the moment automatic deletion of
 *       ex-employees' documents starts.</li>
 *   <li>{@code review.slack-notify} — HR Slack ping on employee
 *       self-uploads.</li>
 * </ul>
 * All seeded {@code 'false'}, category {@code 'employee_documents'}, by V452.
 */
@ApplicationScoped
public class EmployeeDocumentsFeatureFlag {

    static final String HR_TAB_KEY = "employee_documents.ui.hr-tab.enabled";
    static final String SELF_SERVICE_KEY = "employee_documents.ui.self-service.enabled";
    static final String RETENTION_KEY = "employee_documents.retention.enabled";
    static final String REVIEW_SLACK_NOTIFY_KEY = "employee_documents.review.slack-notify.enabled";
    /**
     * Key kept verbatim from the migration era: the row was seeded by V457
     * and is what the settings tab still flips.
     */
    static final String AI_CATEGORIZATION_KEY = "employee_documents.migration.ai.enabled";

    @Inject
    AppSettingService appSettingService;

    /** HR Documents tab in employee-management (ADMIN dark-preview while off). */
    public boolean isHrTabEnabled() {
        return readFlag(HR_TAB_KEY);
    }

    /** Profile self-view/self-upload switches from legacy to the new store (no admin bypass). */
    public boolean isSelfServiceEnabled() {
        return readFlag(SELF_SERVICE_KEY);
    }

    /** Arms the nightly retention job (spec §6.10). */
    public boolean isRetentionEnabled() {
        return readFlag(RETENTION_KEY);
    }

    /** Slack message to the HR channel when an employee self-uploads. */
    public boolean isReviewSlackNotifyEnabled() {
        return readFlag(REVIEW_SLACK_NOTIFY_KEY);
    }

    /**
     * AI assist for the employee-document categorizer (decision A5). OFF ⇒
     * the categorizer still works, purely deterministically (the §9.5 rule
     * table only). Seeded 'false' by V457.
     */
    public boolean isAiCategorizationEnabled() {
        return readFlag(AI_CATEGORIZATION_KEY);
    }

    private boolean readFlag(String key) {
        Optional<AppSetting> setting = appSettingService.findByKey(key);
        return setting
                .map(AppSetting::getSettingValue)
                .map(Boolean::parseBoolean)
                .orElse(Boolean.FALSE);
    }
}
