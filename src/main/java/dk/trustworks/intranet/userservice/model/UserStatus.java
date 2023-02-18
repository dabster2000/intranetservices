package dk.trustworks.intranet.userservice.model;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import javax.persistence.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Created by hans on 23/06/2017.
 */

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

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public StatusType getStatus() {
        return status;
    }

    public void setStatus(StatusType status) {
        this.status = status;
    }

    public LocalDate getStatusdate() {
        return statusdate;
    }

    public void setStatusdate(LocalDate statusdate) {
        this.statusdate = statusdate;
    }

    public int getAllocation() {
        return allocation;
    }

    public void setAllocation(int allocation) {
        this.allocation = allocation;
    }

    public ConsultantType getType() {
        return type;
    }

    public void setType(ConsultantType type) {
        this.type = type;
    }

    public String getUseruuid() {
        return useruuid;
    }

    public void setUseruuid(String useruuid) {
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
