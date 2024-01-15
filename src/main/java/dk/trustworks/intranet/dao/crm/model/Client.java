package dk.trustworks.intranet.dao.crm.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "client")
public class Client extends PanacheEntityBase {
    @Id
    @EqualsAndHashCode.Include
    private String uuid;
    private boolean active;
    private String contactname;
    @Temporal(TemporalType.TIMESTAMP)
    private Date created;
    private String name;
    private String crmid;
    private String accountmanager;

    @Transient private List<Clientdata> clientdata;
    @Transient private List<Project> projects;

    public Client() {
        this.projects = new ArrayList<>();
        this.clientdata = new ArrayList<>();
        this.created = new Date();
    }

    public Client(String contactname, String name) {
        uuid = UUID.randomUUID().toString();
        this.active = true;
        this.contactname = contactname;
        this.created = new Date();
        this.name = name;
        this.crmid = "";
        this.projects = new ArrayList<>();
        this.clientdata = new ArrayList<>();
    }

}
