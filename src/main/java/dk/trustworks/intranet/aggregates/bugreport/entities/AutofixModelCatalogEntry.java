package dk.trustworks.intranet.aggregates.bugreport.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A Claude model an admin may select for the auto-fix worker.
 *
 * <p>Replaces the hardcoded {@code ALLOWED_MODELS} constant that used to live in
 * {@code BugReportResource}. Rows are reference data: seeded by Flyway (V564) and
 * edited by hand when Anthropic ships a model. The column set deliberately mirrors
 * what Anthropic's {@code GET /v1/models} returns, so a scheduled refresher can be
 * added later writing to this same table without a schema change.
 *
 * @see dk.trustworks.intranet.aggregates.bugreport.services.AutoFixModelCatalogService
 */
@Entity
@Table(name = "autofix_model_catalog")
@Getter
@Setter
public class AutofixModelCatalogEntry extends PanacheEntityBase {

    /** Exact model id handed to {@code claude --model}. */
    @Id
    @Column(name = "model_id", length = 100)
    public String modelId;

    @Column(name = "display_name", nullable = false, length = 100)
    public String displayName;

    /** Dropdown grouping: Opus / Sonnet / Haiku / Fable. */
    @Column(name = "family", nullable = false, length = 40)
    public String family;

    @Column(name = "sort_order", nullable = false)
    public int sortOrder;

    /**
     * CSV of {@code --effort} levels this model accepts. Empty means the model takes
     * no effort flag at all — not that every level is allowed.
     */
    @Column(name = "supported_efforts", nullable = false, length = 120)
    public String supportedEfforts;

    /** low | medium | high | premium. Drives the cost badge in the settings UI. */
    @Column(name = "cost_tier", nullable = false, length = 20)
    public String costTier;

    @Column(name = "available", nullable = false)
    public boolean available;

    @Column(name = "recommended", nullable = false)
    public boolean recommended;

    /**
     * Whether the <em>deployed</em> worker CLI is known to map this id:
     * {@code VERIFIED}, {@code UNRECOGNIZED} or {@code UNKNOWN}.
     *
     * <p>An UNKNOWN model is still selectable — {@code --model} has no argument
     * validator, so the id is forwarded verbatim and the run generally succeeds.
     * The UI warns rather than blocks, because clearing the warning needs a worker
     * image rebuild, which an admin cannot perform.
     */
    @Column(name = "worker_status", nullable = false, length = 20)
    public String workerStatus;

    @Column(name = "notes", length = 255)
    public String notes;

    @Column(name = "updated_by", length = 100)
    public String updatedBy;

    @Column(name = "updated_at", insertable = false, updatable = false)
    public LocalDateTime updatedAt;

    /** Models offered in the picker, in display order. */
    public static List<AutofixModelCatalogEntry> listSelectable() {
        return find("available = true ORDER BY sortOrder, modelId").list();
    }

    /**
     * Effort levels this model accepts, parsed from the CSV column.
     * An empty result means the model takes no effort flag.
     */
    public Set<String> efforts() {
        if (supportedEfforts == null || supportedEfforts.isBlank()) return Set.of();
        return Arrays.stream(supportedEfforts.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }
}
