package dk.trustworks.intranet.expenseservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@NoArgsConstructor
@Table(name = "expense_account")
public class ExpenseAccount extends PanacheEntityBase {

    @Id
    @JsonIgnore
    private String uuid;
    @JsonIgnore
    private String companyuuid;
    @Column(name = "account_number")
    private int accountNumber;
    @Column(name = "account_name")
    private String accountName;
    @JsonIgnore
    private boolean active;
    @Transient
    private boolean defaultAccount = false;

}

