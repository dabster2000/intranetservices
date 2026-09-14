package dk.trustworks.intranet.dao.crm.services;

import dk.trustworks.intranet.contracts.model.ContractProject;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.model.Task;
import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import dk.trustworks.intranet.dao.crm.model.enums.TaskType;
import dk.trustworks.intranet.dao.workservice.model.Work;
import io.quarkus.panache.common.Sort;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@JBossLog
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
        requireBillableClient(project);
        project.setUuid(UUID.randomUUID().toString());
        project.persist();
        notify(project.getUuid());
        new Task("Ikke fakturerbar", TaskType.SO, project.getUuid()).persist();
        log.infof("Project created: uuid=%s, name=%s",
                project.getUuid(), project.getName());
        return project;
    }

    /**
     * A project belongs to a company Intra may bill.
     *
     * <p>Contracts and the e-conomic sync have refused prospects since the type existed;
     * projects were the one thing left that would take one silently. Nothing downstream
     * could invoice it — an invoice needs a contract, and the contract gate holds — but the
     * row was real, it carried work and budgets, and the account looked like a delivery
     * that had never been sold.
     *
     * <p>The refusal is deliberately the same shape as the graduation refusal in
     * {@code ContractService.graduateProspect}: name the company, say what it is, and send
     * the person to the form that fixes it. A prospect becomes a customer by getting its
     * registration number and its first contract — never by having a project hung on it.
     *
     * <p>A missing or unknown client is not this method's business; the project's own
     * validation owns that, and inventing an error for it here would report the wrong
     * problem.
     */
    private void requireBillableClient(Project project) {
        if (project == null || project.getClientuuid() == null || project.getClientuuid().isBlank()) {
            return;
        }
        Client client = Client.findById(project.getClientuuid().trim());
        if (client == null || client.getType() != ClientType.PROSPECT) {
            return;
        }
        throw new BadRequestException(
                client.getName() + " is a prospect — a company Intra has never billed — so it cannot "
                        + "have a project. Add its registration number on the client form and create the "
                        + "contract; the first contract makes it a customer.");
    }

    @Transactional
    public void updateOne(Project project) {
        log.infof("Project updating: uuid=%s, name=%s, active=%s, locked=%s",
                project.getUuid(), project.getName(), project.isActive(), project.isLocked());
        Project.update("active = ?1, " +
                        "budget = ?2, " +
                        "customerreference = ?3, " +
                        "name = ?4, " +
                        "userowneruuid = ?5, " +
                        "locked = ?6 " +
                        "WHERE uuid like ?7 ",
                project.isActive(),
                project.getBudget(),
                project.getCustomerreference(),
                project.getName(),
                project.getUserowneruuid(),
                project.isLocked(),
                project.getUuid());
        notify(project.getUuid());
        log.infof("Project updated: uuid=%s", project.getUuid());
    }

    @Transactional
    public void delete(@PathParam("uuid") String uuid) {
        log.infof("Project delete requested: uuid=%s", uuid);
        AtomicBoolean safeToDelete = new AtomicBoolean(true);
        Task.<Task>stream("projectuuid like ?1", uuid).forEach(t -> {
            if(Work.count("taskuuid like ?1", t.getUuid()) > 0) safeToDelete.set(false);
        });
        if(safeToDelete.get()) {
            Project.deleteById(uuid);
            Task.delete("projectuuid like ?1", uuid);
            ContractProject.delete("projectuuid like ?1", uuid);
            log.infof("Project deleted: uuid=%s (with associated tasks and contract-projects)", uuid);
        } else {
            log.warnf("Project delete blocked: uuid=%s has tasks with registered work", uuid);
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