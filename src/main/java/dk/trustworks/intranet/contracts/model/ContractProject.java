package dk.trustworks.intranet.contracts.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.util.UUID;

@Data
@Entity
@Table(name = "contract_project")
public class ContractProject extends PanacheEntityBase {

    @Id
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
