package dk.trustworks.intranet.recruitmentservice.services;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Feature flag for Method B interview scheduling (candidate-option
 * scheduling, plan 2026-08-12 Phase 7.4): placeholder calendar holds,
 * Slack interviewer coordination, the candidate option page and
 * finalization through the Method A two-event machinery.
 * <p>
 * A CONFIG property, not an {@code app_settings} row like the
 * {@link RecruitmentSlackFeatureFlag} toggles, for two reasons: it
 * mirrors {@code dk.trustworks.recruitment.graph.calendar.enabled}
 * (the Method A sibling it is deliberately independent of — Method B
 * off leaves Method A untouched, and vice versa), and the staging
 * nightly refresh re-seeds {@code app_settings}, which would silently
 * flip an app-settings-backed flag off mid-soak.
 * <p>
 * Default OFF — every Method B phase merges and deploys dark behind
 * this flag. Off ⇒ Method B entry points are absent (REST answers 404,
 * the dialog hides the method selector, sweeps no-op). Per decision D8
 * the flag first turns on in STAGING when Phase 13 (image evidence) is
 * complete — buttons-only operation is a soak state, not a release —
 * and in production only after the spec §27 scenario matrix passes on
 * staging. Enable via {@code DK_TRUSTWORKS_RECRUITMENT_SCHEDULING_METHODB_ENABLED}.
 */
@ApplicationScoped
public class RecruitmentSchedulingFeatureFlag {

    @ConfigProperty(name = "dk.trustworks.recruitment.scheduling.methodb.enabled",
            defaultValue = "false")
    boolean methodBEnabled;

    public boolean isMethodBEnabled() {
        return methodBEnabled;
    }
}
