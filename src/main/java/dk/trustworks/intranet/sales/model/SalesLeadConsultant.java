package dk.trustworks.intranet.sales.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import javax.persistence.*;

@Data
@Table(name = "sales_lead_consultant")
@Entity
@NoArgsConstructor
@RequiredArgsConstructor
public class SalesLeadConsultant extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leaduuid")
    @JsonIgnore
    @NonNull
    SalesLead lead;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "useruuid")
    @NonNull
    User user;
}