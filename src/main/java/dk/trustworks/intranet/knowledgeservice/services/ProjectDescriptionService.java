package dk.trustworks.intranet.knowledgeservice.services;

import dk.trustworks.intranet.knowledgeservice.model.ProjectDescription;
import dk.trustworks.intranet.knowledgeservice.model.ProjectDescriptionUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Optional;

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
        projectDescription.persist();
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

            // Handle projectDescriptionUserList carefully for add/update/delete propagation
            List<ProjectDescriptionUser> incomingUsers = updatedProjectDescription.getProjectDescriptionUserList();

            // Step 1: Remove orphans (existing children that are not in the incoming list)
            existingProjectDescription.getProjectDescriptionUserList().removeIf(existingUser ->
                    incomingUsers.stream().noneMatch(incomingUser -> incomingUser.getId().equals(existingUser.getId()))
            );

            // Step 2: Add or update children
            for (ProjectDescriptionUser incomingUser : incomingUsers) {
                if (incomingUser.getId() == null) {
                    // New child, persist with the parent
                    existingProjectDescription.getProjectDescriptionUserList().add(incomingUser);
                } else {
                    // Existing child, find and update
                    ProjectDescriptionUser existingUser = existingProjectDescription.getProjectDescriptionUserList().stream()
                            .filter(u -> u.getId().equals(incomingUser.getId()))
                            .findFirst()
                            .orElse(null);

                    if (existingUser != null) {
                        // Update existing user fields
                        existingUser.setUseruuid(incomingUser.getUseruuid());
                    } else {
                        // If not found, add as new
                        existingProjectDescription.getProjectDescriptionUserList().add(incomingUser);
                    }
                }
            }
        }
        return existingProjectDescription;
    }


    @Transactional
    public boolean delete(String uuid) {
        return ProjectDescription.deleteById(uuid);
    }

}
