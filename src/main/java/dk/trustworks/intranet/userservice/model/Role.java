package dk.trustworks.intranet.userservice.model;


import com.fasterxml.jackson.annotation.JsonIgnore;
import dk.trustworks.intranet.userservice.model.enums.RoleType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.*;
import java.util.List;

/**
 * Created by hans on 23/06/2017.
 */
@Entity
@Table(name = "roles")
public class Role extends PanacheEntityBase {

    @Id
    private String uuid;

    @Enumerated(EnumType.STRING)
    private RoleType role;

    @JsonIgnore
    private String useruuid;

    public Role() {
    }

    public Role(String uuid, RoleType role, String useruuid) {
        this.uuid = uuid;
        this.role = role;
        this.useruuid = useruuid;
    }

    public Role(RoleType role) {
        this.role = role;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public RoleType getRole() {
        return role;
    }

    public void setRole(RoleType role) {
        this.role = role;
    }

    public String getUseruuid() {
        return useruuid;
    }

    public void setUseruuid(String useruuid) {
        this.useruuid = useruuid;
    }

    public static List<Role> findByUseruuid(String useruuid){
        return find("useruuid", useruuid).list();
    }

    @Override
    public String toString() {
        return "Role{" +
                "uuid='" + uuid + '\'' +
                ", role=" + role +
                ", useruuid='" + useruuid + '\'' +
                '}';
    }
}
