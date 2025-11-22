package dk.trustworks.intranet.dao.crm.model;

import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
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

    @Column(name = "segment")
    @Enumerated(EnumType.STRING)
    private ClientSegment segment;

    @Transient private List<Clientdata> clientdata;
    @Transient private List<Project> projects;

    public Client() {
        this.projects = new ArrayList<>();
        this.clientdata = new ArrayList<>();
        this.created = LocalDateTime.now();
        this.segment = ClientSegment.OTHER;
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
