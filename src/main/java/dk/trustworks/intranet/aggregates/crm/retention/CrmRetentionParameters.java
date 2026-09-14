package dk.trustworks.intranet.aggregates.crm.retention;

import dk.trustworks.intranet.aggregates.crm.retention.services.CrmRetentionPurgeService;
import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The one tunable number the CRM retention purge has (spec §7), read from
 * {@code app_settings} per call — the {@code EmployeeDocumentsParameters} idiom: no cache, so
 * an edit takes effect on the next run without a redeploy, and a missing or garbage value
 * falls back to the compiled default rather than to "unlimited".
 *
 * <p><b>Deliberately NOT here: the 24-month retention window.</b> It is
 * {@link CrmRetentionPurgeService#RETENTION_MONTHS}, a compiled constant, exactly as
 * {@code RecruitmentGdprParameters} keeps the 6-month recruitment retention and the 12-month
 * consent validity out of its own settings: those are the signed-off policy, and changing
 * one is a reviewed code change, not a toggle somebody can move on a Friday afternoon. Only
 * cadence and blast radius belong in {@code app_settings}. A 24 that could be edited to a 1
 * from a settings screen would make every migration header that cites "24 months" a
 * statement about nothing.
 *
 * <p>The cap is the other half of shipping this safely. The first armed run meets years of
 * accumulated backlog, and erasure is irreversible; ten accounts a night is slow enough that
 * a mistake is noticed while it is still ten accounts, and fast enough that a real backlog
 * drains in weeks. Oldest-first ordering lives with the eligibility rule, not here.
 *
 * <h2>Two stores for one number, and which one wins</h2>
 * The cap is declared in <b>both</b> {@code application.yml}
 * ({@code dk.trustworks.crm.retention.purge.nightly-account-cap}, i.e. the
 * {@code CRM_RETENTION_PURGE_CAP} environment variable) and {@code app_settings}
 * ({@link #NIGHTLY_ACCOUNT_CAP_KEY}, seeded {@code '10'} by V608). That is on purpose, and the
 * order is: <b>the {@code app_settings} row wins while it holds a usable value; the yml/env
 * value is what a missing, blank or garbage row falls back to; ten is what a missing yml key
 * falls back to.</b>
 *
 * <p><b>This used to be a trap and is written down so it does not come back.</b> The yml key
 * shipped with a comment about irreversible work and no undo — and nothing read it. The only
 * consumer was the {@code app_settings} row, which V608 did not seed either, so
 * {@code CRM_RETENTION_PURGE_CAP} was dead config: an operator could set it on the task
 * definition, redeploy, watch the purge sweep ten accounts a night regardless, and have no
 * signal at all that their number had gone nowhere. A blast-radius cap that silently ignores
 * the knob an operator reaches for is worse than no knob, because it reads as a control. Both
 * halves are now wired: {@link #configuredNightlyAccountCap} binds the yml key, V608 seeds the
 * row, and neither can go missing without the other still producing a number.
 */
@ApplicationScoped
public class CrmRetentionParameters {

    static final String NIGHTLY_ACCOUNT_CAP_KEY = "crm.retention.purge.nightly-account-cap";

    /**
     * The yml/env spelling of the same number — {@code CRM_RETENTION_PURGE_CAP} resolves into
     * this key. Note it is the {@link #NIGHTLY_ACCOUNT_CAP_KEY} setting key with a
     * {@code dk.trustworks.} prefix; keep the two spellings in step or the comment in
     * {@code application.yml} starts describing a key nobody reads.
     */
    static final String NIGHTLY_ACCOUNT_CAP_PROPERTY =
            "dk.trustworks.crm.retention.purge.nightly-account-cap";

    /** Ten, like every other blast-radius cap in this repo. */
    static final int DEFAULT_NIGHTLY_ACCOUNT_CAP = 10;

    @Inject
    AppSettingService appSettingService;

    /**
     * The yml/env half of the cap: the fallback an absent or unusable {@code app_settings} row
     * lands on, rather than jumping straight to the compiled ten.
     *
     * <p>Package-private and not {@code final}, the same shape as
     * {@code CrmRetentionPurgeJob.purgeEnabled}, because Quarkus injects it. Read it through
     * {@link #configuredNightlyAccountCap()} and never directly: a plain-JUnit test constructs
     * this bean with Mockito's {@code @InjectMocks}, which leaves an uninjected {@code int} at
     * {@code 0} — and zero means "sweep nothing", which is the arming flag's job, not this
     * field's.
     */
    @ConfigProperty(name = NIGHTLY_ACCOUNT_CAP_PROPERTY,
            defaultValue = "" + DEFAULT_NIGHTLY_ACCOUNT_CAP)
    int configuredNightlyAccountCap;

    /** How many accounts one run may sweep, oldest last-activity first. */
    public int nightlyAccountCap() {
        return readPositiveInt(NIGHTLY_ACCOUNT_CAP_KEY, configuredNightlyAccountCap());
    }

    /**
     * The configured cap, or the compiled ten when it is missing or non-positive.
     *
     * <p>Non-positive covers two cases that have to behave identically: an operator who set
     * {@code CRM_RETENTION_PURGE_CAP=0}, and a unit test whose {@code @InjectMocks} bean never
     * had the field written at all. Neither may be read as "sweep nothing" and neither may be
     * read as "sweep everything" — both land on ten.
     */
    int configuredNightlyAccountCap() {
        return configuredNightlyAccountCap > 0 ? configuredNightlyAccountCap : DEFAULT_NIGHTLY_ACCOUNT_CAP;
    }

    /**
     * A positive integer, or the caller's default.
     *
     * <p>Zero and negatives fall back rather than being honoured: read literally they would
     * mean "sweep no accounts", which is what the arming flag is for, and a typo in a number
     * field must not quietly become a second off switch nobody remembers setting.
     */
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
