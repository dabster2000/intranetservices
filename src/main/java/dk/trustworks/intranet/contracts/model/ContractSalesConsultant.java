package dk.trustworks.intranet.contracts.model;

import dk.trustworks.intranet.contracts.model.enums.SalesStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "contract_sales")
public class ContractSalesConsultant extends PanacheEntityBase {

    @Id
    private String uuid;

    private String contractuuid;

    @Column(name = "sales_consultant")
    private String salesconsultant;

    @Enumerated(EnumType.STRING)
    private SalesStatus status;

    private LocalDateTime created;

}
