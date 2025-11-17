package dk.trustworks.intranet.expenseservice.model;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_ext_account")
public class UserAccount extends PanacheEntityBase{

    @Id
    private String useruuid;
    private Integer economics;
    private String username;

    public UserAccount (Integer economics, String username) {
        this.economics = economics;
        this.username = username;
    }

}
