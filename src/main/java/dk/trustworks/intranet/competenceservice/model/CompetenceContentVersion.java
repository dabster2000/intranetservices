package dk.trustworks.intranet.competenceservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One version of one artefact — a microcourse or a test.
 *
 * <p><strong>{@code active_key} and {@code draft_key} are deliberately not mapped.</strong>
 * They exist in the table and carry the two conditional unique indexes that make "at most
 * one ACTIVE and at most one DRAFT per (requirement, kind)" a database guarantee, but
 * they are written by {@code trg_competence_content_version_keys_ins/upd} on every insert
 * and update. The trigger overwrites whatever is supplied, so mapping them would only
 * create a way to be confused about who owns the value.
 *
 * <p>{@code payload_json} is a plain String. Mapping it as a JSON column would crash the
 * container at boot — see {@link dk.trustworks.intranet.competenceservice.content.CompetencePayloadCodec}.
 *
 * <p>{@code version_label} is author-chosen and compared for <em>equality only</em>. It is
 * never parsed or ordered: publishing a new version whose label equals the active one
 * therefore leaves everyone green even with force-retake on, which the publish dialog
 * must say out loud.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "competence_content_version")
@EntityListeners(AuditEntityListener.class)
public class CompetenceContentVersion extends PanacheEntityBase implements Auditable {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    @Column(name = "requirement_uuid")
    private String requirementUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_kind")
    private ContentKind contentKind;

    @Column(name = "version_label")
    private String versionLabel;

    @Enumerated(EnumType.STRING)
    private ContentStatus status;

    @Column(name = "payload_json", columnDefinition = "LONGTEXT")
    private String payloadJson;

    /** Set at publish. {@code false} = a correction; nobody retakes. */
    @Column(name = "forced_retake")
    private boolean forcedRetake = true;

    @Column(name = "published_by")
    private String publishedBy;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "superseded_by_uuid")
    private String supersededByUuid;

    @Column(name = "created_at")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "updated_at")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "modified_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    public static CompetenceContentVersion findByUuid(String uuid) {
        return find("uuid", uuid).firstResult();
    }

    public static CompetenceContentVersion findActive(String requirementUuid, ContentKind kind) {
        return find("requirementUuid = ?1 and contentKind = ?2 and status = ?3",
                requirementUuid, kind, ContentStatus.ACTIVE).firstResult();
    }

    public static CompetenceContentVersion findDraft(String requirementUuid, ContentKind kind) {
        return find("requirementUuid = ?1 and contentKind = ?2 and status = ?3",
                requirementUuid, kind, ContentStatus.DRAFT).firstResult();
    }

    public static List<CompetenceContentVersion> findHistory(String requirementUuid) {
        return list("requirementUuid = ?1 order by contentKind, publishedAt desc, createdAt desc",
                requirementUuid);
    }

    /** Every ACTIVE version across all requirements — one query for the whole matrix. */
    public static List<CompetenceContentVersion> listAllActive() {
        return list("status", ContentStatus.ACTIVE);
    }
}
