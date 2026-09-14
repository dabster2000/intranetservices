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

    /**
     * The tombstone (V615). Set when this row was merged into another client: everything
     * that referenced it now references {@code mergedIntoUuid}, and every listing filters
     * this row out ({@link #NOT_MERGED}). The row itself stays so a stale link or a cached
     * uuid still resolves — and answers with where the company went. Never set through
     * the create or update endpoints: {@code ClientService.save} blanks it and
     * {@code ClientService.updateOne} does not list it.
     */
    @Column(name = "merged_into_uuid", length = 36)
    private String mergedIntoUuid;

    @Column(name = "merged_at")
    private LocalDateTime mergedAt;

    /**
     * The Panache predicate every client listing carries. A merged row is not a client
     * anybody may pick, search for, dedup against or enrich; it exists only to answer
     * {@code findById} with a forwarding address.
     */
    public static final String NOT_MERGED = "mergedIntoUuid is null";

    /** Derived from {@link #mergedIntoUuid}; not serialised — the uuid says it and says where. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isMerged() {
        return mergedIntoUuid != null && !mergedIntoUuid.isBlank();
    }

    @Transient private List<Project> projects;

    public Client() {
        this.projects = new ArrayList<>();
        this.created = LocalDateTime.now();
        this.segment = ClientSegment.OTHER;
        this.managed = "INTRA";
        this.billingCountry = "DK";
        this.currency = "DKK";
        this.type = ClientType.CLIENT;
    }

    public Client(String contactname, String name) {
        uuid = UUID.randomUUID().toString();
        this.contactname = contactname;
        this.created = LocalDateTime.now();
        this.name = name;
        this.crmid = "";
        this.segment = ClientSegment.OTHER;
        this.projects = new ArrayList<>();
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
