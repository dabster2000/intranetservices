package dk.trustworks.intranet.cvtool.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "cv_tool_employee_cv")
@NoArgsConstructor
public class CvToolEmployeeCv extends PanacheEntityBase {

    @Id
    @NonNull
    @EqualsAndHashCode.Include
    private String uuid;

    @NonNull
    private String useruuid;

    @Column(name = "cvtool_employee_id")
    private int cvtoolEmployeeId;

    @Column(name = "cvtool_cv_id")
    private int cvtoolCvId;

    @Column(name = "employee_name")
    private String employeeName;

    @Column(name = "employee_title")
    private String employeeTitle;

    @Column(name = "employee_profile", columnDefinition = "TEXT")
    private String employeeProfile;

    @NonNull
    @Column(name = "cv_data_json", columnDefinition = "LONGTEXT")
    private String cvDataJson;

    @Column(name = "cv_language")
    private int cvLanguage;

    @NonNull
    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @Column(name = "cv_last_updated_at")
    private LocalDateTime cvLastUpdatedAt;
}
