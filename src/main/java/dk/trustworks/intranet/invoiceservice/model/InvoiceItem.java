package dk.trustworks.intranet.invoiceservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.EqualsAndHashCode;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Created by hans on 08/07/2017.
 */
@EqualsAndHashCode
@Entity
@Table(name = "invoiceitems")
public class InvoiceItem extends PanacheEntityBase {

    @Id
    public String uuid;
    @EqualsAndHashCode.Exclude
    public String itemname;
    @EqualsAndHashCode.Exclude
    public String description;
    @EqualsAndHashCode.Exclude
    public double rate;
    @EqualsAndHashCode.Exclude
    public double hours;
    @JsonIgnore
    @EqualsAndHashCode.Exclude
    public String invoiceuuid;

    public InvoiceItem() {

    }

    public InvoiceItem(String itemname, String description, double rate, double hours, String invoiceuuid) {
        this.invoiceuuid = invoiceuuid;
        uuid = UUID.randomUUID().toString();
        this.itemname = itemname;
        this.description = description;
        this.rate = rate;
        this.hours = hours;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getItemname() {
        return itemname;
    }

    public void setItemname(String itemname) {
        this.itemname = itemname;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public double getRate() {
        return rate;
    }

    public void setRate(double rate) {
        this.rate = rate;
    }

    public double getHours() {
        return hours;
    }

    public void setHours(double hours) {
        this.hours = hours;
    }

    public String getInvoiceuuid() {
        return invoiceuuid;
    }

    public void setInvoiceuuid(String invoiceuuid) {
        this.invoiceuuid = invoiceuuid;
    }

    @Override
    public String toString() {
        return "InvoiceItem{" + "uuid='" + uuid + '\'' +
                ", itemname='" + itemname + '\'' +
                ", description='" + description + '\'' +
                ", rate=" + rate +
                ", hours=" + hours +
                '}';
    }
}