package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;

import jakarta.persistence.*;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Table(name = "expense_category")
public class ExpenseCategory extends PanacheEntityBase {

    @Id
    private String uuid;
    private String category_name;
    private Boolean internal_expense;
    private Boolean is_active;

    @OneToMany(fetch = FetchType.EAGER, mappedBy = "expense_category_uuid", cascade = CascadeType.ALL)
    private List<ExpenseAccount> expenseAccounts;

    public ExpenseCategory() {
        uuid = UUID.randomUUID().toString();
    }

}