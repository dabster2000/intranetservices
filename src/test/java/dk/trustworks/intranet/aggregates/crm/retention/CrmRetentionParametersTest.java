package dk.trustworks.intranet.aggregates.crm.retention;

import dk.trustworks.intranet.aggregates.crm.retention.services.CrmRetentionPurgeService;
import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * The blast-radius cap, and the boundary it must not be allowed to cross.
 *
 * <p>Ten accounts a night is the number that makes a first armed run recoverable: slow enough
 * that a mistake is noticed while it is still ten accounts, fast enough that a real backlog
 * drains in weeks. A garbage value must therefore fall back to ten rather than to "no limit",
 * and a zero or a negative must fall back too — read literally they mean "sweep nothing",
 * which is what the arming flag is for, and a typo in a number field must not become a second
 * off switch nobody remembers setting.
 *
 * <p>The middle block is a regression suite for a cap that was dead config. {@code
 * application.yml} declared {@code dk.trustworks.crm.retention.purge.nightly-account-cap}
 * (i.e. {@code CRM_RETENTION_PURGE_CAP}) with a comment about irreversible work and no undo —
 * and nothing read it. The only consumer was the {@code app_settings} row, which V608 did not
 * seed either, so an operator could set the environment variable, redeploy, and watch the purge
 * go on sweeping the compiled ten a night with no signal that their number had gone nowhere.
 * Both stores are wired now, and these tests pin the precedence between them.
 *
 * <p>The last test is the one that is really about policy: the 24-month window is NOT read
 * from {@code app_settings} at all.
 */
@ExtendWith(MockitoExtension.class)
class CrmRetentionParametersTest {

    @Mock
    private AppSettingService appSettingService;

    @InjectMocks
    private CrmRetentionParameters parameters;

    @Test
    @DisplayName("no row falls back to ten accounts a night")
    void missingRowFallsBackToTheDefault() {
        when(appSettingService.findByKey(CrmRetentionParameters.NIGHTLY_ACCOUNT_CAP_KEY))
                .thenReturn(Optional.empty());
        assertEquals(CrmRetentionParameters.DEFAULT_NIGHTLY_ACCOUNT_CAP, parameters.nightlyAccountCap());
    }

    @Test
    @DisplayName("a word falls back rather than throwing on a nightly job")
    void garbageFallsBackToTheDefault() {
        when(appSettingService.findByKey(CrmRetentionParameters.NIGHTLY_ACCOUNT_CAP_KEY))
                .thenReturn(Optional.of(setting("all of them")));
        assertEquals(10, parameters.nightlyAccountCap());
    }

    @Test
    @DisplayName("zero and negatives fall back — they are not a second off switch")
    void nonPositiveValuesFallBackToTheDefault() {
        when(appSettingService.findByKey(CrmRetentionParameters.NIGHTLY_ACCOUNT_CAP_KEY))
                .thenReturn(Optional.of(setting("0")));
        assertEquals(10, parameters.nightlyAccountCap());

        when(appSettingService.findByKey(CrmRetentionParameters.NIGHTLY_ACCOUNT_CAP_KEY))
                .thenReturn(Optional.of(setting("-5")));
        assertEquals(10, parameters.nightlyAccountCap());
    }

    @Test
    @DisplayName("a tuned value wins, padding and all")
    void tunedValueWins() {
        when(appSettingService.findByKey(CrmRetentionParameters.NIGHTLY_ACCOUNT_CAP_KEY))
                .thenReturn(Optional.of(setting(" 25 ")));
        assertEquals(25, parameters.nightlyAccountCap());
    }

