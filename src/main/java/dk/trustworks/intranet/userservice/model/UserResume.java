package dk.trustworks.intranet.userservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "user_resume")
@NoArgsConstructor
public class UserResume extends PanacheEntityBase {

    public static final int version = 4;

    @Id
    @NonNull
    @EqualsAndHashCode.Include
    private String uuid;

    @NonNull
    private String useruuid;

    @NonNull
    @Column(name = "resume_eng")
    private String resumeENG;

    @NonNull
    @Column(name = "resume_dk")
    private String resumeDK;

    @NonNull
    @JsonIgnore
    @Column(name = "resume_result")
    private String resumeResult;

    @JsonIgnore
    @Column(name = "resume_version")
    private int resumeVersion;

    public UserResume(@NonNull String uuid, @NonNull String useruuid, @NonNull String resumeENG, @NonNull String resumeDK, @NonNull String resumeResult) {
        this.uuid = uuid;
        this.useruuid = useruuid;
        this.resumeENG = resumeENG;
        this.resumeDK = resumeDK;
        this.resumeResult = resumeResult;
        this.resumeVersion = version;
    }
}