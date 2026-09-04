package dk.trustworks.intranet.aggregates.bugreport.services;

import dk.trustworks.intranet.aggregates.bugreport.dto.AutoFixModelDTO;
import dk.trustworks.intranet.aggregates.bugreport.entities.AutofixModelCatalogEntry;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the selectable auto-fix model catalogue (table {@code autofix_model_catalog}).
 *
 * <p>This replaces the hardcoded {@code Set.of(...)} allow-list that used to sit in
 * {@code BugReportResource}. Two properties matter to callers:
 *
 * <ul>
 *   <li><b>Ordering is stable.</b> The old constant was a {@code Set.of(...)}, whose
 *       iteration order is salted per JVM boot — the dropdown really did reorder itself
 *       after every restart. Everything here returns an ordered {@link List}.</li>
 *   <li><b>It never returns nothing.</b> If the table is empty or unreadable, the
 *       {@link #STATIC_FALLBACK} keeps the settings page renderable and saveable rather
 *       than presenting an empty dropdown.</li>
 * </ul>
 */
@ApplicationScoped
@JBossLog
public class AutoFixModelCatalogService {

    /**
     * Default when {@code autofix.model} is unset. Single definition — previously this
     * literal was duplicated in BugReportResource, BugReportAutoFixService and worker.py.
     */
    public static final String DEFAULT_MODEL = "claude-opus-4-8";

    /** Effort levels the CLI accepts, in ascending order. Ordered: renders a dropdown. */
    public static final List<String> ALL_EFFORTS =
            List.of("low", "medium", "high", "xhigh", "max");

    /**
     * Last-resort catalogue, used only when the table is empty or the read fails.
     * Deliberately the pre-migration allow-list: a degraded settings page offers exactly
     * what it offered before, never less.
     */
    static final List<AutoFixModelDTO> STATIC_FALLBACK = List.of(
            new AutoFixModelDTO("claude-opus-4-8", "Claude Opus 4.8", "Opus", "high",
                    true, ALL_EFFORTS, "VERIFIED", null),
            new AutoFixModelDTO("claude-sonnet-4-6", "Claude Sonnet 4.6", "Sonnet", "medium",
                    false, List.of("low", "medium", "high", "max"), "VERIFIED", null),
            new AutoFixModelDTO("claude-haiku-4-5", "Claude Haiku 4.5", "Haiku", "low",
                    false, ALL_EFFORTS, "VERIFIED", null));

    /**
     * The selectable models, in display order. Falls back to {@link #STATIC_FALLBACK}
     * rather than throwing — the settings page must render even with a broken catalogue.
     */
    public List<AutoFixModelDTO> listModels() {
        try {
            List<AutofixModelCatalogEntry> rows = AutofixModelCatalogEntry.listSelectable();
            if (rows.isEmpty()) {
                log.warn("autofix_model_catalog is empty; serving the static fallback list");
                return STATIC_FALLBACK;
            }
            List<AutoFixModelDTO> models = new ArrayList<>(rows.size());
            for (AutofixModelCatalogEntry row : rows) {
                models.add(new AutoFixModelDTO(
                        row.modelId,
                        row.displayName,
                        row.family,
                        row.costTier,
                        row.recommended,
                        List.copyOf(row.efforts()),
                        row.workerStatus,
                        row.notes));
            }
            return models;
        } catch (Exception e) {
            log.warnf(e, "Could not read autofix_model_catalog; serving the static fallback list");
            return STATIC_FALLBACK;
        }
    }

    /**
     * Model ids valid for a PUT, given what is currently stored.
     *
     * <p>The stored value is always unioned in. Without that, retiring a model from the
     * catalogue would make the existing configuration unsaveable: the admin could not
     * even toggle the kill switch, because PUT would reject the model it is already on.
     */
    public Set<String> validModelIds(String currentlyStored) {
        Set<String> ids = new LinkedHashSet<>();
        for (AutoFixModelDTO m : listModels()) ids.add(m.id());
        if (currentlyStored != null && !currentlyStored.isBlank()) ids.add(currentlyStored);
        return ids;
    }

    /**
     * Effort levels valid for {@code modelId}. Falls back to every level for an unknown
     * model (e.g. one only present because it is the stored value) — an unknown model is
     * not a reason to reject an effort we cannot disprove.
     */
    public List<String> supportedEfforts(String modelId) {
        for (AutoFixModelDTO m : listModels()) {
            if (m.id().equals(modelId)) {
                return m.supportedEfforts().isEmpty() ? List.of() : m.supportedEfforts();
            }
        }
        return ALL_EFFORTS;
    }

    /** Union of every effort level any selectable model accepts — the UI's outer bound. */
    public List<String> allEfforts() {
        return ALL_EFFORTS;
    }
}
