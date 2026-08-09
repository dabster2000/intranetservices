package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.InterviewResource;
import dk.trustworks.intranet.recruitmentservice.model.InterviewResourcePin;
import dk.trustworks.intranet.recruitmentservice.model.enums.InterviewResourceCategory;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The interview resources library: shared interview guides, cases and
 * assessment templates HR curates and every employee can download, plus
 * the per-position pins interviewers see in context.
 * <p>
 * Files live in S3 via {@link RecruitmentS3StorageService}; rows are
 * soft-deleted so old links degrade gracefully. This is internal library
 * content, NOT candidate data — GDPR anonymization never touches it.
 */
@JBossLog
@ApplicationScoped
public class InterviewResourceService {

    /** Upload cap — interview material is documents, not media archives. */
    static final int MAX_FILE_BYTES = 25 * 1024 * 1024;

    @Inject
    RecruitmentS3StorageService storageService;

    // ---- Library ---------------------------------------------------------------

    public List<InterviewResource> listActive() {
        return InterviewResource.list("active", Sort.by("category").and("title"), true);
    }

    @Transactional
    public InterviewResource create(String title, InterviewResourceCategory category,
                                    String description, String filename,
                                    String contentType, byte[] bytes) {
        if (title == null || title.isBlank()) {
            throw badRequest("TITLE_REQUIRED");
        }
        if (bytes == null || bytes.length == 0) {
            throw badRequest("FILE_REQUIRED");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw badRequest("FILE_TOO_LARGE");
        }
        InterviewResource resource = new InterviewResource();
        resource.setTitle(title.trim());
        resource.setCategory(category);
        resource.setDescription(description == null || description.isBlank() ? null : description.trim());
        resource.setOriginalFilename(filename == null || filename.isBlank() ? "resource" : filename.trim());
        resource.setContentType(contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType.trim());
        resource.setFileSize(bytes.length);
        // Persist first so the S3 object can reference the row uuid.
        resource.setFileUuid("pending");
        resource.persist();
        String fileUuid = storageService.storeInterviewResource(
                bytes, resource.getOriginalFilename(), UUID.fromString(resource.getUuid()));
        resource.setFileUuid(fileUuid);
        return resource;
    }

    @Transactional
    public InterviewResource update(String uuid, String title,
                                    InterviewResourceCategory category, String description) {
        InterviewResource resource = requireActive(uuid);
        if (title != null && !title.isBlank()) {
            resource.setTitle(title.trim());
        }
        if (category != null) {
            resource.setCategory(category);
        }
        resource.setDescription(description == null || description.isBlank() ? null : description.trim());
        return resource;
    }

    /** Soft delete — the S3 object stays; stale links 404 via the active check. */
    @Transactional
    public void softDelete(String uuid) {
        InterviewResource resource = requireActive(uuid);
        resource.setActive(false);
    }

    public record Download(InterviewResource resource, byte[] bytes) {
    }

    public Download download(String uuid) {
        InterviewResource resource = requireActive(uuid);
        return new Download(resource, storageService.fetchInterviewResource(resource.getFileUuid()));
    }

    // ---- Position pins ---------------------------------------------------------

    /** The active resources pinned to a position, library order. */
    public List<InterviewResource> pinnedForPosition(String positionUuid) {
        List<String> resourceUuids = InterviewResourcePin
                .<InterviewResourcePin>list("positionUuid", positionUuid).stream()
                .map(InterviewResourcePin::getResourceUuid)
                .toList();
        if (resourceUuids.isEmpty()) {
            return List.of();
        }
        return InterviewResource.list("active = true and uuid in ?1",
                Sort.by("category").and("title"), resourceUuids);
    }

    /**
     * Replace the position's pin set (the position dialog edits pins as a
     * multiselect, so replace-set semantics match the UI exactly).
     */
    @Transactional
    public void replacePins(String positionUuid, Set<String> resourceUuids) {
        long known = resourceUuids.isEmpty() ? 0
                : InterviewResource.count("uuid in ?1 and active = true", resourceUuids);
        if (known != resourceUuids.size()) {
            throw badRequest("UNKNOWN_RESOURCE");
        }
        Set<String> existing = InterviewResourcePin
                .<InterviewResourcePin>list("positionUuid", positionUuid).stream()
                .map(InterviewResourcePin::getResourceUuid)
                .collect(Collectors.toSet());
        InterviewResourcePin.delete("positionUuid = ?1 and resourceUuid not in ?2",
                positionUuid, resourceUuids.isEmpty() ? Set.of("-") : resourceUuids);
        for (String resourceUuid : resourceUuids) {
            if (existing.contains(resourceUuid)) {
                continue;
            }
            InterviewResourcePin pin = new InterviewResourcePin();
            pin.setPositionUuid(positionUuid);
            pin.setResourceUuid(resourceUuid);
            pin.persist();
        }
    }

    // ---- Helpers ---------------------------------------------------------------

    private static InterviewResource requireActive(String uuid) {
        InterviewResource resource = InterviewResource.findById(uuid);
        if (resource == null || !resource.isActive()) {
            throw new NotFoundException();
        }
        return resource;
    }

    private static WebApplicationException badRequest(String code) {
        return new WebApplicationException(Response
                .status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"" + code + "\"}")
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
