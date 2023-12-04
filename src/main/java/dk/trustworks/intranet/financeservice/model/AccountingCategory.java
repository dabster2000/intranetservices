package dk.trustworks.intranet.financeservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.persistence.*;
import java.util.List;

@Data
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
    //@JsonIgnore
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
}
