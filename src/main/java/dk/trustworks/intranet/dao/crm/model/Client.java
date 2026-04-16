package dk.trustworks.intranet.dao.crm.model;

import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import dk.trustworks.intranet.utils.ValidEan;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.proxy.HibernateProxy;

import java.time.LocalDateTime;
import java.util.*;

@Getter
@Setter
@ToString
@Entity
@Table(name = "client")
public class Client extends PanacheEntityBase {
    @Id
    @EqualsAndHashCode.Include
    private String uuid;
    private boolean active;
    private String contactname;

    private LocalDateTime created;
    private String name;
    private String crmid;
    private String accountmanager;

    @Column(length = 16)
    private String managed;

    @Column(name = "segment")
    @Enumerated(EnumType.STRING)
    private ClientSegment segment;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 10, nullable = false)
    private ClientType type;

    @Column(name = "default_billing_attention", length = 150)
    private String defaultBillingAttention;

    @Column(name = "default_billing_email", length = 255)
    private String defaultBillingEmail;

    // Billing fields
    @Column(name = "cvr", length = 20)
    private String cvr;

    @Column(name = "ean", length = 20)
    @ValidEan
    private String ean;

    @Column(name = "billing_address", length = 510)
    private String billingAddress;

    @Column(name = "billing_zipcode", length = 30)
    private String billingZipcode;

    @Column(name = "billing_city", length = 50)
    private String billingCity;

    @Column(name = "billing_country", length = 2)
    private String billingCountry;

    @Column(name = "billing_email", length = 255)
    private String billingEmail;

    @Column(name = "currency", length = 3)
    private String currency;

    // CVR registry data
    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "industry_code")
    private Integer industryCode;

    @Column(name = "industry_desc", length = 255)
    private String industryDesc;

    @Column(name = "company_code")
    private Integer companyCode;

    @Column(name = "company_desc", length = 100)
    private String companyDesc;

    @Transient private List<Clientdata> clientdata;
    @Transient private List<Project> projects;

    public Client() {
        this.projects = new ArrayList<>();
        this.clientdata = new ArrayList<>();
        this.created = LocalDateTime.now();
        this.segment = ClientSegment.OTHER;
        this.managed = "INTRA";
        this.billingCountry = "DK";
        this.currency = "DKK";
        this.type = ClientType.CLIENT;
    }

    public Client(String contactname, String name) {
        uuid = UUID.randomUUID().toString();
        this.active = true;
        this.contactname = contactname;
        this.created = LocalDateTime.now();
        this.name = name;
        this.crmid = "";
        this.segment = ClientSegment.OTHER;
        this.projects = new ArrayList<>();
        this.clientdata = new ArrayList<>();
        this.managed = "INTRA";
        this.billingCountry = "DK";
        this.currency = "DKK";
        this.type = ClientType.CLIENT;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : this.getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        Client client = (Client) o;
        return getUuid() != null && Objects.equals(getUuid(), client.getUuid());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