    // ------------------------------------------------------------------------
    // The yml/env half: the cap that used to be dead config
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("CRM_RETENTION_PURGE_CAP reaches nightlyAccountCap() when no app_settings row exists")
    void theConfiguredCapIsUsedWhenTheRowIsAbsent() {
        // This is the whole defect in one assertion. The environment variable resolves into
        // dk.trustworks.crm.retention.purge.nightly-account-cap, Quarkus binds that key into
        // configuredNightlyAccountCap, and nightlyAccountCap() must hand it back rather than
        // falling through to the compiled ten as if the operator had never typed anything.
        parameters.configuredNightlyAccountCap = 3;
        when(appSettingService.findByKey(CrmRetentionParameters.NIGHTLY_ACCOUNT_CAP_KEY))
                .thenReturn(Optional.empty());

        assertEquals(3, parameters.nightlyAccountCap());
    }

    @Test
    @DisplayName("the app_settings row wins over the yml/env value — one store is authoritative")
    void theRowWinsOverTheConfiguredCap() {
        // Both stores set, deliberately disagreeing. The row wins because it is the one an
        // admin can change at 03:00 without a redeploy, which is the whole reason a
        // blast-radius cap lives in app_settings in this codebase.
        parameters.configuredNightlyAccountCap = 3;
        when(appSettingService.findByKey(CrmRetentionParameters.NIGHTLY_ACCOUNT_CAP_KEY))
                .thenReturn(Optional.of(setting("7")));

        assertEquals(7, parameters.nightlyAccountCap());
    }

    @Test
    @DisplayName("a garbage row falls back to the yml/env value, not straight to the compiled ten")
    void garbageRowFallsBackToTheConfiguredCap() {
        parameters.configuredNightlyAccountCap = 4;
        when(appSettingService.findByKey(CrmRetentionParameters.NIGHTLY_ACCOUNT_CAP_KEY))
                .thenReturn(Optional.of(setting("all of them")));

        assertEquals(4, parameters.nightlyAccountCap(),
                "an operator who set the env var has said what they want; a typo in the settings "
                        + "screen must not discard it");
    }

    @Test
    @DisplayName("a blank row falls back to the yml/env value too")
    void blankRowFallsBackToTheConfiguredCap() {
        parameters.configuredNightlyAccountCap = 4;
        when(appSettingService.findByKey(CrmRetentionParameters.NIGHTLY_ACCOUNT_CAP_KEY))
                .thenReturn(Optional.of(setting("   ")));

        assertEquals(4, parameters.nightlyAccountCap());
    }

    @Test
    @DisplayName("a zero or negative yml/env value is not a second off switch either")
    void aNonPositiveConfiguredCapFallsBackToTen() {
        // Same reading as for the app_settings value: zero means "sweep nothing", which is the
        // arming flag's job. It also covers the shape this very test class is in — an
        // @InjectMocks bean whose int field Quarkus never wrote reads 0.
        parameters.configuredNightlyAccountCap = 0;
        assertEquals(CrmRetentionParameters.DEFAULT_NIGHTLY_ACCOUNT_CAP,
                parameters.configuredNightlyAccountCap());

        parameters.configuredNightlyAccountCap = -5;
        assertEquals(CrmRetentionParameters.DEFAULT_NIGHTLY_ACCOUNT_CAP,
                parameters.configuredNightlyAccountCap());
    }

    @Test
    @DisplayName("the two spellings of the cap stay in step — the yml key is the setting key, prefixed")
    void thePropertyAndTheSettingKeyMatch() {
        // If these drift, application.yml documents a key nothing reads and V608 seeds a row
        // nothing looks up — which is exactly the state this fix came out of.
        assertEquals("dk.trustworks." + CrmRetentionParameters.NIGHTLY_ACCOUNT_CAP_KEY,
                CrmRetentionParameters.NIGHTLY_ACCOUNT_CAP_PROPERTY);
    }

    @Test
    @DisplayName("the 24-month window is compiled, not a setting — nothing here can move it")
    void theRetentionWindowIsNotAnAdminToggle() {
        assertEquals(24, CrmRetentionPurgeService.RETENTION_MONTHS,
                "eight migration headers quote this number; changing it is a reviewed code change");
    }

    private static AppSetting setting(String value) {
        AppSetting appSetting = new AppSetting();
        appSetting.setSettingValue(value);
        return appSetting;
    }
}
