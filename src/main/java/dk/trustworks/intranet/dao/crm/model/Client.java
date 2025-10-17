package dk.trustworks.intranet.dao.crm.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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

    @Transient private List<Clientdata> clientdata;
    @Transient private List<Project> projects;

    public Client() {
        this.projects = new ArrayList<>();
        this.clientdata = new ArrayList<>();
        this.created = LocalDateTime.now();
    }

    public Client(String contactname, String name) {
        uuid = UUID.randomUUID().toString();
        this.active = true;
        this.contactname = contactname;
        this.created = LocalDateTime.now();
        this.name = name;
        this.crmid = "";
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
