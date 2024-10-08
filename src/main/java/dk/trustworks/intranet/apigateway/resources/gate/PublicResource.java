package dk.trustworks.intranet.apigateway.resources.gate;


import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.apigateway.dto.PublicUser;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.resources.PhotoService;
import dk.trustworks.intranet.newsservice.model.News;
import dk.trustworks.intranet.newsservice.resources.NewsService;
import dk.trustworks.intranet.userservice.model.Employee;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

import java.util.List;
import java.util.stream.Stream;

@JBossLog
@Path("/public")
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"USER", "APPLICATION"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class PublicResource {

    @Inject
    ClientService clientAPI;
    @Inject
    UserService userService;

    @Inject
    NewsService newsService;

    @Inject
    PhotoService photoAPI;

    @GET
    @Path("/clients")
    public List<Client> findAllClients() {
        return clientAPI.listAllClients();
    }

    @GET
    @Path("/users")
    public List<PublicUser> findAllUsers() {
        Stream<Employee> stream = Employee.streamAll();
        return stream.map(employee -> {
            PublicUser publicUser = new PublicUser();
            publicUser.setUuid(employee.getUuid());
            publicUser.setActive(!(employee.getStatus().equals(StatusType.TERMINATED) || employee.getStatus().equals(StatusType.PREBOARDING)));
            publicUser.setType(employee.getConsultanttype());
            publicUser.setFirstName(employee.getFirstname());
            publicUser.setLastName(employee.getLastname());
            return publicUser;
        }).toList();
    }

    @GET
    @Path("/news/{category}")
    public List<News> findAllNews(@PathParam("category") String category) {
        return newsService.getActiveNews(category);
    }

    @GET
    @Path("/files/photos/{relateduuid}")
    public File findPhotoByRelatedUUID(@PathParam("relateduuid") String relateduuid) {
        return photoAPI.findPhotoByRelatedUUID(relateduuid);
    }

    @GET
    @Path("/users/{useruuid}/photo")
    public File findPhotoByUserUUID(@PathParam("useruuid") String useruuid) {
        return photoAPI.findPhotoByRelatedUUID(useruuid);
    }
}
