package dk.trustworks.intranet.financeservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dk.trustworks.intranet.model.Company;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.persistence.*;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "accounting_accounts")
public class AccountingAccount extends PanacheEntityBase {
    @Id
    @EqualsAndHashCode.Include
    private String uuid;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "companyuuid")
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
}
