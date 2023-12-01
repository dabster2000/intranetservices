package dk.trustworks.intranet.aggregateservices.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dk.trustworks.intranet.model.Company;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "work_data_per_month")
@NoArgsConstructor
@AllArgsConstructor
public class WorkDataPerMonth extends PanacheEntityBase {

    @Id
    private String uuid;

    private int year; // done

    private int month;

    private String useruuid; // done

    private boolean workas;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "contract_company_uuid")
    private Company contractCompany;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "consultant_company_uuid")
    private Company consultantCompany;

    @Column(name = "workduration")
    private double workDuration;

    @Column(name = "total_billed")
    private double totalBilled;

    @JsonIgnore
    public LocalDate getDate() {
        return LocalDate.of(year, month, 1);
    }

}
