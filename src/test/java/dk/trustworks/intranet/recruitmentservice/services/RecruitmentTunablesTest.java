package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The parsing contract behind every admin-tunable number in this module
 * (cadence thresholds and list lengths alike): a bad row falls back to the
 * compiled default, <em>never</em> to zero and never to "off".
 *
 * <p>This matters more than it looks. These values are typed into a free-text
 * admin field, and the ones added on 2026-08-22 decide how many rows a list
 * shows. A "0" reaching the landing page would silently blank the idle list;
 * a "0" reaching the SLA sweep would silence a reminder loop with no error
 * anywhere. The feature switches are separate booleans precisely so a
 * mistyped number can never act as one.
 *
 * <p>Pure: {@link AppSettingService} is subclassed with an in-memory map, so
 * this runs in the DB-free fast tier that gates deploys.
 */
class RecruitmentTunablesTest {

    private static final String KEY = "recruitment.ui.task-rows";

    @Test
    void aGoodValueWins() {
        assertEquals(12, positiveInt("12", 5));
        assertEquals(1, positiveInt("1", 5), "one row is a legitimate choice");
    }

    @Test
    void surroundingWhitespaceIsForgiven() {
        assertEquals(7, positiveInt("  7  ", 5),
                "a trailing space from a copy-paste must not silently reset the value");
    }

    @Test
    void aMissingRowFallsBackToTheDefault() {
        assertEquals(5, positiveInt(null, 5));
    }

    @Test
    void aBlankRowFallsBackToTheDefault() {
        assertEquals(5, positiveInt("", 5));
        assertEquals(5, positiveInt("   ", 5));
    }

    @Test
    void zeroAndNegativesFallBackRatherThanBlankingTheThingTheyMeasure() {
        assertEquals(5, positiveInt("0", 5),
                "0 rows would hide the list; 0 hours would silence the sweep");
        assertEquals(5, positiveInt("-3", 5));
    }

    @Test
    void garbageFallsBackInsteadOfThrowing() {
        assertEquals(5, positiveInt("five", 5));
        assertEquals(5, positiveInt("5.5", 5), "not an integer");
        assertEquals(5, positiveInt("true", 5),
                "a boolean pasted into a number field must not reach the read model");
    }

    // ---- fixture ----------------------------------------------------------

    private static int positiveInt(String storedValue, int defaultValue) {
        return RecruitmentTunables.positiveInt(
                new FakeAppSettings(storedValue == null ? Map.of() : Map.of(KEY, storedValue)),
                KEY, defaultValue);
    }

    /** In-memory stand-in — the real service is a thin Panache wrapper. */
    private static final class FakeAppSettings extends AppSettingService {
        private final Map<String, String> rows;

        private FakeAppSettings(Map<String, String> rows) {
            this.rows = rows;
        }

        @Override
        public Optional<AppSetting> findByKey(String settingKey) {
            String value = rows.get(settingKey);
            return value == null ? Optional.empty()
                    : Optional.of(new AppSetting(settingKey, value, "recruitment", "test"));
        }
    }
}
