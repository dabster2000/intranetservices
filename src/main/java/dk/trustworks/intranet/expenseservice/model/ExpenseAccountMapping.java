package dk.trustworks.intranet.expenseservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "expense_account_mapping")
public class ExpenseAccountMapping extends PanacheEntityBase {
    @Id
    @JsonIgnore
    public String uuid;
    @Column(name = "account_key")
    public String accountKey;
    public String companyuuid;
    @Column(name = "account_number")
    public String accountNumber;
    @Column(name = "account_name")
    public String accountName;
    public boolean active;
}
