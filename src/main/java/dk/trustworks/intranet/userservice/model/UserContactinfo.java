package dk.trustworks.intranet.userservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Data
@Entity
@Table(name = "user_contactinfo")
@NoArgsConstructor
@AllArgsConstructor
public class UserContactinfo extends PanacheEntityBase {

    @Id
    private String uuid;

    @Column(name = "street")
    private String streetname;

    private String postalcode;

    private String city;

    private String phone;

    @JsonIgnore
    private String useruuid;

    public static UserContactinfo findByUseruuid(String useruuid){
        return find("useruuid", useruuid).firstResult();
    }

}
