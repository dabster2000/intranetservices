package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;

/**
 * The one way this module turns an {@code app_settings} row into a number.
 *
 * <p>The rule it encodes is small and load-bearing: a missing, blank or
 * unparseable value falls back to the <em>compiled default</em>, never to
 * zero and never to "off". Every caller here tunes a cadence or a row count;
 * none of them has a meaningful "0" reading, and a typo in an admin text
 * field must not be able to silence a reminder loop or blank a list. The
 * on/off switches are separate boolean flags ({@code RecruitmentFeatureFlag})
 * precisely so these can never act as one.
 *
 * <p>Read per call, no cache — the {@code RecruitmentFeatureFlag} idiom. The
 * table is tiny and an admin edit takes effect on the next request or sweep
 * without a redeploy, which is the whole point of these living in the
 * database rather than in {@code application.properties}.
 */
final class RecruitmentTunables {

    private RecruitmentTunables() {
    }

    /** The value at {@code key}, or {@code defaultValue} if it is absent or not a positive integer. */
    static int positiveInt(AppSettingService appSettingService, String key, int defaultValue) {
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
