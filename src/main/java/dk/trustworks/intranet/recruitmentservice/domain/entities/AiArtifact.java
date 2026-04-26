package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "recruitment_ai_artifact")
public class AiArtifact extends PanacheEntityBase {

    @Id
    @Column(length = 36)
    public String uuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_kind", length = 40, nullable = false)
    public AiSubjectKind subjectKind;

    @Column(name = "subject_uuid", length = 36, nullable = false)
    public String subjectUuid;

    @Column(name = "kind", length = 40, nullable = false)
    public String kind;  // store enum name as string; matches V307 VARCHAR(40)

    @Column(name = "prompt_version", length = 40, nullable = false)
    public String promptVersion;

    @Column(name = "model", length = 80, nullable = false)
    public String model;

    @Column(name = "input_digest", length = 64, nullable = false)
    public String inputDigest;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output", columnDefinition = "JSON")
    public String output;  // stored as raw JSON string; service serialises/deserialises

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "JSON")
    public String evidence;

    @Column(name = "confidence", precision = 4, scale = 3)
    public BigDecimal confidence;

    @Column(name = "state", length = 40, nullable = false)
    public String state;

    @Column(name = "generated_at")
    public LocalDateTime generatedAt;

    @Column(name = "reviewed_by_uuid", length = 36)
    public String reviewedByUuid;

    @Column(name = "reviewed_at")
    public LocalDateTime reviewedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "override_json", columnDefinition = "JSON")
    public String overrideJson;
}
