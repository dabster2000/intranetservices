package dk.trustworks.intranet.aggregates.crm.retention;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import org.junit.jupiter.api.DisplayName;
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
 * The arming switch, pinned in the only direction that matters.
 *
 * <p>This flag is the difference between a purge that ships dark and one that erases two
 * years of accumulated third-party data on the afternoon it deploys. Every way of failing to
 * read it — the row absent because V608 has not run, the value blank because somebody cleared
 * the field, the value a word rather than a boolean — has to come out {@code false}, because
 * the only safe reading of "I do not know whether I am armed" is "I am not".
 */
@ExtendWith(MockitoExtension.class)
class CrmRetentionFeatureFlagTest {

    @Mock
    private AppSettingService appSettingService;

    @InjectMocks
    private CrmRetentionFeatureFlag flag;

    @Test
    @DisplayName("no row at all reads as disarmed")
    void missingRowReadsAsFalse() {
        when(appSettingService.findByKey(CrmRetentionFeatureFlag.PURGE_ENABLED_KEY))
                .thenReturn(Optional.empty());
        assertFalse(flag.isPurgeArmed());
    }

    @Test
    @DisplayName("the seeded 'false' reads as disarmed")
    void seededFalseReadsAsFalse() {
        when(appSettingService.findByKey(CrmRetentionFeatureFlag.PURGE_ENABLED_KEY))
                .thenReturn(Optional.of(setting("false")));
        assertFalse(flag.isPurgeArmed());
    }

    @Test
    @DisplayName("a word that is not a boolean reads as disarmed, never as armed")
    void garbageReadsAsFalse() {
        when(appSettingService.findByKey(CrmRetentionFeatureFlag.PURGE_ENABLED_KEY))
                .thenReturn(Optional.of(setting("yes please")));
        assertFalse(flag.isPurgeArmed());
    }

    @Test
    @DisplayName("a blank value reads as disarmed")
    void blankReadsAsFalse() {
        when(appSettingService.findByKey(CrmRetentionFeatureFlag.PURGE_ENABLED_KEY))
                .thenReturn(Optional.of(setting("   ")));
        assertFalse(flag.isPurgeArmed());
    }

    @Test
    @DisplayName("a null value reads as disarmed rather than throwing")
    void nullValueReadsAsFalse() {
        when(appSettingService.findByKey(CrmRetentionFeatureFlag.PURGE_ENABLED_KEY))
                .thenReturn(Optional.of(setting(null)));
        assertFalse(flag.isPurgeArmed());
    }

    @Test
    @DisplayName("only a deliberate 'true' arms it")
    void trueArmsIt() {
        when(appSettingService.findByKey(CrmRetentionFeatureFlag.PURGE_ENABLED_KEY))
                .thenReturn(Optional.of(setting("true")));
        assertTrue(flag.isPurgeArmed());
    }

    @Test
    @DisplayName("a settings screen that stores ' TRUE ' still arms it — the value is trimmed and case-insensitive")
    void paddedTrueArmsIt() {
        when(appSettingService.findByKey(CrmRetentionFeatureFlag.PURGE_ENABLED_KEY))
                .thenReturn(Optional.of(setting("  TRUE  ")));
        assertTrue(flag.isPurgeArmed());
    }

    private static AppSetting setting(String value) {
        AppSetting appSetting = new AppSetting();
        appSetting.setSettingValue(value);
        return appSetting;
    }
}
