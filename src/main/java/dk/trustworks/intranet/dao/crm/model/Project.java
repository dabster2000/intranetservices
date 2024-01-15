package dk.trustworks.intranet.dao.crm.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "project")
public class Project extends PanacheEntityBase {
    @Id
    private String uuid;
    private boolean active;
    private Double budget;

    private String customerreference;
    private String name;
    private boolean locked;

    private String clientuuid;

    @Transient private List<Task> tasks;
    @Transient private Clientdata clientdata;

    private String userowneruuid;
    private String clientdatauuid;

    public Project() {
    }

    public Project(String name, String clientuuid) {
        this.clientuuid = clientuuid;
        uuid = UUID.randomUUID().toString();
        active = true;
        budget = 0.0;
        tasks = new ArrayList<>();
        customerreference = "";
        this.name = name;
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

    public Double getBudget() {
        return budget;
    }

    public void setBudget(Double budget) {
        this.budget = budget;
    }

    public String getCustomerreference() {
        return customerreference;
    }

    public void setCustomerreference(String customerreference) {
        this.customerreference = customerreference;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public String getClientuuid() {
        return clientuuid;
    }

    public void setClientuuid(String clientuuid) {
        this.clientuuid = clientuuid;
    }

    public List<Task> getTasks() {
        return tasks;
    }

    public void setTasks(List<Task> tasks) {
        this.tasks = tasks;
    }

    public Clientdata getClientdata() {
        return clientdata;
    }

    public void setClientdata(Clientdata clientdata) {
        this.clientdata = clientdata;
    }

    public String getClientdatauuid() {
        return clientdatauuid;
    }

    public void setClientdatauuid(String clientdatauuid) {
        this.clientdatauuid = clientdatauuid;
    }

    @Override
    public String toString() {
        return "Project{" +
                "uuid='" + uuid + '\'' +
                ", active=" + active +
                ", budget=" + budget +
                ", customerreference='" + customerreference + '\'' +
                ", name='" + name + '\'' +
                ", locked=" + locked +
                ", userowneruuid='" + userowneruuid + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Project project = (Project) o;

        return uuid.equals(project.uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    public String getUserowneruuid() {
        return userowneruuid;
    }

    public void setUserowneruuid(String userowneruuid) {
        this.userowneruuid = userowneruuid;
    }
}
