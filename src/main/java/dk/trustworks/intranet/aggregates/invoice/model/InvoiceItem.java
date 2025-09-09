package dk.trustworks.intranet.aggregates.invoice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Created by hans on 08/07/2017.
 */
@Getter
@Setter
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
    public int position;
    @JsonIgnore
    public String invoiceuuid;

    @Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "origin")
    public InvoiceItemOrigin origin = InvoiceItemOrigin.BASE;

    @Column(name = "calculation_ref")
    public String calculationRef;

    @Column(name = "rule_id")
    public String ruleId;

    @Column(name = "label")
    public String label;

    public InvoiceItem() {
        if( this.uuid == null ) {
            this.uuid = UUID.randomUUID().toString();
        }
    }

    public InvoiceItem(String itemname, String description, double rate, double hours, String invoiceuuid) {
        uuid = UUID.randomUUID().toString();
        this.itemname = itemname;
        this.description = description;
        this.rate = rate;
        this.hours = hours;
        this.invoiceuuid = invoiceuuid;
    }

    public InvoiceItem(String useruuid, String itemname, String description, double rate, double hours, int position, String invoiceuuid) {
        this(itemname, description, rate, hours, invoiceuuid);
        this.consultantuuid = useruuid;
        this.position = position;
    }

    @Override
    public String toString() {
        return "InvoiceItem{" +
                "uuid='" + uuid + '\'' +
                ", itemname='" + itemname + '\'' +
                ", rate=" + rate +
                ", hours=" + hours +
                ", origin=" + origin +
                '}';
    }
}