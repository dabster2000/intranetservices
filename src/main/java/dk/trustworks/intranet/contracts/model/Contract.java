package dk.trustworks.intranet.contracts.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.contracts.model.enums.ContractStatus;
import dk.trustworks.intranet.contracts.model.enums.ContractType;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;

import javax.persistence.*;
import java.time.LocalDate;
import java.util.*;


@Data
@Entity
@Table(name = "contracts")
public class Contract extends PanacheEntityBase {

    @Id
    private String uuid;
    private double amount;

    @Column(name = "contracttype")
    @Enumerated(EnumType.STRING)
    private ContractType contractType;

    private String refid;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private ContractStatus status;

    @Column(name = "activefrom")
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate activeFrom;

    private String clientuuid;

    @Column(name = "activeto")
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate activeTo;

    @Column(name = "parentuuid")
    private String parentuuid;

    @Temporal(TemporalType.TIMESTAMP)
    private Date created;

    private String name;

    private String clientdatauuid;

    private String note;

    @Transient
    @JsonProperty("salesconsultant")
    private ContractSalesConsultant salesconsultant;

    @Transient
    private List<ContractConsultant> contractConsultants = new ArrayList<>();
    @Transient
    private List<ContractProject> contractProjects = new ArrayList<>();

    public Contract() {
        uuid = UUID.randomUUID().toString();
    }

    public ContractConsultant findByUser(User user) {
        Optional<ContractConsultant> first = contractConsultants.stream().filter(consultant -> consultant.getUseruuid().equals(user.getUuid())).findFirst();
        return first.orElse(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        Contract that = (Contract) o;
        return com.google.common.base.Objects.equal(getUuid(), that.getUuid());
    }

    @Override
    public int hashCode() {
        return com.google.common.base.Objects.hashCode(super.hashCode(), getUuid());
    }

    @Override
    public String toString() {
        return "Contract{" +
                "uuid='" + uuid + '\'' +
                ", amount=" + amount +
                ", contractType=" + contractType +
                ", refid='" + refid + '\'' +
                ", status=" + status +
                ", activeFrom=" + activeFrom +
                ", clientuuid='" + clientuuid + '\'' +
                ", activeTo=" + activeTo +
                ", parentuuid='" + parentuuid + '\'' +
                ", created=" + created +
                ", name='" + name + '\'' +
                ", clientdatauuid='" + clientdatauuid + '\'' +
                ", note='" + note + '\'' +
                ", contractConsultants=" + contractConsultants.size() +
                ", contractProjects=" + contractProjects.size() +
                '}';
    }
}
