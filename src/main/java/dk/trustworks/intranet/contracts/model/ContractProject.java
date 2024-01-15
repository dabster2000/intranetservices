package dk.trustworks.intranet.contracts.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper=false)
@Entity
@Table(name = "contract_project")
public class ContractProject extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;
    private String contractuuid;
    private String projectuuid;

    public ContractProject() {
        this.uuid = UUID.randomUUID().toString();
    }

    public ContractProject(String contractuuid, String projectuuid) {
        this.uuid = UUID.randomUUID().toString();
        this.contractuuid = contractuuid;
        this.projectuuid = projectuuid;
    }
}
