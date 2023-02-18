package dk.trustworks.intranet.knowledgeservice.services;

import dk.trustworks.intranet.knowledgeservice.model.ProjectDescription;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.GET;
import java.util.List;

@JBossLog
@ApplicationScoped
public class ProjectDescriptionService {

    @GET
    public List<ProjectDescription> findAll() {
        return ProjectDescription.findAll().list();
    }

}
