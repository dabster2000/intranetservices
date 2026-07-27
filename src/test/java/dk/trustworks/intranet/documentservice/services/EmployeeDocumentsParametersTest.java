package dk.trustworks.intranet.documentservice.services;

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
 * Pure unit tests for {@link EmployeeDocumentsParameters} (spec §11):
 * missing/garbage rows fall back to the compiled defaults (5y / 10 / 25MB);
 * the 1-year retention floor and the 25 MB upload ceiling are compiled
 * and NOT admin-overridable.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeDocumentsParametersTest {

    @Mock
    private AppSettingService appSettingService;

    @InjectMocks
    private EmployeeDocumentsParameters parameters;

    @Test
    void missingRows_fallBackToTheDefaults() {
        when(appSettingService.findByKey(EmployeeDocumentsParameters.RETENTION_YEARS_KEY))
                .thenReturn(Optional.empty());
        when(appSettingService.findByKey(EmployeeDocumentsParameters.NIGHTLY_USER_CAP_KEY))
                .thenReturn(Optional.empty());
        when(appSettingService.findByKey(EmployeeDocumentsParameters.UPLOAD_MAX_SIZE_MB_KEY))
                .thenReturn(Optional.empty());

        assertEquals(5, parameters.retentionYears());
        assertEquals(10, parameters.nightlyUserCap());
        assertEquals(25, parameters.uploadMaxSizeMb());
    }

    @Test
    void garbageValues_fallBackToTheDefaults() {
        when(appSettingService.findByKey(EmployeeDocumentsParameters.RETENTION_YEARS_KEY))
                .thenReturn(Optional.of(setting("forever")));
        when(appSettingService.findByKey(EmployeeDocumentsParameters.NIGHTLY_USER_CAP_KEY))
                .thenReturn(Optional.of(setting("-5")));
        when(appSettingService.findByKey(EmployeeDocumentsParameters.UPLOAD_MAX_SIZE_MB_KEY))
                .thenReturn(Optional.of(setting("0")));

        assertEquals(5, parameters.retentionYears());
        assertEquals(10, parameters.nightlyUserCap());
        assertEquals(25, parameters.uploadMaxSizeMb());
    }

    @Test
    void tunedValues_win() {
        when(appSettingService.findByKey(EmployeeDocumentsParameters.RETENTION_YEARS_KEY))
                .thenReturn(Optional.of(setting("7")));
        when(appSettingService.findByKey(EmployeeDocumentsParameters.NIGHTLY_USER_CAP_KEY))
                .thenReturn(Optional.of(setting("3")));
        when(appSettingService.findByKey(EmployeeDocumentsParameters.UPLOAD_MAX_SIZE_MB_KEY))
                .thenReturn(Optional.of(setting("10")));

        assertEquals(7, parameters.retentionYears());
        assertEquals(3, parameters.nightlyUserCap());
        assertEquals(10, parameters.uploadMaxSizeMb());
    }

    @Test
    void uploadCap_clampsToTheHardCeiling() {
        when(appSettingService.findByKey(EmployeeDocumentsParameters.UPLOAD_MAX_SIZE_MB_KEY))
                .thenReturn(Optional.of(setting("500")));
        assertEquals(25, parameters.uploadMaxSizeMb());
        assertEquals(25L * 1024 * 1024, parameters.uploadMaxSizeBytes());
    }

    private static AppSetting setting(String value) {
        AppSetting s = new AppSetting();
        s.setSettingValue(value);
        return s;
    }
}
