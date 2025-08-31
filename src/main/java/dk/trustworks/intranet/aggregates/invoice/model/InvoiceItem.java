package dk.trustworks.intranet.aggregates.invoice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

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
    public enum ItemOrigin { USER, AUTO_RULE }

    // ...inside class (fields)...
    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false)
    public ItemOrigin origin = ItemOrigin.USER;

    @Column(name = "locked", nullable = false)
    public boolean locked = false;

    @Column(name = "rulecode")
    public String ruleCode;

    @Column(name = "calc_note")
    public String calcNote;                 // optional human-readable detail (“2% of 123,000.00”)


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