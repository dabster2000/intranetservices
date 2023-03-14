package dk.trustworks.intranet.sales.model;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@Entity(name = "sales_lead")
public class SalesLead extends PanacheEntityBase {

    @Id
    private String uuid;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "clientuuid")
    private Client client;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "leadmanager")
    private User leadManager;
    @Column(name = "contactinformation")
    private String contactInformation;
    @Enumerated
    private LeadStatus status;
    private double rate;
    @Column(name = "closedate")
    private LocalDate closeDate;
    private int period;
    private int allocation;
    private String competencies;
    private boolean extension;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SalesLead salesLead = (SalesLead) o;

        return uuid.equals(salesLead.uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }
}
/*
CREATE TABLE `sales_lead` (
  `uuid` varchar(36) NOT NULL,
  `clientuuid` varchar(36) NOT NULL,
  `leadmanager` varchar(36) DEFAULT NULL,
  `contactinformation` varchar(255) DEFAULT NULL,
  `rate` float DEFAULT 0,
  `closedate` date DEFAULT NULL,
  `period` int(11) DEFAULT NULL,
  `allocation` int(11) DEFAULT NULL,
  `competencies` varchar(36) DEFAULT NULL,
  `extension` tinyint(1) DEFAULT NULL,
  PRIMARY KEY (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
 */