package dk.trustworks.intranet.dao.crm.model;


import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "client")
public class Client extends PanacheEntityBase {
    @Id
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

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getContactname() {
        return contactname;
    }

    public void setContactname(String contactname) {
        this.contactname = contactname;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCrmid() {
        return crmid;
    }

    public void setCrmid(String crmid) {
        this.crmid = crmid;
    }

    public String getAccountmanager() {
        return accountmanager;
    }

    public void setAccountmanager(String accountmanager) {
        this.accountmanager = accountmanager;
    }

    public List<Clientdata> getClientdata() {
        return clientdata;
    }

    public void setClientdata(List<Clientdata> clientdata) {
        this.clientdata = clientdata;
    }

    public List<Project> getProjects() {
        return projects;
    }

    public void setProjects(List<Project> projects) {
        this.projects = projects;
    }

    @Override
    public String toString() {
        return "Client{" +
                "uuid='" + uuid + '\'' +
                ", active=" + active +
                ", contactname='" + contactname + '\'' +
                ", created=" + created +
                ", name='" + name + '\'' +
                ", crmid='" + crmid + '\'' +
                ", accountmanager='" + accountmanager + '\'' +
                ", clientdata=" + clientdata +
                ", projects=" + projects +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if ( !(o instanceof final Client client) ) return false;

        return getUuid().equals(client.getUuid());
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }
}
