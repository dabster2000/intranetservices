package dk.trustworks.intranet.sales.model;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.sales.model.enums.ConsultantCompetencies;
import dk.trustworks.intranet.sales.model.enums.LeadStatus;
import dk.trustworks.intranet.sales.model.enums.LostReason;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import static jakarta.persistence.FetchType.EAGER;

@Data
@NoArgsConstructor
@Entity(name = "sales_lead")
public class SalesLead extends PanacheEntityBase {

    @Id
    private String uuid;
    @OneToMany(fetch = EAGER)
    @JoinColumn(name = "salesleaduuid")
    private Set<SalesLead> children;
    @Column(name = "parent_lead")
    private boolean isParent;
    @ManyToOne(fetch = EAGER)
    @JoinColumn(name = "clientuuid")
    private Client client;
    @ManyToOne(fetch = EAGER)
    @JoinColumn(name = "leadmanager")
    private User leadManager;
    private String description;
    @Column(name = "detailed_description")
    private String detailedDescription;
    @Column(name = "contactinformation")
    private String contactInformation;
    @Enumerated(EnumType.STRING)
    private LeadStatus status;
    private double rate;
    @Column(name = "closedate")
    private LocalDate closeDate;
    private int period;
    private int allocation;
    @Enumerated(EnumType.STRING)
    @Column(name = "competencies")
    private ConsultantCompetencies competencies;
    private boolean extension;
    @Enumerated(EnumType.STRING)
    @Column(name = "lost_reason")
    private LostReason lostReason;
    @OneToMany(mappedBy = "lead", fetch = EAGER)
    Set<SalesLeadConsultant> salesLeadConsultants;
    @Column(name = "created")
    private LocalDateTime created;
    @Column(name = "last_updated")
    private LocalDateTime modified;

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

    @Override
    public String toString() {
        return "SalesLead{" +
                "uuid='" + uuid + '\'' +
                ", children=" + (children!=null?children.size():"0") +
                ", parent=" + isParent +
                ", client=" + client.getUuid() +
                ", description='" + description + '\'' +
                ", contactInformation='" + contactInformation + '\'' +
                ", status=" + status +
                ", rate=" + rate +
                ", closeDate=" + closeDate +
                ", period=" + period +
                ", allocation=" + allocation +
                ", competencies=" + competencies +
                ", extension=" + extension +
                ", lostReason=" + lostReason +
                '}';
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