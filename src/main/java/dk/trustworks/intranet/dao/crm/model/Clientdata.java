package dk.trustworks.intranet.dao.crm.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "clientdata")
public class Clientdata extends PanacheEntityBase {
    @Id
    private String uuid;
    private String city;
    private String clientname;
    private String contactperson;
    private String cvr;
    private String ean;
    private String otheraddressinfo;
    private Long postalcode;
    private String streetnamenumber;
    private String clientuuid;

    @Transient private Client client;
    @Transient private List<Project> project;

    public Clientdata() {
    }

    public Clientdata(String city, String clientname, String contactperson, String cvr, String ean, String otheraddressinfo, Long postalcode, String streetnamenumber, Client client) {
        this.uuid = UUID.randomUUID().toString();
        this.city = city;
        this.clientname = clientname;
        this.contactperson = contactperson;
        this.cvr = cvr;
        this.ean = ean;
        this.otheraddressinfo = otheraddressinfo;
        this.postalcode = postalcode;
        this.streetnamenumber = streetnamenumber;
        this.client = client;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getClientname() {
        return clientname;
    }

    public void setClientname(String clientname) {
        this.clientname = clientname;
    }

    public String getContactperson() {
        return contactperson;
    }

    public void setContactperson(String contactperson) {
        this.contactperson = contactperson;
    }

    public String getCvr() {
        return cvr;
    }

    public void setCvr(String cvr) {
        this.cvr = cvr;
    }

    public String getEan() {
        return ean;
    }

    public void setEan(String ean) {
        this.ean = ean;
    }

    public String getOtheraddressinfo() {
        return otheraddressinfo;
    }

    public void setOtheraddressinfo(String otheraddressinfo) {
        this.otheraddressinfo = otheraddressinfo;
    }

    public Long getPostalcode() {
        return postalcode;
    }

    public void setPostalcode(Long postalcode) {
        this.postalcode = postalcode;
    }

    public String getStreetnamenumber() {
        return streetnamenumber;
    }

    public void setStreetnamenumber(String streetnamenumber) {
        this.streetnamenumber = streetnamenumber;
    }

    public String getClientuuid() {
        return clientuuid;
    }

    public void setClientuuid(String clientuuid) {
        this.clientuuid = clientuuid;
    }

    public Client getClient() {
        return client;
    }

    public void setClient(Client client) {
        this.client = client;
    }

    public List<Project> getProject() {
        return project;
    }

    public void setProject(List<Project> project) {
        this.project = project;
    }

    @Override
    public String toString() {
        return "Clientdata{" +
                "uuid='" + uuid + '\'' +
                ", city='" + city + '\'' +
                ", clientname='" + clientname + '\'' +
                ", contactperson='" + contactperson + '\'' +
                ", cvr='" + cvr + '\'' +
                ", ean='" + ean + '\'' +
                ", otheraddressinfo='" + otheraddressinfo + '\'' +
                ", postalcode=" + postalcode +
                ", streetnamenumber='" + streetnamenumber + '\'' +
                ", clientuuid='" + clientuuid + '\'' +
                '}';
    }
}
