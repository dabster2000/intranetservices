package dk.trustworks.intranet.userservice.model;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;

import javax.persistence.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Created by hans on 23/06/2017.
 */

@Data
@Entity
@Table(name = "userstatus")
public class UserStatus extends PanacheEntityBase {

    @Id
    public String uuid;

    @Enumerated(EnumType.STRING)
    private ConsultantType type;

    @Enumerated(EnumType.STRING)
    private StatusType status;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate statusdate;

    private int allocation;

    @JsonIgnore
    private String useruuid;

    @Column(name = "is_tw_bonus_eligible")
    @JsonProperty("isTwBonusEligible")
    private boolean isTwBonusEligible;

    public UserStatus() {
    }

    public UserStatus(ConsultantType type, StatusType status, LocalDate statusdate, int allocation, String useruuid) {
        this.useruuid = useruuid;
        uuid = UUID.randomUUID().toString();
        this.type = type;
        this.status = status;
        this.statusdate = statusdate;
        this.allocation = allocation;
    }

    public UserStatus(String uuid, ConsultantType type, StatusType status, LocalDate statusdate, int allocation, String useruuid) {
        this.uuid = uuid;
        this.type = type;
        this.status = status;
        this.statusdate = statusdate;
        this.allocation = allocation;
        this.useruuid = useruuid;
    }

    public static List<UserStatus> findByUseruuid(String useruuid){
        return find("useruuid", useruuid).list();
    }

    @Override
    public String toString() {
        return "UserStatus{" +
                "uuid='" + uuid + '\'' +
                ", type=" + type +
                ", status=" + status +
                ", statusdate=" + statusdate +
                ", allocation=" + allocation +
                ", useruuid='" + useruuid + '\'' +
                '}';
    }
}
