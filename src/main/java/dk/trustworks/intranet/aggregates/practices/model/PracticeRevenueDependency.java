package dk.trustworks.intranet.aggregates.practices.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

@Getter @Setter @Entity
@IdClass(PracticeRevenueDependency.Key.class)
@Table(name = "fact_practice_revenue_dependency_mat")
public class PracticeRevenueDependency extends PanacheEntityBase {
    @Id @Column(name = "generation_id") public String generationId;
    @Id @Column(name = "dependent_item_control_key") public String dependentItemControlKey;
    @Id @Column(name = "dependency_kind") public String dependencyKind;
    @Id @Column(name = "dependency_key") public String dependencyKey;
    @Id @Column(name = "dependency_sequence") public int dependencySequence;
    @Column(name = "dependent_recognized_month") public LocalDate dependentRecognizedMonth;
    @Column(name = "dependency_source_category") public String dependencySourceCategory;
    @Column(name = "source_document_uuid") public String sourceDocumentUuid;
    @Column(name = "source_item_uuid") public String sourceItemUuid;
    @Column(name = "source_attribution_uuid") public String sourceAttributionUuid;
    @Column(name = "source_work_uuid") public String sourceWorkUuid;
    @JsonIgnore @Column(name = "source_user_uuid") public String sourceUserUuid;
    @Column(name = "source_task_uuid") public String sourceTaskUuid;
    @Column(name = "source_project_uuid") public String sourceProjectUuid;
    @Column(name = "source_contract_project_uuid") public String sourceContractProjectUuid;
    @Column(name = "source_contract_uuid") public String sourceContractUuid;
    @Column(name = "source_contract_consultant_uuid") public String sourceContractConsultantUuid;
    @Column(name = "source_self_billed_uuid") public String sourceSelfBilledUuid;
    @Column(name = "source_phantom_uuid") public String sourcePhantomUuid;
    @Column(name = "source_practice_basis_generation_id") public String sourcePracticeBasisGenerationId;
    @JsonIgnore @Column(name = "source_capacity_user_uuid") public String sourceCapacityUserUuid;
    @Column(name = "source_capacity_start_date") public LocalDate sourceCapacityStartDate;
    @Column(name = "source_capacity_end_date") public LocalDate sourceCapacityEndDate;
    @Column(name = "delivery_start_date") public LocalDate deliveryStartDate;
    @Column(name = "delivery_end_date") public LocalDate deliveryEndDate;
    @Column(name = "booked_voucher_key") public String bookedVoucherKey;
    @Column(name = "dependency_fingerprint") public String dependencyFingerprint;
    @Column(name = "created_at") public LocalDateTime createdAt;

    @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Key implements Serializable {
        public String generationId;
        public String dependentItemControlKey;
        public String dependencyKind;
        public String dependencyKey;
        public int dependencySequence;
    }
}
