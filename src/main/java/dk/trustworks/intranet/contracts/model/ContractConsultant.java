package dk.trustworks.intranet.contracts.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Data
@AllArgsConstructor
@Entity
@Table(name = "contract_consultants")
public class ContractConsultant extends PanacheEntityBase {

    @Id
    private String uuid;

    private String contractuuid;

    private String useruuid;

    @Column(name = "activefrom")
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate activeFrom;

    @Column(name = "activeto")
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate activeTo;

    private double rate;

    private double hours;

    public ContractConsultant() {
    }

    private ContractConsultant(ContractConsultant cc, Contract c) {
        uuid = UUID.randomUUID().toString();
        contractuuid = c.getUuid();
        useruuid = cc.getUseruuid();
        rate = cc.getRate();
        hours = cc.getHours();
    }

    public static ContractConsultant createContractConsultant(ContractConsultant cc, Contract c) {
        return new ContractConsultant(cc, c);
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
