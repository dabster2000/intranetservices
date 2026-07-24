package dk.trustworks.intranet.documentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
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
 * Pure unit tests for {@link EmployeeDocumentsFeatureFlag} (spec §11):
 * every toggle is opt-in — a missing or unparseable row reads as FALSE,
 * so the feature ships dark and stays dark until the settings tab arms
 * it explicitly.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeDocumentsFeatureFlagTest {

    @Mock
    private AppSettingService appSettingService;

    @InjectMocks
    private EmployeeDocumentsFeatureFlag flag;

    @Test
    void missingRows_readAsFalse() {
        when(appSettingService.findByKey(EmployeeDocumentsFeatureFlag.HR_TAB_KEY))
                .thenReturn(Optional.empty());
        when(appSettingService.findByKey(EmployeeDocumentsFeatureFlag.SELF_SERVICE_KEY))
                .thenReturn(Optional.empty());
        when(appSettingService.findByKey(EmployeeDocumentsFeatureFlag.WRITER_SIGNING_KEY))
                .thenReturn(Optional.empty());
        when(appSettingService.findByKey(EmployeeDocumentsFeatureFlag.WRITER_PROMOTION_KEY))
                .thenReturn(Optional.empty());
        when(appSettingService.findByKey(EmployeeDocumentsFeatureFlag.WRITER_ONBOARDING_KEY))
                .thenReturn(Optional.empty());
        when(appSettingService.findByKey(EmployeeDocumentsFeatureFlag.RETENTION_KEY))
                .thenReturn(Optional.empty());
        when(appSettingService.findByKey(EmployeeDocumentsFeatureFlag.REVIEW_SLACK_NOTIFY_KEY))
                .thenReturn(Optional.empty());

        assertFalse(flag.isHrTabEnabled());
        assertFalse(flag.isSelfServiceEnabled());
        assertFalse(flag.isSigningWriterEnabled());
        assertFalse(flag.isPromotionWriterEnabled());
        assertFalse(flag.isOnboardingWriterEnabled());
        assertFalse(flag.isRetentionEnabled());
        assertFalse(flag.isReviewSlackNotifyEnabled());
    }

    @Test
    void garbageValue_readsAsFalse() {
        when(appSettingService.findByKey(EmployeeDocumentsFeatureFlag.WRITER_SIGNING_KEY))
                .thenReturn(Optional.of(setting("yes please")));
        assertFalse(flag.isSigningWriterEnabled());
    }

    @Test
    void trueRow_readsAsTrue() {
        when(appSettingService.findByKey(EmployeeDocumentsFeatureFlag.RETENTION_KEY))
                .thenReturn(Optional.of(setting("true")));
        assertTrue(flag.isRetentionEnabled());
    }

    @Test
    void falseRow_readsAsFalse() {
        when(appSettingService.findByKey(EmployeeDocumentsFeatureFlag.HR_TAB_KEY))
                .thenReturn(Optional.of(setting("false")));
        assertFalse(flag.isHrTabEnabled());
    }

    private static AppSetting setting(String value) {
        AppSetting s = new AppSetting();
        s.setSettingValue(value);
        return s;
    }
}
