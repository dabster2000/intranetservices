package dk.trustworks.intranet.financeservice.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import dk.trustworks.intranet.model.Company;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "accounting_accounts")
public class AccountingAccount extends PanacheEntityBase {
    @Id
    @EqualsAndHashCode.Include
    private String uuid;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "companyuuid")
    @JsonIgnore
    private Company company;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "categoryuuid")
    @JsonIgnore
    private AccountingCategory accountingCategory;
    @Column(name = "account_code")
    private int accountCode;
    @Column(name = "account_description")
    private String accountDescription;
    private boolean shared;
    private boolean salary;
    @Transient
    private double sum;
    @Transient
    private double adjustedSum;
    @Transient
    private double debt;
    @Transient
    private double loan;

    public AccountingAccount(Company company, AccountingCategory accountingCategory, int accountCode, String accountDescription, boolean shared, boolean salary) {
        uuid = UUID.randomUUID().toString();
        this.company = company;
        this.accountingCategory = accountingCategory;
        this.accountCode = accountCode;
        this.accountDescription = accountDescription;
        this.shared = shared;
        this.salary = salary;
    }

    @JsonGetter("companyuuid")
    public String getCompanyuuid() {
        return company.getUuid();
    }

    public void addSum(double sum) {
        this.sum += sum;
    }

    public void addLoan(double loan) {
        this.loan += loan;
    }

    public void addDebt(double debt) {
        this.debt += debt;
    }

    @Override
    public String toString() {
        return "AccountingAccount{" +
                "uuid='" + uuid + '\'' +
                ", company=" + company.getName() +
                ", accountingCategory=" + accountingCategory.getAccountname() +
                ", accountCode=" + accountCode +
                ", accountDescription='" + accountDescription + '\'' +
                ", shared=" + shared +
                ", salary=" + salary +
                ", sum=" + sum +
                ", adjustedSum=" + adjustedSum +
                ", debt=" + debt +
                ", loan=" + loan +
                '}';
    }
}
