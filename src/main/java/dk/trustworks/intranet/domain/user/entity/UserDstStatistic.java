package dk.trustworks.intranet.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.userservice.model.enums.*;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "user_dst_statistics")
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserDstStatistic extends PanacheEntityBase {
    @Id
    @EqualsAndHashCode.Include
    @Column(name = "uuid")
    @JsonProperty("uuid")
    private String uuid;

    @Column(name = "useruuid")
    @JsonProperty("useruuid")
    private String useruuid;

    @Column(name = "active_date")
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonProperty("active_date")
    private LocalDate activeDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "employement_type")
    @JsonProperty("employement_type")
    private DstEmploymentType employementType;

    @Enumerated(EnumType.STRING)
    @Column(name = "employement_terms")
    @JsonProperty("employement_terms")
    private DstEmploymentTerms employementTerms;

    @Enumerated(EnumType.STRING)
    @Column(name = "employement_function")
    @JsonProperty("employement_function")
    private DstEmploymentFunction employementFunction;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_status")
    @JsonProperty("job_status")
    private DstEmploymentStatus jobStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "salary_type")
    @JsonProperty("salary_type")
    private DstSalaryType salaryType;

    public static List<UserDstStatistic> findByUser(String useruuid) {
        return UserDstStatistic.find("useruuid", useruuid).list();
    }
}