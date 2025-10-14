package dk.trustworks.intranet.aggregates.invoice.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.pricing.CalculationBreakdownLine;
import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import dk.trustworks.intranet.contracts.model.enums.ContractType;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.model.enums.SalesApprovalStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.eclipse.microprofile.config.ConfigProvider;

import java.time.LocalDate;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

/**
 * Created by hans on 08/07/2017.
 */

@Getter
@Setter
@Entity
@Table(name = "invoices")
public class Invoice extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    public String uuid;
    public String contractuuid;
    public String projectuuid;
    public String projectname;
    @Column(name = "bonus_consultant")
    public String bonusConsultant;
    @Column(name = "bonus_consultant_approved")
    public SalesApprovalStatus bonusConsultantApprovedStatus;
    @Column(name = "bonus_override_amount")
    public double bonusOverrideAmount;
    @Column(name = "bonus_override_note")
    public String bonusOverrideNote;
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
    public String currency;
    public double vat;
    @Column(name = "referencenumber")
    public int referencenumber;
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    public LocalDate invoicedate;
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    public LocalDate duedate;
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    public LocalDate bookingdate; //date from e-conomics
    public String projectref;
    public String contractref;
    @Column(name = "contract_type")
    @Enumerated(EnumType.STRING)
    public ContractType contractType;
    public String specificdescription;
    @OneToMany(fetch = FetchType.EAGER, cascade={CascadeType.ALL})
    @JoinColumn(name="invoiceuuid")
    public List<InvoiceItem> invoiceitems;
    @Transient
    @JsonIgnore
    public boolean errors;
    @Enumerated(EnumType.STRING)
    public InvoiceType type;
    @Enumerated(EnumType.STRING)
    public InvoiceStatus status;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "companyuuid")
    public Company company;
    @Lob
    @ToString.Exclude
    public byte[] pdf;

    @Enumerated(EnumType.STRING)
    @Column(name = "economics_status")
    public EconomicsInvoiceStatus economicsStatus;

    @Column(name = "economics_voucher_number")
    public int economicsVoucherNumber;

    @Column(name = "creditnote_for_uuid")
    public String creditnoteForUuid;

    @Column(name = "debtor_companyuuid")
    public String debtorCompanyuuid;

    @Transient
    public Double sumBeforeDiscounts;

    @Transient
    public Double sumAfterDiscounts;

    @Transient
    public Double vatAmount;

    @Transient
    public Double grandTotal;

    @Transient
    public List<CalculationBreakdownLine> calculationBreakdown;

    public Invoice() {
        this.invoiceitems = new LinkedList<>();
        this.errors = false;
        this.economicsStatus = EconomicsInvoiceStatus.NA;
    }

    public Invoice(int invoiceref, InvoiceType type, String contractuuid, String projectuuid, String projectname, double discount, int year, int month, String clientname, String clientaddresse, String otheraddressinfo, String zipcity, String ean, String cvr, String attention, LocalDate invoicedate, LocalDate duedate, String projectref, String contractref, ContractType contractType, Company company, String currency, String specificdescription) {
        this();
        this.type = type;
        this.contractType = contractType;
        this.currency = currency;
        if(currency.equals("DKK")) this.vat = 25.0;
        else this.vat = 0.0;
        this.bookingdate = LocalDate.of(1900,1,1);
        this.invoiceref = invoiceref;
        this.contractuuid = contractuuid;
        this.discount = discount;
        this.otheraddressinfo = otheraddressinfo;
        this.ean = ean;
        this.cvr = cvr;
        this.contractref = contractref;
        this.status = InvoiceStatus.DRAFT;
        this.economicsStatus = EconomicsInvoiceStatus.NA;
        this.projectuuid = projectuuid;
        this.projectname = projectname;
        this.year = year;
        this.month = month;
        this.clientname = clientname;
        this.clientaddresse = clientaddresse;
        this.zipcity = zipcity;
        this.attention = attention;
        this.invoicedate = invoicedate;
        this.duedate = duedate;
        this.projectref = projectref;
        this.company = company;
        this.specificdescription = specificdescription;
        uuid = UUID.randomUUID().toString();
    }

    public Invoice(int invoiceref, InvoiceType type, String contractuuid, String projectuuid, String projectname, double discount, int year, int month, String clientname, String clientaddresse, String otheraddressinfo, String zipcity, String ean, String cvr, String attention, LocalDate invoicedate, LocalDate duedate, String projectref, String contractref, ContractType contractType, Company company, String currency, double vat, String specificdescription, String bonusConsultant, SalesApprovalStatus bonusConsultantApprovedStatus) {
        this(invoiceref, type, contractuuid, projectuuid, projectname, discount, year, month, clientname, clientaddresse, otheraddressinfo, zipcity, ean, cvr, attention, invoicedate, duedate, projectref, contractref, contractType, company, currency, specificdescription);
        this.vat = vat;
        this.bonusConsultant = bonusConsultant;
        this.bonusConsultantApprovedStatus = bonusConsultantApprovedStatus;
    }

    @JsonGetter
    public ContractTypeItem getContractTypeItem() {
        return ContractTypeItem.<ContractTypeItem>find("contractuuid", contractuuid).firstResultOptional().orElse(null);
    }

    public double getSumNoTax() {
        return invoiceitems.stream()
                .mapToDouble(ii -> ii.hours * ii.rate)
                .sum(); // Rabatter er allerede udtrykt som syntetiske linjer
    }


    public double getSumWithNoTaxInDKK(double exchangeRate) {
        if(getCurrency().equals("DKK")) return getSumNoTax();
        return getSumNoTax() * exchangeRate;
    }

    @Override
    public String toString() {
        return "Invoice{" +
                "uuid='" + uuid + '\'' +
                '}';
    }
}
