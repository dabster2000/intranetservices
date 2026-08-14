package dk.trustworks.intranet.competenceservice.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The four module settings and their change log (spec §4.7, §6.3, §11.3).
 *
 * <p>The threshold travels twice: as the stored fraction and as the whole percent the UI edits.
 * Both are needed and neither is redundant — the fraction is what the comparison uses and what
 * the change log records, while the percent is what a human types. Sending only the fraction
 * would make the settings form derive the percent itself, and a settings form that rounds
 * differently from the score badge is how "80% threshold" and "you scored 80%, failed" end up
 * on adjacent screens. {@link CompetencePercent} rounds thresholds up, so a whole-percent
 * setting round-trips exactly.
 *
 * <p>The two numbers have very different blast radii and the UI has to say so before
 * confirming:
 * <ul>
 *   <li><strong>Threshold</strong> applies to <em>future attempts only</em> — each attempt
 *       freezes the threshold it started under, so raising it cannot retroactively fail
 *       somebody who already passed.</li>
 *   <li><strong>Cadence</strong> applies <em>immediately to everyone</em>, because it is
 *       evaluated at read time against completion and submission timestamps. Lowering it can
 *       turn a matrix green→red the same second.</li>
 * </ul>
 *
 * <p>The change log ships with the values rather than from a second endpoint: the form's
 * from → to confirmation is the moment somebody wants to see what the last change was and who
 * made it, and a separate fetch would let the page render the values without it.
 *
 * @param passThreshold        the stored fraction, e.g. {@code 0.800}
 * @param passThresholdPercent the same value as whole percent, e.g. {@code 80}
 * @param cadenceDays          global renewal interval; a requirement may override it
 * @param attemptTimeoutMinutes after which the reaper abandons an in-progress attempt. Not
 *                             cosmetic: an unreaped attempt blocks the person from ever
 *                             starting another one.
 * @param retentionYears       how long evidence is kept (§10.9). Erasure pseudonymises the
 *                             subject; rows are never deleted, because the triggers forbid it.
 * @param changeLog            newest first, capped by the service
 */
public record SettingsDTO(BigDecimal passThreshold,
                          int passThresholdPercent,
                          int cadenceDays,
                          int attemptTimeoutMinutes,
                          int retentionYears,
                          List<SettingsAuditDTO> changeLog) {

    public SettingsDTO {
        changeLog = changeLog == null ? List.of() : List.copyOf(changeLog);
    }

    /**
     * Builds the view from the values the settings service already resolved, deriving the one
     * number that must not be derived twice.
     *
     * <p>Takes the typed values rather than the {@code Map<String,String>} of
     * {@code currentSettings()} on purpose: parsing that map here would put a second parser
     * next to the service's own, keyed on string constants that live in the service package.
     */
    public static SettingsDTO of(BigDecimal passThreshold,
                                 int cadenceDays,
                                 int attemptTimeoutMinutes,
                                 int retentionYears,
                                 List<SettingsAuditDTO> changeLog) {
        return new SettingsDTO(
                passThreshold,
                CompetencePercent.threshold(passThreshold),
                cadenceDays,
                attemptTimeoutMinutes,
                retentionYears,
                changeLog);
    }
}
