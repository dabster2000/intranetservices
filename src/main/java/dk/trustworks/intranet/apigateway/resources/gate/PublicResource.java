package dk.trustworks.intranet.apigateway.resources.gate;

import dk.trustworks.intranet.apigateway.dto.PublicUser;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.model.Task;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.dao.crm.services.TaskService;
import dk.trustworks.intranet.dao.workservice.model.Week;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WeekService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.resources.PhotoService;
import dk.trustworks.intranet.newsservice.model.News;
import dk.trustworks.intranet.newsservice.resources.NewsService;
import dk.trustworks.intranet.sales.model.SalesCoffeeDate;
import dk.trustworks.intranet.userservice.model.Employee;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Base64;
import java.util.stream.Stream;

import jakarta.ws.rs.core.Response;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static dk.trustworks.intranet.utils.DateUtils.stringIt;

@JBossLog
@Path("/public")
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"public:read"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class PublicResource {

    @Inject
    ClientService clientAPI;

    @Inject
    NewsService newsService;

    @Inject
    PhotoService photoAPI;

    @Inject
    WeekService weekService;

    @Inject
    TaskService taskService;

    @Inject
    ProjectService projectService;

    @Inject
    WorkService workService;

    @GET
    @Path("/clients")
    public List<Client> findAllClients() {
        return clientAPI.listAllClients();
    }

    @GET
    @Path("/users")
    public List<PublicUser> findAllUsers() {
        Stream<Employee> stream = Employee.stream(
                "status not in (?1, ?2) and (consultanttype is null or consultanttype <> ?3)",
                StatusType.TERMINATED, StatusType.PREBOARDING, ConsultantType.EXTERNAL
        );
        return stream.map(PublicUser::new).toList();
    }

    @GET
    @Path("/users/{useruuid}")
    public PublicUser findByUseruuid(@PathParam("useruuid") String useruuid) {
        Employee employee = Employee.findById(useruuid);
        if (employee == null) return null;
        return new PublicUser(employee);
    }

    @GET
    @Path("/users/{useruuid}/weeks/{year}/{weeknumber}")
    public List<Week> findByWeeknumberAndYearAndUseruuidOrderBySortingAsc(@PathParam("useruuid") String useruuid, @PathParam("weeknumber") String strWeeknumber, @PathParam("year") String strYear) {
        int year = Integer.parseInt(strYear);
        int weeknumber = Integer.parseInt(strWeeknumber);
        return weekService.findByWeeknumberAndYearAndUseruuidOrderBySortingAsc(weeknumber, year, useruuid);
    }

    @GET
    @Path("/news/{category}")
    public List<News> findAllNews(@PathParam("category") String category) {
        return newsService.getActiveNews(category);
    }

    @GET
    @Path("/files/photos/{relateduuid}")
    public File findPhotoByRelatedUUID(@PathParam("relateduuid") String relateduuid,
                                       @QueryParam("width") Integer width) {
        log.debug("Public photo request " + relateduuid + (width != null ? " width=" + width : ""));
        File photo = photoAPI.findPhotoByRelatedUUID(relateduuid);
        if (width != null) {
            photo.setFile(photoAPI.findPhotoByRelatedUUID(relateduuid).getFile());
        }
        return photo;
    }

    /** {@code height} opts into a centre-cropped square thumbnail — see {@code FileResource}. */
    @GET
    @Path("/users/{useruuid}/photo")
    public File findPhotoByUserUUID(@PathParam("useruuid") String useruuid,
                                    @QueryParam("width") Integer width,
                                    @QueryParam("height") Integer height) {
        log.debug("Public user photo request " + useruuid + (width != null ? " width=" + width : ""));
        File photo = photoAPI.findPhotoByRelatedUUID(useruuid);
        if (width != null) {
            photo.setFile(photoAPI.getResizedPhoto(useruuid, width, height == null ? 0 : height));
        }
        return photo;
    }

    @GET
    @Path("/tasks/{uuid}")
    public Task findByTaskuuid(@PathParam("uuid") String uuid) {
        return taskService.findByUuid(uuid);
    }

    @GET
    @Path("/projects/{uuid}")
    @SecurityRequirement(name = "jwt", scopes = {})
    public Project findByProjectuuid(@PathParam("uuid") String uuid) {
        return projectService.findByUuid(uuid);
    }

    @GET
    @Path("/users/{uuid}/work")
    public List<WorkFull> getUserWorkByPeriod(@PathParam("uuid") String useruuid, @QueryParam("fromdate") Optional<String> fromDate, @QueryParam("todate") Optional<String> toDate) {
        return workService.findByPeriodAndUserUUID(dateIt(fromDate.orElse("2014-02-01")), dateIt(toDate.orElse(stringIt(LocalDate.now()))), useruuid);
    }

    @GET
    @Path("/coffeedates")
    public List<SalesCoffeeDate> getCoffeeDates() {
        List<SalesCoffeeDate> coffeeDateList = SalesCoffeeDate.<SalesCoffeeDate>listAll();
        coffeeDateList.forEach(coffeeDate -> {
            coffeeDate.addPublicUser(Employee.findById(coffeeDate.getUseruuid()));
        });
        return coffeeDateList;
    }

    @PUT
    @Path("/client/{clientuuid}")
    @Consumes(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
    public Response updateClientLogo(@PathParam("clientuuid") String clientuuid, UpdateClientLogoRequest request) {
        if (request == null || request.getFile() == null || request.getFile().isBlank()) {
            throw new WebApplicationException("file is required", Response.Status.BAD_REQUEST);
        }

        Client client = clientAPI.findByUuid(clientuuid);
        if (client == null) {
            throw new WebApplicationException("client not found", Response.Status.NOT_FOUND);
        }

        if (!"EXTERNAL".equalsIgnoreCase(client.getManaged())) {
            throw new WebApplicationException("client is not external", Response.Status.FORBIDDEN);
        }

        storeLogo(client, decodeLogo(request.getFile()));

        return Response.noContent().build();
    }

    @POST
    @Path("/client")
    @Consumes(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
    @Produces(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
    public Client createClient(CreateClientRequest request) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            throw new WebApplicationException("name is required", Response.Status.BAD_REQUEST);
        }

        // Decode and vet the logo before anything is persisted. clientAPI.save() commits in its own
        // transaction (this method has no @Transactional of its own), so rejecting the logo after
        // it would leave a client behind — and the dedup lookup below then returns that client
        // early on the corrected retry, without ever reaching the upload. The caller would be left
        // unable to attach a logo through this endpoint at all.
        byte[] decodedLogo = (request.getFile() != null && !request.getFile().isBlank())
                ? decodeLogo(request.getFile())
                : null;

        // Dedup: return existing client if name matches (exact or fuzzy)
        Optional<Client> existingMatch = clientAPI.findFuzzyMatch(request.getName());
        if (existingMatch.isPresent()) {
            log.infof("Client dedup: returning existing client uuid=%s for requested name='%s'",
                    existingMatch.get().getUuid(), request.getName());
            return existingMatch.get();
        }

        Client client = new Client();
        client.setContactname("");
        client.setCreated(LocalDateTime.now());
        client.setName(request.getName());
        client.setAccountmanager(null);
        client.setCrmid(null);
        client.setSegment(ClientSegment.OTHER);
        client.setManaged("EXTERNAL");

        clientAPI.save(client);

        if (decodedLogo != null) {
            storeLogo(client, decodedLogo);
        }

        return client;
    }

    /**
     * Decodes a base64 logo and rejects anything that is not a storable raster image.
     * <p>
     * {@code PhotoService.update} enforces the same allowlist — it is the chokepoint that actually
     * guarantees it — but doing it here as well keeps the rejection ahead of the client insert in
     * {@link #createClient}, and returns the caller a 400 that names the offending format rather
     * than one raised from inside the photo service.
     */
    private byte[] decodeLogo(String base64) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("file must be valid base64", Response.Status.BAD_REQUEST);
        }

        String mimeType = photoAPI.detectMimeType(decoded);
        if (!PhotoService.isStorableImageType(mimeType)) {
            throw new WebApplicationException("Unsupported image format: " + mimeType,
                    Response.Status.BAD_REQUEST);
        }
        return decoded;
    }

    private void storeLogo(Client client, byte[] decoded) {
        String extension = photoAPI.extensionFromMimeType(photoAPI.detectMimeType(decoded));

        File logo = new File();
        logo.setUuid("");
        logo.setRelateduuid(client.getUuid());
        logo.setType("PHOTO");
        logo.setName(client.getName());
        logo.setFilename(sanitizeFilename(client.getName()) + extension);
        logo.setUploaddate(LocalDate.now());
        logo.setFile(decoded);

        photoAPI.updateLogo(logo);
    }

    static String sanitizeFilename(String name) {
        String base = (name == null || name.isBlank()) ? "client" : name;
        String sanitized = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitized.isBlank()) {
            sanitized = "client";
        }
        return sanitized;
    }

    @Setter
    @Getter
    public static class CreateClientRequest {
        private String name;
        private String file;

    }

    @Setter
    @Getter
    public static class UpdateClientLogoRequest {
        private String file;

    }
}
