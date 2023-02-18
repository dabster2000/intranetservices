package dk.trustworks.intranet.dao.crm.model;

import dk.trustworks.intranet.dao.crm.model.enums.TaskType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import javax.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "task")
public class Task extends PanacheEntityBase {
    @Id
    private String uuid;
    private String name;

    @Column(name = "type")
    @Enumerated(EnumType.STRING)
    private TaskType type;

    private String projectuuid;

    public Task() {
    }

    public Task(String name, TaskType type, String projectuuid) {
        this.uuid = UUID.randomUUID().toString();
        this.name = name;
        this.type = type;
        this.projectuuid = projectuuid;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TaskType getType() {
        return type;
    }

    public void setType(TaskType type) {
        this.type = type;
    }

    public String getProjectuuid() {
        return projectuuid;
    }

    public void setProjectuuid(String projectuudi) {
        this.projectuuid = projectuudi;
    }

    @Override
    public String toString() {
        return "Task{" +
                "uuid='" + uuid + '\'' +
                ", name='" + name + '\'' +
                ", type=" + type +
                ", projectuuid='" + projectuuid + '\'' +
                '}';
    }
}
