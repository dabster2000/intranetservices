package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link RecruitmentGdprParameters}: missing or
 * garbage rows fall back to the plan's defaults (30 / 7 / 7), never to
 * "off" — the engine's on/off switch is the flag, not these.
 */
@ExtendWith(MockitoExtension.class)
class RecruitmentGdprParametersTest {

    @Mock
    private AppSettingService appSettingService;

    @InjectMocks
    private RecruitmentGdprParameters parameters;

    @Test
    void missingRows_fallBackToTheDefaults() {
        when(appSettingService.findByKey(RecruitmentGdprParameters.RENEWAL_FIRST_DAYS_KEY))
                .thenReturn(Optional.empty());
        when(appSettingService.findByKey(RecruitmentGdprParameters.RENEWAL_SECOND_DAYS_KEY))
                .thenReturn(Optional.empty());
        when(appSettingService.findByKey(RecruitmentGdprParameters.ART14_WARNING_DAYS_KEY))
                .thenReturn(Optional.empty());

        assertEquals(30, parameters.renewalFirstDays());
        assertEquals(7, parameters.renewalSecondDays());
        assertEquals(7, parameters.art14WarningDays());
    }

    @Test
    void tunedRow_wins() {
        when(appSettingService.findByKey(RecruitmentGdprParameters.RENEWAL_FIRST_DAYS_KEY))
                .thenReturn(Optional.of(setting("45")));
        assertEquals(45, parameters.renewalFirstDays());
    }

    @Test
    void garbageAndNonPositiveValues_fallBackToTheDefaults() {
        when(appSettingService.findByKey(RecruitmentGdprParameters.RENEWAL_FIRST_DAYS_KEY))
                .thenReturn(Optional.of(setting("a month")));
        when(appSettingService.findByKey(RecruitmentGdprParameters.RENEWAL_SECOND_DAYS_KEY))
                .thenReturn(Optional.of(setting("0")));
        when(appSettingService.findByKey(RecruitmentGdprParameters.ART14_WARNING_DAYS_KEY))
                .thenReturn(Optional.of(setting("-3")));

        assertEquals(30, parameters.renewalFirstDays());
        assertEquals(7, parameters.renewalSecondDays());
        assertEquals(7, parameters.art14WarningDays());
    }

    private static AppSetting setting(String value) {
        AppSetting setting = new AppSetting();
        setting.setSettingValue(value);
        return setting;
    }
}
