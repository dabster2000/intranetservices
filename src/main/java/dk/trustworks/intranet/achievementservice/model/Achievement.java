package dk.trustworks.intranet.achievementservice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.achievementservice.model.enums.AchievementType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
@Entity
@Table(name = "achievements")
public class Achievement extends PanacheEntityBase {

    @Id
    private String uuid;

    private String useruuid;

    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate achieved;

    @Enumerated(EnumType.STRING)
    private AchievementType achievement;

    public Achievement(String useruuid, LocalDate achieved, AchievementType achievement) {
        this.uuid = UUID.randomUUID().toString();
        this.useruuid = useruuid;
        this.achieved = achieved;
        this.achievement = achievement;
    }
}
