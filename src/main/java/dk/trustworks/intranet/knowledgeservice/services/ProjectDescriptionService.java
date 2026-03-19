package dk.trustworks.intranet.knowledgeservice.services;

import dk.trustworks.intranet.knowledgeservice.model.ProjectDescription;
import dk.trustworks.intranet.knowledgeservice.model.ProjectDescriptionUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class ProjectDescriptionService {

    public List<ProjectDescription> findAll() {
        return ProjectDescription.findAll().list();
    }

    public Optional<ProjectDescription> findById(String uuid) {
        return ProjectDescription.findByIdOptional(uuid);
    }

    @Transactional
    public ProjectDescription create(ProjectDescription projectDescription) {
        projectDescription.setUuid(UUID.randomUUID().toString());
        projectDescription.persist();
        projectDescription.getProjectDescriptionUserList().forEach(projectDescriptionUser -> {
            addProjectDescriptionUser(projectDescription, projectDescriptionUser.getUseruuid());
        });
        log.infof("ProjectDescription created: uuid=%s, name=%s, consultantCount=%d",
                projectDescription.getUuid(), projectDescription.getName(),
                projectDescription.getProjectDescriptionUserList().size());
        return projectDescription;
    }

    @Transactional
    public ProjectDescription update(String uuid, ProjectDescription updatedProjectDescription) {
        ProjectDescription existingProjectDescription = ProjectDescription.findById(uuid);
        if (existingProjectDescription != null) {
            // Update simple fields directly
            existingProjectDescription.setClientuuid(updatedProjectDescription.getClientuuid());
            existingProjectDescription.setName(updatedProjectDescription.getName());
            existingProjectDescription.setPurpose(updatedProjectDescription.getPurpose());
            existingProjectDescription.setRole(updatedProjectDescription.getRole());
            existingProjectDescription.setLearnings(updatedProjectDescription.getLearnings());
            existingProjectDescription.setRoles(updatedProjectDescription.getRoles());
            existingProjectDescription.setMethods(updatedProjectDescription.getMethods());
            existingProjectDescription.setFromDate(updatedProjectDescription.getFromDate());
            existingProjectDescription.setToDate(updatedProjectDescription.getToDate());
            log.infof("ProjectDescription updated: uuid=%s, name=%s", uuid, updatedProjectDescription.getName());
        } else {
            log.warnf("ProjectDescription update failed: uuid=%s not found", uuid);
        }
        return existingProjectDescription;
    }

    @Transactional
    public void delete(String uuid) {
        ProjectDescription.deleteById(uuid);
        log.infof("ProjectDescription deleted: uuid=%s", uuid);
    }

    @Transactional
    public void addProjectDescriptionUser(String uuid, String useruuid) {
        ProjectDescription projectDescription = ProjectDescription.findById(uuid);
        addProjectDescriptionUser(projectDescription, useruuid);
    }

    @Transactional
    public void addProjectDescriptionUser(ProjectDescription projectDescription, String useruuid) {
        if(ProjectDescriptionUser.find("useruuid like ?1 and projectDescription = ?2", useruuid, projectDescription).singleResultOptional().isPresent()) {
            log.debugf("ProjectDescription consultant already exists: projectDescUuid=%s, useruuid=%s",
                    projectDescription.getUuid(), useruuid);
            return;
        }
        ProjectDescriptionUser projectDescriptionUser = new ProjectDescriptionUser(useruuid, projectDescription);
        projectDescriptionUser.persist();
        log.infof("ProjectDescription consultant added: projectDescUuid=%s, useruuid=%s",
                projectDescription.getUuid(), useruuid);
    }


    @Transactional
    public void removeProjectDescriptionUser(String projectdesc_uuid, String useruuid) {
        ProjectDescription projectDescription = ProjectDescription.findById(projectdesc_uuid);
        ProjectDescriptionUser.delete("projectDescription = ?1 and useruuid like ?2", projectDescription, useruuid);
        log.infof("ProjectDescription consultant removed: projectDescUuid=%s, useruuid=%s",
                projectdesc_uuid, useruuid);
    }

    @Transactional
    public void removeProjectDescriptionUsers(String projectdesc_uuid) {
        ProjectDescriptionUser.delete("projectDescription = ?1", ProjectDescription.<ProjectDescription>findById(projectdesc_uuid));
        log.infof("ProjectDescription all consultants removed: projectDescUuid=%s", projectdesc_uuid);
    }

}
