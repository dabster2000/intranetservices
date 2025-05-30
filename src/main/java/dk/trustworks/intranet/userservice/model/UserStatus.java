package dk.trustworks.intranet.userservice.model;


import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Created by hans on 23/06/2017.
 */

@Data
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "userstatus")
public class UserStatus extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    public String uuid;

    @Enumerated(EnumType.STRING)
    private ConsultantType type;

    @Enumerated(EnumType.STRING)
    private StatusType status;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate statusdate;

    private int allocation;

    private String useruuid;

    @Column(name = "is_tw_bonus_eligible")
    @JsonProperty("isTwBonusEligible")
    private boolean isTwBonusEligible;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "companyuuid")
    private Company company;

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
}
