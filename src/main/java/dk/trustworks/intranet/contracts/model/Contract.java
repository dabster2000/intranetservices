package dk.trustworks.intranet.contracts.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import dk.trustworks.intranet.contracts.model.enums.ContractStatus;
import dk.trustworks.intranet.contracts.model.enums.ContractType;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


@Data
@Entity
@Table(name = "contracts")
public class Contract extends PanacheEntityBase {

    @Id
    private String uuid;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "companyuuid")
    private Company company;

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

    @Column(name = "leaduuid")
    private String leaduuid;

    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime created;

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

    public Contract(Contract contract) {
        this();
        this.status = ContractStatus.INACTIVE;
        this.note = contract.getNote();
        this.amount = 0.0;
        this.created = LocalDateTime.now();
        this.salesconsultant = contract.getSalesconsultant();
        this.refid = contract.getRefid();
        this.activeFrom = contract.getActiveTo().plusMonths(1).withDayOfMonth(1);
        this.activeTo = contract.getActiveTo().plusMonths(3).withDayOfMonth(1);
        this.parentuuid = contract.getUuid();
        this.contractType = contract.getContractType();
        this.clientuuid = contract.getClientuuid();
        this.clientdatauuid = contract.getClientdatauuid();
        this.name = contract.getName();
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
