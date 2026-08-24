package dk.trustworks.intranet.vacationservice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import dk.trustworks.intranet.vacationservice.model.enums.VacationImportBatchStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(name = "vacation_import_batches")
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class VacationImportBatch extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    private String companyuuid;

    private String filename;

    @Column(name = "as_of_date")
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate asOfDate;

    @Enumerated(EnumType.STRING)
    private VacationImportBatchStatus status;

    @Column(name = "uploaded_by")
    private String uploadedBy;

    @Column(name = "uploaded_at", insertable = false, updatable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "applied_by")
    private String appliedBy;

    @Column(name = "row_count")
    private int rowCount;

    @Column(name = "matched_count")
    private int matchedCount;

    @Column(name = "unmatched_count")
    private int unmatchedCount;

    public static List<VacationImportBatch> findByCompany(String companyuuid) {
        return list("companyuuid = ?1 ORDER BY uploadedAt DESC", companyuuid);
    }
}
