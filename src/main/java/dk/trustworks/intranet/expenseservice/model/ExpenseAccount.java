package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceUnit;
import jakarta.persistence.Table;

@Data
@Entity
@NoArgsConstructor
@Table(name = "expense_account")
@PersistenceUnit
public class ExpenseAccount extends PanacheEntityBase {

    @Id
    private String account_no;
    private String account_name;
    private Boolean is_active;
    private String expense_category_uuid;

    public ExpenseAccount(String accountNumber) {
        this.account_no = accountNumber;
    }
}

