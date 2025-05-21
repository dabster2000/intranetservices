package dk.trustworks.intranet.sales.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dk.trustworks.intranet.apigateway.dto.PublicUser;
import dk.trustworks.intranet.userservice.model.Employee;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@Table(name = "sales_coffee_dates")
@EqualsAndHashCode(callSuper = true)
public class SalesCoffeeDate extends PanacheEntityBase {

    @Id
    public String uuid;
    @JsonIgnore
    private String useruuid;
    @Transient
    private PublicUser publicUser;
    private String bubbleuuid;
    private int coffeeDates;

    public void addPublicUser(Employee employee) {
        this.publicUser = new PublicUser(employee);
    }
}
