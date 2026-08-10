package dk.trustworks.intranet.recruitmentservice.airtable;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One row of the Airtable-value → practice mapping config table (ATS P21,
 * spec §10): every Airtable <em>faglighed</em> select value and every
 * team-pipeline (table) name the importer may meet resolves to a
 * {@code practice.uuid} through this table — never through hardcoded
 * practice codes. The practice registry is runtime-mutable, so a new
 * practice (e.g. GEN / Management Consulting) slots in by adding a row
 * here without touching the importer.
 * <p>
 * Matching is case-insensitive after trim ({@link #normalize(String)}) —
 * Airtable selects are hand-typed and drift in casing. An unmapped value
 * BLOCKS a real import (plan §P21 DoD); the dry-run report lists exactly
 * which values are missing.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_airtable_practice_mapping")
@EntityListeners(AuditEntityListener.class)
public class AirtablePracticeMapping extends PanacheEntityBase implements Auditable {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** Verbatim Airtable value (faglighed select or pipeline/table name). */
    @Column(name = "airtable_value", length = 200, nullable = false)
    private String airtableValue;

    /** FK to {@code practice.uuid} — the registry reference. */
    @Column(name = "practice_uuid", length = 36, nullable = false)
    private String practiceUuid;

    // ---- Audit columns (house Auditable pattern) ---------------------------

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "modified_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
    }

    /** Trim + lower-case — the lookup key both sides are normalized with. */
    public static String normalize(String airtableValue) {
        return airtableValue == null ? null : airtableValue.trim().toLowerCase(Locale.ROOT);
    }

    /** The full mapping as a normalized-value → practiceUuid lookup map. */
    public static Map<String, String> lookupMap() {
        List<AirtablePracticeMapping> rows = listAll();
        Map<String, String> map = new HashMap<>();
        for (AirtablePracticeMapping row : rows) {
            map.put(normalize(row.getAirtableValue()), row.getPracticeUuid());
        }
        return map;
    }
}
