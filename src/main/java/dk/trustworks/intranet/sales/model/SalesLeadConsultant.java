package dk.trustworks.intranet.sales.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

@Data
@Table(name = "sales_lead_consultant")
@Entity
@NoArgsConstructor
public class SalesLeadConsultant extends PanacheEntityBase {

    @Id
    Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leaduuid")
    @JsonIgnore
    SalesLead lead;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "useruuid")
    User user;
}