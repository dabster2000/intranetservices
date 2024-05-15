package dk.trustworks.intranet.expenseservice.model;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Data
@NoArgsConstructor
@Entity
@Table(name = "user_ext_account")
public class UserAccount extends PanacheEntityBase{

    @Id
    private String useruuid;
    private Integer economics;
    private String danlon;
    private String username;

    public UserAccount (Integer economics, String danlon, String username) {
        this.economics = economics;
        this.danlon = danlon;
        this.username = username;
    }

}
