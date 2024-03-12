package dk.trustworks.intranet.invoiceservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Created by hans on 08/07/2017.
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "invoiceitems")
public class InvoiceItem extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    public String uuid;
    public String consultantuuid;
    public String itemname;
    public String description;
    public double rate;
    public double hours;
    @JsonIgnore
    public String invoiceuuid;

    public InvoiceItem() {
    }

    public InvoiceItem(String itemname, String description, double rate, double hours, String invoiceuuid) {
        uuid = UUID.randomUUID().toString();
        this.itemname = itemname;
        this.description = description;
        this.rate = rate;
        this.hours = hours;
        this.invoiceuuid = invoiceuuid;
    }

    public InvoiceItem(String useruuid, String itemname, String description, double rate, double hours, String invoiceuuid) {
        this(itemname, description, rate, hours, invoiceuuid);
        this.consultantuuid = useruuid;
    }

}