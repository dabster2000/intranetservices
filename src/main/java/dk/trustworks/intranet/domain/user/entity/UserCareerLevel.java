package dk.trustworks.intranet.domain.user.entity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.userservice.model.enums.CareerLevel;
import dk.trustworks.intranet.userservice.model.enums.CareerTrack;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "user_career_level")
public class UserCareerLevel extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    public String uuid;

    private String useruuid;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    @Column(name = "active_from")
    private LocalDate activeFrom;

    @Enumerated(EnumType.STRING)
    @Column(name = "career_track", length = 30)
    private CareerTrack careerTrack;

    @Enumerated(EnumType.STRING)
    @Column(name = "career_level", length = 40)
    private CareerLevel careerLevel;

    public UserCareerLevel() {
    }

    public UserCareerLevel(String useruuid, LocalDate activeFrom, CareerTrack careerTrack, CareerLevel careerLevel) {
        this.uuid = UUID.randomUUID().toString();
        this.useruuid = useruuid;
        this.activeFrom = activeFrom;
        this.careerTrack = careerTrack;
        this.careerLevel = careerLevel;
    }

    public static List<UserCareerLevel> findByUseruuid(String useruuid) {
        return find("useruuid", useruuid).list();
    }
}
