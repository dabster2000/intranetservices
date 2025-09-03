package dk.trustworks.intranet.contracts.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import dk.trustworks.intranet.contracts.model.enums.ContractStatus;
import dk.trustworks.intranet.contracts.model.enums.ContractType;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;


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

    private String clientuuid;

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

    @JsonProperty("salesconsultant")
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "sales_consultant")
    private ContractSalesConsultant salesconsultant;

    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @JoinColumn(name = "contractuuid")
    private Set<ContractConsultant> contractConsultants = new HashSet<>();

    @OneToMany(fetch = FetchType.EAGER)
    @JoinColumn(name = "contractuuid")
    private Set<ContractProject> contractProjects = new HashSet<>();

    @OneToMany(fetch = FetchType.EAGER)
    @JoinColumn(name = "contractuuid")
    @JsonProperty("contractTypeItems")
    private Set<ContractTypeItem> contractTypeItems = new HashSet<>();

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
        this.parentuuid = contract.getUuid();
        this.contractType = contract.getContractType();
        this.clientuuid = contract.getClientuuid();
        this.clientdatauuid = contract.getClientdatauuid();
        this.name = contract.getName();
    }

    public LocalDate getActiveFrom() {
        return contractConsultants.stream().map(ContractConsultant::getActiveFrom).min(LocalDate::compareTo).orElse(null);
    }

    public LocalDate getActiveTo() {
        return contractConsultants.stream().map(ContractConsultant::getActiveTo).max(LocalDate::compareTo).orElse(null);
    }

    public ContractConsultant findByUserAndDate(User user, LocalDate date) {
        Optional<ContractConsultant> first = contractConsultants.stream().filter(consultant -> consultant.getUseruuid().equals(user.getUuid()) && DateUtils.isBetweenBothIncluded(date, consultant.getActiveFrom(), consultant.getActiveTo())).findFirst();
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
                ", company=" + company.getName() +
                ", amount=" + amount +
                ", contractType=" + contractType +
                ", refid='" + refid + '\'' +
                ", status=" + status +
                ", clientuuid='" + clientuuid + '\'' +
                ", parentuuid='" + parentuuid + '\'' +
                ", leaduuid='" + leaduuid + '\'' +
                ", created=" + created +
                ", name='" + name + '\'' +
                ", clientdatauuid='" + clientdatauuid + '\'' +
                ", note='" + note + '\'' +
                ", contractConsultants=" + contractConsultants.size() +
                ", contractProjects=" + contractProjects.size() +
                '}';
    }
}
