package dk.trustworks.intranet.competenceservice.services;

import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;
import dk.trustworks.intranet.competenceservice.model.CompetenceSettingsAudit;
import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The four module settings, and the change log the concept requires.
 *
 * <p>{@code app_settings} keeps only the current value, but both the threshold and the
 * cadence change what a green cell means — so "what was it before, and who changed it
 * when" is itself evidence. Every write therefore appends to
 * {@code competence_settings_audit}, which is append-only at the database level.
 *
 * <p>The two have very different blast radii and the UI has to say so:
 * <ul>
 *   <li><strong>Threshold</strong> applies to <em>future</em> attempts only. Each attempt
 *       freezes the threshold it started under, so raising it can never retroactively
 *       fail somebody who already passed.</li>
 *   <li><strong>Cadence</strong> applies <em>immediately to everyone</em>, because it is
 *       evaluated at read time against the completion and submission timestamps. Lowering
 *       it can turn a matrix green→red the same second.</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class CompetenceSettingsService {

    public static final String CATEGORY = "competence";
    public static final String KEY_THRESHOLD = "competence.pass-threshold";
    public static final String KEY_CADENCE = "competence.cadence-days";
    public static final String KEY_TIMEOUT = "competence.attempt-timeout-minutes";
    public static final String KEY_RETENTION = "competence.retention-years";

    static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("0.800");
    static final int DEFAULT_CADENCE_DAYS = 365;
    static final int DEFAULT_TIMEOUT_MINUTES = 120;
    static final int DEFAULT_RETENTION_YEARS = 5;

    @Inject
    AppSettingService appSettingService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /**
     * The real admin behind an impersonated request, or {@code null} for an ordinary one.
     *
     * <p>{@code changed_by} is the impersonated subject during impersonation — the swap happens
     * in the BFF's JWT — so a threshold moved from 0.80 to 0.20 under somebody else's session
     * would name them in the append-only audit row and nowhere record that it was not them.
     * The audit table has no column for this (it is append-only and was migrated in V495), so
     * the log line is where the disclosure §10.4 promises actually lands.
     */
    private String actingFor() {
        return requestHeaderHolder.getActingForUuid();
    }

    // -----------------------------------------------------------------------
    // reads
    // -----------------------------------------------------------------------

    /**
     * The pass threshold as a fraction. Falls back to the default rather than throwing:
     * a missing row must not take the whole module down, and staging loses these on every
     * nightly refresh.
     */
    public BigDecimal passThreshold() {
        return readDecimal(KEY_THRESHOLD, DEFAULT_THRESHOLD);
    }

    public int cadenceDays() {
        return readInt(KEY_CADENCE, DEFAULT_CADENCE_DAYS);
    }

    public int attemptTimeoutMinutes() {
        return readInt(KEY_TIMEOUT, DEFAULT_TIMEOUT_MINUTES);
    }

    public int retentionYears() {
        return readInt(KEY_RETENTION, DEFAULT_RETENTION_YEARS);
    }

    /** A per-requirement override wins over the global cadence when set. */
    public int effectiveCadenceDays(CompetenceRequirement requirement) {
        if (requirement != null && requirement.getCadenceDaysOverride() != null
                && requirement.getCadenceDaysOverride() > 0) {
            return requirement.getCadenceDaysOverride();
        }
        return cadenceDays();
    }

    public Map<String, String> currentSettings() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(KEY_THRESHOLD, passThreshold().toPlainString());
        out.put(KEY_CADENCE, String.valueOf(cadenceDays()));
        out.put(KEY_TIMEOUT, String.valueOf(attemptTimeoutMinutes()));
        out.put(KEY_RETENTION, String.valueOf(retentionYears()));
        return out;
    }

    public List<CompetenceSettingsAudit> changeLog(int limit) {
        return CompetenceSettingsAudit.listRecent(Math.max(1, Math.min(limit, 500)));
    }

    // -----------------------------------------------------------------------
    // writes
    // -----------------------------------------------------------------------

    /**
     * Validates, writes, and appends the change-log row in one transaction, so a value can
     * never move without leaving a trace.
     */
    @Transactional
    public void updateSettings(Map<String, String> updates, String actorUuid) {
        if (updates == null || updates.isEmpty()) {
            throw new WebApplicationException("No settings supplied", Response.Status.BAD_REQUEST);
        }
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            String key = entry.getKey();
            String value = validate(key, entry.getValue());
            String oldValue = rawValue(key).orElse(null);
            if (value.equals(oldValue)) {
                continue;
            }
            appSettingService.saveSetting(key, value, CATEGORY, actorUuid);
            audit(key, oldValue, value, actorUuid);
            log.infof("COMPETENCE_SETTING_CHANGED key=%s old=%s new=%s actor=%s actingFor=%s",
                    key, oldValue, value, actorUuid, actingFor());
        }
    }

    private void audit(String key, String oldValue, String newValue, String actorUuid) {
        CompetenceSettingsAudit row = new CompetenceSettingsAudit();
        row.setUuid(UUID.randomUUID().toString());
        row.setSettingKey(key);
        row.setOldValue(oldValue);
        row.setNewValue(newValue);
        row.setChangedBy(actorUuid);
        row.setChangedAt(LocalDateTime.now());
        row.persist();
    }

    /**
     * Server-side validation (spec §11.3). The client mirrors these for feedback; this is
     * the enforcement point.
     *
     * <p>The threshold arrives as a whole percent from the UI and is stored as a fraction,
     * so both forms are accepted and normalised — a caller sending {@code 0.8} and one
     * sending {@code 80} must not end up meaning different things.
     */
    String validate(String key, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new WebApplicationException("Missing value for " + key, Response.Status.BAD_REQUEST);
        }
        String value = rawValue.trim();
        switch (key) {
            case KEY_THRESHOLD -> {
                BigDecimal parsed;
                try {
                    parsed = new BigDecimal(value);
                } catch (NumberFormatException e) {
                    throw new WebApplicationException(
                            "Pass threshold must be a number", Response.Status.BAD_REQUEST);
                }
                if (parsed.compareTo(BigDecimal.ONE) > 0) {
                    parsed = parsed.divide(BigDecimal.valueOf(100), 3, java.math.RoundingMode.HALF_UP);
                }
                if (parsed.compareTo(new BigDecimal("0.01")) < 0
                        || parsed.compareTo(BigDecimal.ONE) > 0) {
                    throw new WebApplicationException(
                            "Pass threshold must be between 1 and 100 percent",
                            Response.Status.BAD_REQUEST);
                }
                return parsed.setScale(3, java.math.RoundingMode.HALF_UP).toPlainString();
            }
            case KEY_CADENCE -> {
                return String.valueOf(requireInt(key, value, 1, 36500));
            }
            case KEY_TIMEOUT -> {
                return String.valueOf(requireInt(key, value, 5, 1440));
            }
            case KEY_RETENTION -> {
                return String.valueOf(requireInt(key, value, 1, 100));
            }
            default -> throw new WebApplicationException(
                    "Unknown competence setting: " + key, Response.Status.BAD_REQUEST);
        }
    }

    private int requireInt(String key, String value, int min, int max) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new WebApplicationException(key + " must be a whole number",
                    Response.Status.BAD_REQUEST);
        }
        if (parsed < min || parsed > max) {
            throw new WebApplicationException(
                    key + " must be between " + min + " and " + max, Response.Status.BAD_REQUEST);
        }
        return parsed;
    }

    // -----------------------------------------------------------------------

    private Optional<String> rawValue(String key) {
        return appSettingService.findByKey(key).map(AppSetting::getSettingValue);
    }

    private BigDecimal readDecimal(String key, BigDecimal fallback) {
        return rawValue(key).map(v -> {
            try {
                return new BigDecimal(v.trim());
            } catch (NumberFormatException e) {
                log.warnf("Invalid %s value %s — falling back to %s", key, v, fallback);
                return fallback;
            }
        }).orElse(fallback);
    }

    private int readInt(String key, int fallback) {
        return rawValue(key).map(v -> {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                log.warnf("Invalid %s value %s — falling back to %d", key, v, fallback);
                return fallback;
            }
        }).orElse(fallback);
    }
}
