package dk.trustworks.intranet.expenseservice.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;

import java.util.List;

@Data
@Entity
@Table(name = "expense_category")
public class ExpenseCategory extends PanacheEntityBase {

    @Id
    @JsonIgnore
    private String uuid;
    @Column(name = "category_name")
    private String categoryName;
    @JsonIgnore
    @Column(name = "active")
    private boolean active;

    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @JoinColumn(name = "expense_category_uuid") // This should match the column name in the expense_account table
    private List<ExpenseAccount> expenseAccounts;

    public void removeExpenseAccountByCompany(String companyuuid) {
        expenseAccounts.removeIf(expenseAccount -> expenseAccount.getCompanyuuid().equals(companyuuid));
    }

    @JsonGetter
    public boolean defaultCategory() {
        return expenseAccounts.stream().anyMatch(ExpenseAccount::isDefaultAccount);
    }
}