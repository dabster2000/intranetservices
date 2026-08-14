package dk.trustworks.intranet.competenceservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dk.trustworks.intranet.competenceservice.model.CompetenceSettingsAudit;

import java.time.LocalDateTime;

/**
 * One row of the settings change log (spec §4.7, §6.3).
 *
 * <p>{@code app_settings} keeps only the current value, but the threshold and the cadence
 * change what a green cell <em>means</em> — so "it was 80% until March, then somebody made it
 * 60%" is itself evidence, and a compliance history read against an unknown threshold is not
 * worth much. Every write appends one of these rows, and the table refuses UPDATE and DELETE
 * at the database level.
 *
 * @param settingKey    the {@code competence.*} key
 * @param oldValue      the previous raw value, {@code null} the first time a key is set. Raw,
 *                      not percent-formatted: the log is evidence about what was stored, and a
 *                      formatter between the row and the reader is a place for a rounding rule
 *                      to rewrite history.
 * @param newValue      what it became
 * @param changedBy     actor uuid — the identity that acted, and the only one that survives a
 *                      rename
 * @param changedByName resolved for display, {@code null} when the actor no longer resolves
 *                      (someone who has left). The uuid still identifies the row, so an
 *                      unresolvable name costs a label, never the audit trail.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SettingsAuditDTO(String settingKey,
                               String oldValue,
                               String newValue,
                               String changedBy,
                               String changedByName,
                               LocalDateTime changedAt) {

    /** @param changedByName resolved by the caller in batch; {@code null} is acceptable */
    public static SettingsAuditDTO of(CompetenceSettingsAudit row, String changedByName) {
        return new SettingsAuditDTO(
                row.getSettingKey(),
                row.getOldValue(),
                row.getNewValue(),
                row.getChangedBy(),
                changedByName,
                row.getChangedAt());
    }
}
