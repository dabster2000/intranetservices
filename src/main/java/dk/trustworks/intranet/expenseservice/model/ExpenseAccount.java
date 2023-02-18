package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Data
@Entity
@NoArgsConstructor
@Table(name = "expense_account")
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

