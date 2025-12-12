package dk.trustworks.intranet.documentservice.resources;

import dk.trustworks.intranet.documentservice.dto.SharePointLocationDTO;
import dk.trustworks.intranet.documentservice.model.SharePointLocationEntity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST resource for shared SharePoint location management.
 * Provides CRUD operations for SharePoint locations that can be referenced
 * by multiple signing store configurations.
 */
@JBossLog
@Path("/sharepoint-locations")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"SYSTEM"})
public class SharePointLocationResource {

    /**
     * Get all SharePoint locations.
     *
     * @param activeOnly If true, return only active locations
     * @return List of locations
     */
    @GET
    public List<SharePointLocationDTO> getAll(@QueryParam("activeOnly") @DefaultValue("false") boolean activeOnly) {
        log.infof("GET /sharepoint-locations?activeOnly=%s", activeOnly);
        List<SharePointLocationEntity> entities = SharePointLocationEntity.findAllLocations(activeOnly);
        return entities.stream().map(this::toDTO).collect(Collectors.toList());
    }

    /**
     * Get a location by UUID.
     *
     * @param uuid The location UUID
     * @return The location DTO
     */
    @GET
    @Path("/{uuid}")
    public SharePointLocationDTO getByUuid(@PathParam("uuid") String uuid) {
        log.infof("GET /sharepoint-locations/%s", uuid);
        SharePointLocationEntity entity = SharePointLocationEntity.findByUuid(uuid);
        if (entity == null) {
            throw new WebApplicationException("SharePoint location not found: " + uuid, Response.Status.NOT_FOUND);
        }
        return toDTO(entity);
    }

    /**
     * Create a new SharePoint location.
     *
     * @param dto Location DTO
     * @return Created location
     */
    @POST
    @Transactional
    public Response create(@Valid SharePointLocationDTO dto) {
        log.infof("POST /sharepoint-locations: %s", dto.getName());

        // Check for duplicate path
        if (SharePointLocationEntity.existsByPath(dto.getSiteUrl(), dto.getDriveName(), dto.getFolderPath())) {
            throw new WebApplicationException(
                    "A location with this path already exists", Response.Status.CONFLICT);
        }

        SharePointLocationEntity entity = toEntity(dto);
        entity.setUuid(UUID.randomUUID().toString());
        entity.persist();

        log.infof("Created SharePoint location: uuid=%s, name=%s", entity.getUuid(), entity.getName());
        return Response.status(Response.Status.CREATED).entity(toDTO(entity)).build();
    }

    /**
     * Update an existing SharePoint location.
     *
     * @param uuid Location UUID
     * @param dto Updated location data
     * @return Updated location
     */
    @PUT
    @Path("/{uuid}")
    @Transactional
    public SharePointLocationDTO update(@PathParam("uuid") String uuid, @Valid SharePointLocationDTO dto) {
        log.infof("PUT /sharepoint-locations/%s: %s", uuid, dto.getName());

        SharePointLocationEntity entity = SharePointLocationEntity.findByUuid(uuid);
        if (entity == null) {
            throw new WebApplicationException("SharePoint location not found: " + uuid, Response.Status.NOT_FOUND);
        }

        // Check for duplicate path (excluding current entity)
        if (SharePointLocationEntity.existsByPathExcludingUuid(
                dto.getSiteUrl(), dto.getDriveName(), dto.getFolderPath(), uuid)) {
            throw new WebApplicationException(
                    "Another location with this path already exists", Response.Status.CONFLICT);
        }

        entity.setName(dto.getName());
        entity.setSiteUrl(dto.getSiteUrl());
        entity.setDriveName(dto.getDriveName());
        entity.setFolderPath(dto.getFolderPath());
        entity.setIsActive(dto.isActive());
        entity.setDisplayOrder(dto.getDisplayOrder());

        log.infof("Updated SharePoint location: uuid=%s, name=%s", uuid, dto.getName());
        return toDTO(entity);
    }

    /**
     * Delete a SharePoint location.
     * Fails if the location is still referenced by signing stores.
     *
     * @param uuid Location UUID
     * @return No content response
     */
    @DELETE
    @Path("/{uuid}")
    @Transactional
    public Response delete(@PathParam("uuid") String uuid) {
        log.infof("DELETE /sharepoint-locations/%s", uuid);

        SharePointLocationEntity entity = SharePointLocationEntity.findByUuid(uuid);
        if (entity == null) {
            throw new WebApplicationException("SharePoint location not found: " + uuid, Response.Status.NOT_FOUND);
        }

        // Check if location is in use
        long referenceCount = SharePointLocationEntity.countReferences(uuid);
        if (referenceCount > 0) {
            throw new WebApplicationException(
                    "Cannot delete location: still referenced by " + referenceCount + " signing store(s)",
                    Response.Status.CONFLICT);
        }

        entity.delete();
        log.infof("Deleted SharePoint location: %s", uuid);
        return Response.noContent().build();
    }

    /**
     * Convert entity to DTO.
     */
    private SharePointLocationDTO toDTO(SharePointLocationEntity entity) {
        return SharePointLocationDTO.builder()
                .uuid(entity.getUuid())
                .name(entity.getName())
                .siteUrl(entity.getSiteUrl())
                .driveName(entity.getDriveName())
                .folderPath(entity.getFolderPath())
                .isActive(entity.getIsActive() != null ? entity.getIsActive() : true)
                .displayOrder(entity.getDisplayOrder() != null ? entity.getDisplayOrder() : 1)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .referenceCount(SharePointLocationEntity.countReferences(entity.getUuid()))
                .build();
    }

    /**
     * Convert DTO to entity.
     */
    private SharePointLocationEntity toEntity(SharePointLocationDTO dto) {
        SharePointLocationEntity entity = new SharePointLocationEntity();
        entity.setName(dto.getName());
        entity.setSiteUrl(dto.getSiteUrl());
        entity.setDriveName(dto.getDriveName());
        entity.setFolderPath(dto.getFolderPath());
        entity.setIsActive(dto.isActive());
        entity.setDisplayOrder(dto.getDisplayOrder());
        return entity;
    }
}
