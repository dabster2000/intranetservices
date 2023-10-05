package dk.trustworks.intranet.invoiceservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.invoiceservice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.invoiceservice.model.enums.InvoiceType;
import dk.trustworks.intranet.model.Company;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.persistence.*;
import java.time.LocalDate;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

/**
 * Created by hans on 08/07/2017.
 */

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "invoices")
public class Invoice extends PanacheEntityBase {

    @Id
    public String uuid;
    public String contractuuid;
    public String projectuuid;
    public String projectname;
    public int year;
    public int month;
    public double discount;
    public String clientname;
    public String clientaddresse;
    public String otheraddressinfo;
    public String zipcity;
    public String cvr;
    public String ean;
    public String attention;
    @Column(name = "invoice_ref")
    public int invoiceref;
    public int invoicenumber;

    @Column(name = "referencenumber")
    public int referencenumber;
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    public LocalDate invoicedate;
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    public LocalDate bookingdate; //date from e-conomics
    public String projectref;
    public String contractref;
    public String specificdescription;
    @OneToMany(fetch = FetchType.EAGER, cascade={CascadeType.ALL})
    @JoinColumn(name="invoiceuuid")
    public List<InvoiceItem> invoiceitems;
    @Transient
    @JsonIgnore
    public boolean errors;
    public InvoiceType type;
    @Enumerated(EnumType.STRING)
    public InvoiceStatus status;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "companyuuid")
    public Company company;
    @Lob
    public byte[] pdf;

    @JsonIgnore
    @Transient
    private double sumNoTax;
    @JsonIgnore
    @Transient
    private double sumWithTax;

    public Invoice() {
        this.invoiceitems = new LinkedList<>();
        this.errors = false;
    }

    public Invoice(InvoiceType type, String contractuuid, String projectuuid, String projectname, double discount, int year, int month, String clientname, String clientaddresse, String otheraddressinfo, String zipcity, String ean, String cvr, String attention, LocalDate invoicedate, String projectref, String contractref, Company company, String specificdescription) {
        this();
        this.type = type;
        this.bookingdate = LocalDate.of(1900,1,1);
        if(type.equals(InvoiceType.CREDIT_NOTE)) invoiceref = invoicenumber;
        this.contractuuid = contractuuid;
        this.discount = discount;
        this.otheraddressinfo = otheraddressinfo;
        this.ean = ean;
        this.cvr = cvr;
        this.contractref = contractref;
        this.status = InvoiceStatus.DRAFT;
        this.projectuuid = projectuuid;
        this.projectname = projectname;
        this.year = year;
        this.month = month;
        this.clientname = clientname;
        this.clientaddresse = clientaddresse;
        this.zipcity = zipcity;
        this.attention = attention;
        this.invoicedate = invoicedate;
        this.projectref = projectref;
        this.company = company;
        this.specificdescription = specificdescription;
        uuid = UUID.randomUUID().toString();
    }
}
