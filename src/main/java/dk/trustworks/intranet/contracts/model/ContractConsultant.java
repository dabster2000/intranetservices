package dk.trustworks.intranet.contracts.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.util.Objects;

@Data
@AllArgsConstructor
@Entity
@Table(name = "contract_consultants")
public class ContractConsultant extends PanacheEntityBase {

    @Id
    private String uuid;

    private String contractuuid;

    private String useruuid;

    private double rate;

    private double budget;

    private double hours;

    public ContractConsultant() {
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ContractConsultant that = (ContractConsultant) o;
        return Objects.equals(uuid, that.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid);
    }
}
