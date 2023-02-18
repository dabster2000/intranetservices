package dk.trustworks.intranet.expenseservice.model;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Data
@NoArgsConstructor
@Entity
@Table(name = "useraccount")
public class UserAccount extends PanacheEntityBase{

    @Id
    private String useruuid;
    private int account;
    private String username;

    public UserAccount (int account, String username) {
        this.account = account;
        this.username = username;
    }

}
