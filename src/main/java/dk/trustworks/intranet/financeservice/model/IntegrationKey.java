package dk.trustworks.intranet.financeservice.model;

import dk.trustworks.intranet.model.Company;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.persistence.*;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "integration_keys")
public class IntegrationKey extends PanacheEntityBase {
    @Id
    @EqualsAndHashCode.Include
    private String uuid;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "companyuuid")
    private Company company;
    private String key;
    private String value;
}
