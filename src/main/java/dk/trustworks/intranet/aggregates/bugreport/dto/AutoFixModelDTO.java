package dk.trustworks.intranet.aggregates.bugreport.dto;

import java.util.List;

/**
 * One selectable model, as rendered by the Settings -> Auto-Fix dropdown.
 *
 * <p>Sent alongside the flat {@code allowedModels} string array rather than replacing
 * it: a browser still running the previous frontend bundle keeps working, so the
 * backend and frontend can deploy in either order.
 *
 * @param id               exact id passed to {@code claude --model}
 * @param displayName      human label
 * @param family           dropdown grouping (Opus / Sonnet / Haiku / Fable)
 * @param costTier         low | medium | high | premium
 * @param recommended      whether we actively suggest this model
 * @param supportedEfforts effort levels this model accepts; EMPTY means the model
 *                         takes no effort flag, not that all levels are allowed
 * @param workerStatus     VERIFIED | UNRECOGNIZED | UNKNOWN — whether the deployed
 *                         worker CLI is known to map this id
 * @param notes            caveat to surface to the admin, or null
 */
public record AutoFixModelDTO(
        String id,
        String displayName,
        String family,
        String costTier,
        boolean recommended,
        List<String> supportedEfforts,
        String workerStatus,
        String notes
) {
}
