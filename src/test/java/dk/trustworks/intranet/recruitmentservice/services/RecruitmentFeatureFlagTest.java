package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link RecruitmentFeatureFlag}. The flag is opt-in and
 * defaults to {@code false} — these tests guard against accidental
 * enable-on-missing-row regressions.
 */
@ExtendWith(MockitoExtension.class)
class RecruitmentFeatureFlagTest {

    private static final String SETTING_KEY = "recruitment.dossier.enabled";

    @Mock
    private AppSettingService appSettingService;

    @InjectMocks
    private RecruitmentFeatureFlag featureFlag;

    @BeforeEach
    void resetMocks() {
        // Each test stubs explicitly — no shared default.
    }

    @Test
    void isEnabled_settingMissing_returnsFalse() {
        when(appSettingService.findByKey(SETTING_KEY)).thenReturn(Optional.empty());

        assertFalse(featureFlag.isEnabled());
    }

    @Test
    void isEnabled_settingValueIsTrueLowercase_returnsTrue() {
        when(appSettingService.findByKey(SETTING_KEY)).thenReturn(Optional.of(setting("true")));

        assertTrue(featureFlag.isEnabled());
    }

    @Test
    void isEnabled_settingValueIsTrueUppercase_returnsTrue() {
        // Boolean.parseBoolean is case-insensitive.
        when(appSettingService.findByKey(SETTING_KEY)).thenReturn(Optional.of(setting("TRUE")));

        assertTrue(featureFlag.isEnabled());
    }

    @Test
    void isEnabled_settingValueIsFalse_returnsFalse() {
        when(appSettingService.findByKey(SETTING_KEY)).thenReturn(Optional.of(setting("false")));

        assertFalse(featureFlag.isEnabled());
    }

    @Test
    void isEnabled_settingValueIsGarbage_returnsFalse() {
        // Boolean.parseBoolean returns false for any string that is not "true".
        when(appSettingService.findByKey(SETTING_KEY)).thenReturn(Optional.of(setting("yes-please")));

        assertFalse(featureFlag.isEnabled());
    }

    @Test
    void isEnabled_settingValueIsNull_returnsFalse() {
        // Optional.map returns Optional.empty when mapper returns null
        // (so settingValue=null) — defaults to FALSE.
        when(appSettingService.findByKey(SETTING_KEY)).thenReturn(Optional.of(setting(null)));

        assertFalse(featureFlag.isEnabled());
    }

    private AppSetting setting(String value) {
        AppSetting s = new AppSetting();
        s.setSettingKey(SETTING_KEY);
        s.setSettingValue(value);
        return s;
    }
}
