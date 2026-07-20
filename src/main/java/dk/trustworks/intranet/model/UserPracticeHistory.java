package dk.trustworks.intranet.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Effective-dated practice attribution row (V407 + the V424 dual-key/provenance
 * columns). Half-open periods [effective_from, effective_to); {@code effective_to
 * = null} means "current". The table carries a UNIQUE key on (useruuid,
 * effective_from) and a CHECK {@code effective_to > effective_from} — same-day
 * transitions therefore collapse into an in-place update of the open row, exactly
 * as the retired V407 triggers did.
 * <p>
 * From Phase 2 (V426) this table is written exclusively by
 * {@link dk.trustworks.intranet.services.PracticeSyncService} — the V407/V424
 * trigger family is dropped. Provenance: {@code source} is {@code TEAM_SYNC}
 * (derived from team membership) or {@code MANUAL} (team-less direct assignment);
 * legacy trigger/seed source values remain as historical values.
 * {@code source_team_uuid} is the team that drove a derived transition;
 * {@code updated_by} is the acting user (null for the reconciliation tick).
 * <p>
 * There is intentionally no FK to user: deleting a user must not erase or block
 * audit history (V407 decision).
 * <p>
 * Phase 5A: the row's practice identity is {@code practice_uuid} alone — the
 * legacy code column is unmapped, unwritten, and dropped by V428.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "user_practice_history")
public class UserPracticeHistory extends PanacheEntityBase {

    @Id
    private String uuid;

    private String useruuid;

    /**
     * The period's practice identity (registry uuid; NULL = a first-class
     * "no practice" period since Phase 4). Sole key since Phase 5A — the
     * legacy {@code practice} code column is no longer mapped or written
     * (rows written before 5A keep their stale stored codes untouched until
     * V428 drops the column). This entity has no REST exposure; the code
     * string, where needed, derives via the registry.
     */
    @Column(name = "practice_uuid")
    private String practiceUuid;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;

    private String source;

    @Column(name = "source_team_uuid")
    private String sourceTeamUuid;

    @Column(name = "updated_by")
    private String updatedBy;
}
