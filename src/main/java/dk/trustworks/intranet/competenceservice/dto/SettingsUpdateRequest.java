package dk.trustworks.intranet.competenceservice.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The body of the settings write (spec §6.3, §11.3).
 *
 * <p>A sparse map of {@code competence.*} key → new value, not a full settings object. Two
 * reasons, both about evidence rather than convenience:
 *
 * <ul>
 *   <li><strong>Only what changed is written.</strong> {@code updateSettings} skips a key whose
 *       value is unchanged, so a sparse body produces one change-log row per actual change. A
 *       whole-object PUT would submit all four values every time and would only stay quiet
 *       because of that comparison — one refactor away from four rows per save and a log nobody
 *       can read.</li>
 *   <li><strong>Concurrent edits do not clobber.</strong> Two people with the settings page
 *       open cannot overwrite each other's untouched fields.</li>
 * </ul>
 *
 * <p>Values are strings, validated and normalised per key by the service — the threshold in
 * particular is accepted as either {@code 80} or {@code 0.8} and stored as a fraction, so
 * typing a percent and posting a fraction cannot mean different things. An unknown key is a
 * {@code 400} naming it, never a silently-created setting.
 *
 * @param updates settingKey → new raw value; {@code null} or empty is a {@code 400}
 */
public record SettingsUpdateRequest(Map<String, String> updates) {

    public SettingsUpdateRequest {
        // Null-tolerant copy: a {"competence.cadence-days": null} body must reach the
        // service's "Missing value for …" 400 rather than dying in Map.copyOf as a 500.
        updates = updates == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(updates));
    }
}
