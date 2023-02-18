package dk.trustworks.intranet.dao.crm.services;

import dk.trustworks.intranet.contracts.model.ContractProject;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.model.Task;
import dk.trustworks.intranet.dao.crm.model.enums.TaskType;
import dk.trustworks.intranet.dao.workservice.model.Work;
import io.quarkus.panache.common.Sort;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProjectService {
/*
    @Inject
    @Channel("project-update")
    Emitter<String> updateEmitter;

    @Inject
    @Channel("project-delete")
    Emitter<String> deleteEmitter;

 */

    public List<Project> listAll() {
        return Project.streamAll(Sort.ascending("name")).map(p -> (Project) p).collect(Collectors.toList());
    }

    public Project findByUuid(@PathParam("uuid") String uuid) {
        return Project.findById(uuid);
    }

    public List<Project> findByActiveTrue() {
        return Project.stream("active", Sort.ascending("name"), true).map(p -> (Project) p).collect(Collectors.toList());
    }

    public static List<Project> findByLocked(@QueryParam("locked") boolean locked) {
        return Project.stream("locked", Sort.ascending("name"), locked).map(p -> (Project) p).collect(Collectors.toList());
    }

    public List<Task> findByProjectUuid(@PathParam("projectuuid") String projectuuid) {
        return Task.stream("projectuuid", Sort.ascending("name"), projectuuid).map(p -> (Task) p).collect(Collectors.toList());
    }

    @Transactional
    public Project save(Project project) {
        project.setUuid(UUID.randomUUID().toString());
        project.persist();
        notify(project.getUuid());
        new Task("Ikke fakturerbar", TaskType.SO, project.getUuid()).persist();
        return project;
    }

    @Transactional
    public void updateOne(Project project) {
        Project.update("active = ?1, " +
                        "budget = ?2, " +
                        "customerreference = ?3, " +
                        "name = ?4, " +
                        "userowneruuid = ?5, " +
                        "clientdatauuid = ?6, " +
                        "locked = ?7 " +
                        "WHERE uuid like ?8 ",
                project.isActive(),
                project.getBudget(),
                project.getCustomerreference(),
                project.getName(),
                project.getUserowneruuid(),
                project.getClientdatauuid(),
                project.isLocked(),
                project.getUuid());
        notify(project.getUuid());
    }

    @Transactional
    public void delete(@PathParam("uuid") String uuid) {
        AtomicBoolean safeToDelete = new AtomicBoolean(true);
        Task.<Task>stream("projectuuid like ?1", uuid).forEach(t -> {
            if(Work.count("taskuuid like ?1", t.getUuid()) > 0) safeToDelete.set(false);
        });
        if(safeToDelete.get()) {
            Project.deleteById(uuid);
            Task.delete("projectuuid like ?1", uuid);
            ContractProject.delete("projectuuid like ?1", uuid);
        }
    }

    private void notify(String projectuuid) {
        //updateEmitter.send(projectuuid);
    }

    public List<Project> findByClientuuid(String clientuuid) {
        return Project.find("clientuuid like ?1", clientuuid).list();
    }

    public List<Project> findByClientAndActiveTrue(String clientuuid) {
        return Project.find("clientuuid like ?1 and active = ?2", clientuuid, true).list();
    }
}