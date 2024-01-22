package dk.trustworks.intranet.financeservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.persistence.*;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "accounting_categories")
public class AccountingCategory  extends PanacheEntityBase {
    @Id
    @EqualsAndHashCode.Include
    private String uuid;
    @Column(name = "accountcode")
    private String accountCode;
    @Column(name = "groupname")
    private String accountname;
    @OneToMany(mappedBy = "accountingCategory", fetch = FetchType.EAGER)
    private List<AccountingAccount> accounts;
    @Transient
    private double primarySum;
    @Transient
    private double adjustedPrimarySum;
    @Transient
    private double secondarySum;
    @Transient
    private double adjustedSecondarySum;

    public AccountingCategory(String accountCode, String accountname) {
        uuid = UUID.randomUUID().toString();
        accounts = new ArrayList<>();
        this.accountname = accountname;
        this.accountCode = accountCode;
    }

    public void addPrimarySum(double sum) {
        this.primarySum += sum;
    }

    public void addSecondarySum(double sum) {
        this.secondarySum += sum;
    }
}
