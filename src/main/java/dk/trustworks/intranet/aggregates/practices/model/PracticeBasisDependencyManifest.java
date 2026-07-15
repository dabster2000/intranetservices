package dk.trustworks.intranet.aggregates.practices.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@IdClass(PracticeBasisDependencyManifest.Key.class)
@Table(name = "practice_basis_dependency_manifest_mat")
public class PracticeBasisDependencyManifest extends PanacheEntityBase {

    @Id
    @Column(name = "generation_id")
    public String generationId;

    @Id
    @Column(name = "manifest_sequence")
    public int manifestSequence;

    @Column(name = "recognized_document_uuid")
    public String recognizedDocumentUuid;

    @Column(name = "recognized_item_uuid")
    public String recognizedItemUuid;

    @Column(name = "recognized_document_type")
    public String recognizedDocumentType;

    @Column(name = "recognized_month")
    public LocalDate recognizedMonth;

    @Column(name = "dependency_kind")
    public String dependencyKind;

    @Column(name = "source_document_uuid")
    public String sourceDocumentUuid;

    @Column(name = "source_item_uuid")
    public String sourceItemUuid;

    @Column(name = "required_start_date")
    public LocalDate requiredStartDate;

    @Column(name = "required_end_date")
    public LocalDate requiredEndDate;

    @Column(name = "source_fingerprint")
    public String sourceFingerprint;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        public String generationId;
        public int manifestSequence;
    }
}
