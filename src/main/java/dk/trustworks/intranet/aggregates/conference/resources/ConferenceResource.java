package dk.trustworks.intranet.aggregates.conference.resources;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.conference.events.ChangeParticipantPhaseEvent;
import dk.trustworks.intranet.aggregates.conference.events.CreateParticipantEvent;
import dk.trustworks.intranet.aggregates.conference.events.UpdateParticipantDataEvent;
import dk.trustworks.intranet.aggregates.conference.services.ConferenceService;
import dk.trustworks.intranet.communicationsservice.model.*;
import dk.trustworks.intranet.communicationsservice.resources.MailResource;
import dk.trustworks.intranet.communicationsservice.services.BulkEmailService;
import dk.trustworks.intranet.knowledgeservice.model.Conference;
import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.knowledgeservice.model.ConferencePhase;
import dk.trustworks.intranet.knowledgeservice.model.ConferencePhaseAttachment;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@JBossLog
@Tag(name = "Conference")
@Path("/knowledge/conferences")
@RequestScoped
public class ConferenceResource {

    // Email attachment validation constants
    private static final int MAX_ATTACHMENT_COUNT = 10;
    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
    private static final long MAX_TOTAL_SIZE_BYTES = 25 * 1024 * 1024; // 25 MB
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "image/jpeg",
        "image/png",
        "image/gif"
    );

    @Inject
    AggregateEventSender aggregateEventSender;

    @Inject
    ConferenceService conferenceService;

    @Inject
    MailResource mailResource;

    @Inject
    BulkEmailService bulkEmailService;

    @GET
    public List<Conference> findAllConferences() {
        return conferenceService.findAllConferences();
    }

    @GET
    @Path("/slug/{slug}")
    public Conference findConferenceBySlug(@PathParam("slug") String slug) {
        return conferenceService.findConferenceBySlug(slug);
    }

    @POST
    public void createConference(Conference conference) {
        conferenceService.createConference(conference);
    }

    @GET
    @Path("/{conferenceuuid}/participants")
    public List<ConferenceParticipant> findAllConferenceParticipants(@PathParam("conferenceuuid") String conferenceuuid) {
        System.out.println("ConferenceResource.findAllConferenceParticipants");
        System.out.println("conferenceuuid = " + conferenceuuid);
        return conferenceService.findAllConferenceParticipants(conferenceuuid);
    }

    @GET
    @Path("/{conferenceuuid}/phases")
    public List<ConferencePhase> findAllConferencePhases(@PathParam("conferenceuuid") String conferenceuuid) {
        List<ConferencePhase> list = ConferencePhase.list("conferenceuuid", conferenceuuid);
        return list.stream().sorted(Comparator.comparing(ConferencePhase::getStep)).toList();
    }

    @POST
    @Path("/{conferenceuuid}/phases")
    @Transactional
    public void addConferencePhase(@PathParam("conferenceuuid") String conferenceuuid, ConferencePhase conferencePhase) {
        System.out.println("ConferenceResource.addConferencePhase");
        System.out.println("conferenceuuid = " + conferenceuuid + ", conferencePhase = " + conferencePhase);
        if(conferencePhase.getUuid()==null) throw new IllegalArgumentException("ConferencePhase must have a uuid");
        ConferencePhase.findByIdOptional(conferencePhase.getUuid()).ifPresentOrElse(cp -> updatePhase(conferencePhase), conferencePhase::persist);
    }

    private void updatePhase(ConferencePhase conferencePhase) {
        System.out.println("ConferenceResource.updatePhase");
        ConferencePhase.update("step = ?1, name = ?2, useMail = ?3, subject = ?4, mail = ?5 where uuid = ?6",
                conferencePhase.getStep(), conferencePhase.getName(), conferencePhase.isUseMail(), conferencePhase.getSubject(), conferencePhase.getMail(), conferencePhase.getUuid());
    }

    @DELETE
    @Path("/{conferenceuuid}/phases/{phaseuuid}")
    @Transactional
    public void deleteConferencePhase(@PathParam("conferenceuuid") String conferenceuuid, @PathParam("phaseuuid") String phaseuuid) {
        ConferencePhase.delete("uuid", phaseuuid);
    }

    @GET
    @Path("/{conferenceuuid}/phases/{phaseuuid}/attachments")
    @Operation(
        summary = "Get all attachments for a conference phase",
        description = "Returns all file attachments associated with a specific conference phase"
    )
    public List<ConferencePhaseAttachment> getPhaseAttachments(@PathParam("conferenceuuid") String conferenceuuid, @PathParam("phaseuuid") String phaseuuid) {
        return ConferencePhaseAttachment.list("phaseuuid", phaseuuid);
    }

    @POST
    @Path("/{conferenceuuid}/phases/{phaseuuid}/attachments")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Operation(
        summary = "Add attachment to conference phase",
        description = "Uploads a file attachment to be sent with emails when participants transition to this phase. " +
                     "Same validation rules as bulk email attachments apply."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Attachment added successfully"
        ),
        @APIResponse(
            responseCode = "400",
            description = "Invalid request (bad file type, file too large, etc.)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = EmailErrorResponse.class)
            )
        )
    })
    public Response addPhaseAttachment(
            @PathParam("conferenceuuid") String conferenceuuid,
            @PathParam("phaseuuid") String phaseuuid,
            EmailAttachment attachment) {

        log.info("Adding attachment to phase " + phaseuuid + ": " + attachment.getFilename());

        // Validate attachment (reuse existing validation logic)
        try {
            validateAttachments(List.of(attachment));
        } catch (WebApplicationException e) {
            throw e;
        }

        // Create and persist the phase attachment
        ConferencePhaseAttachment phaseAttachment = new ConferencePhaseAttachment(
                phaseuuid,
                attachment.getFilename(),
                attachment.getContentType(),
                attachment.getContent()
        );
        phaseAttachment.persist();

        log.info("Phase attachment added: id=" + phaseAttachment.getId() + ", filename=" + attachment.getFilename());
        return Response.ok().entity(phaseAttachment).build();
    }

    @DELETE
    @Path("/{conferenceuuid}/phases/{phaseuuid}/attachments/{attachmentid}")
    @Transactional
    @Operation(
        summary = "Delete phase attachment",
        description = "Removes a file attachment from a conference phase"
    )
    public Response deletePhaseAttachment(
            @PathParam("conferenceuuid") String conferenceuuid,
            @PathParam("phaseuuid") String phaseuuid,
            @PathParam("attachmentid") Long attachmentId) {

        log.info("Deleting phase attachment: id=" + attachmentId);

        boolean deleted = ConferencePhaseAttachment.deleteById(attachmentId);

        if (deleted) {
            log.info("Phase attachment deleted: id=" + attachmentId);
            return Response.ok().build();
        } else {
            log.warn("Phase attachment not found: id=" + attachmentId);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new EmailErrorResponse("Not found", "Attachment with id " + attachmentId + " not found"))
                    .build();
        }
    }

    @POST
    @PermitAll
    @Path("/{conferenceuuid}/participants")
    public void createParticipant(@PathParam("conferenceuuid") String conferenceUUID, ConferenceParticipant conferenceParticipant) {
        createParticipant(conferenceUUID, 0, conferenceParticipant);
    }

    @POST
    @PermitAll
    @Path("/{conferenceuuid}/phase/{phasenumber}/participants")
    public void createParticipant(@PathParam("conferenceuuid") String conferenceUUID, @PathParam("phasenumber") int phaseNumber, ConferenceParticipant conferenceParticipant) {
        System.out.println("ConferenceResource.createParticipant");
        System.out.println("conferenceUUID = " + conferenceUUID + ", phaseNumber = " + phaseNumber + ", conferenceParticipant = " + conferenceParticipant);
        conferenceParticipant.setRegistered(LocalDateTime.now());
        conferenceParticipant.setUuid(UUID.randomUUID().toString());
        conferenceParticipant.setConferenceuuid(conferenceUUID);
        conferenceParticipant.setParticipantuuid(UUID.randomUUID().toString());

        conferenceParticipant.setConferencePhase(conferenceService.findConferencePhase(conferenceUUID, phaseNumber));

        CreateParticipantEvent event = new CreateParticipantEvent(conferenceUUID, conferenceParticipant);
        aggregateEventSender.handleEvent(event);
    }

    @PUT
    @Path("/{conferenceuuid}/participants")
    public void updateParticipantData(@PathParam("conferenceuuid") String conferenceUUID, ConferenceParticipant conferenceParticipant) {
        conferenceParticipant.setRegistered(LocalDateTime.now());
        conferenceParticipant.setUuid(UUID.randomUUID().toString());
        conferenceParticipant.setConferenceuuid(conferenceUUID);
        UpdateParticipantDataEvent event = new UpdateParticipantDataEvent(conferenceUUID, conferenceParticipant);
        aggregateEventSender.handleEvent(event);
    }

    @POST
    @Path("/{conferenceuuid}/phase/{phasenumber}/participants/list")
    public void changeParticipantPhase(@PathParam("conferenceuuid") String conferenceUUID, @PathParam("phasenumber") int phaseNumber, List<ConferenceParticipant> conferenceParticipantList) {
        // Fetch the target phase to check for email and attachments
        ConferencePhase targetPhase = conferenceService.findConferencePhase(conferenceUUID, phaseNumber);

        // If phase uses email and has attachments, create a bulk email job for all participants
        if (targetPhase.isUseMail() && targetPhase.hasAttachments()) {
            log.info("Phase " + phaseNumber + " has attachments, creating bulk email job for " + conferenceParticipantList.size() + " participants");

            // Collect recipient emails
            List<String> recipientEmails = conferenceParticipantList.stream()
                    .map(ConferenceParticipant::getEmail)
                    .toList();

            // Convert phase attachments to email attachments
            List<EmailAttachment> emailAttachments = targetPhase.getAttachments().stream()
                    .map(ConferencePhaseAttachment::toEmailAttachment)
                    .toList();

            // Decode the Base64-encoded mail body
            String decodedBody = new String(org.apache.commons.codec.binary.Base64.decodeBase64(targetPhase.getMail().getBytes()));

            // Create bulk email request
            BulkEmailRequest bulkEmailRequest = new BulkEmailRequest(
                    targetPhase.getSubject(),
                    decodedBody,
                    recipientEmails,
                    emailAttachments
            );

            // Create the bulk email job
            bulkEmailService.createBulkEmailJob(bulkEmailRequest);
            log.info("Bulk email job created for phase transition with " + emailAttachments.size() + " attachments");
        }

        // Update each participant's phase in the database (email already sent or will be sent individually)
        conferenceParticipantList.forEach(conferenceParticipant -> {
            conferenceParticipant.setRegistered(LocalDateTime.now());
            conferenceParticipant.setUuid(UUID.randomUUID().toString());
            conferenceParticipant.setConferenceuuid(conferenceUUID);
            conferenceParticipant.setConferencePhase(targetPhase);
            ChangeParticipantPhaseEvent event = new ChangeParticipantPhaseEvent(conferenceUUID, conferenceParticipant);
            aggregateEventSender.handleEvent(event);
        });
    }

    @POST
    @Path("/apply/forefront2024")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public void receiveForm2(@FormParam("name") String name, @FormParam("company") String company, @FormParam("titel") String titel,
                            @FormParam("email") String email, @FormParam("andet") String andet, @FormParam("samtykke[0]") String samtykke) {
        createParticipant("04e9bd12-4800-4780-ada9-dfe8ddd05a9d", new ConferenceParticipant(name, company, titel, email, andet, "ja".equals(samtykke)));
    }

    @POST
    @Path("/apply/forefront2025/{phaseNumber}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public void receiveForm3(@PathParam("phaseNumber") String phaseNumber, @FormParam("name") String name, @FormParam("company") String company, @FormParam("titel") String titel,
                             @FormParam("email") String email, @FormParam("andet") String andet, @FormParam("samtykke[0]") String samtykke) {
        if(phaseNumber == null) createParticipant("ebe8e716-7c1e-42bc-aaf0-43fd03ed99c4", 0, new ConferenceParticipant(name, company, titel, email, andet, "ja".equals(samtykke)));
        else createParticipant("ebe8e716-7c1e-42bc-aaf0-43fd03ed99c4", Integer.parseInt(phaseNumber), new ConferenceParticipant(name, company, titel, email, andet, "ja".equals(samtykke)));
    }

    @POST
    @Path("/{conferenceuuid}/apply")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public void receiveForm(@PathParam("conferenceuuid") String conferenceuuid, @FormParam("name") String name, @FormParam("company") String company, @FormParam("titel") String titel,
                            @FormParam("email") String email, @FormParam("andet") String andet, @FormParam("samtykke[0]") String samtykke) {
        createParticipant(conferenceuuid, new ConferenceParticipant(name, company, titel, email, andet, "ja".equals(samtykke)));
    }

    @POST
    @Path("/message")
    @Operation(
        summary = "Send email to conference participant",
        description = "Sends an email with optional file attachments to a conference participant. " +
                     "Emails without attachments are queued for batch processing. " +
                     "Emails with attachments are sent immediately."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Email sent successfully or queued for sending"
        ),
        @APIResponse(
            responseCode = "400",
            description = "Invalid request (bad file type, too many files, etc.)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = EmailErrorResponse.class),
                examples = @ExampleObject(
                    name = "Invalid file type",
                    value = "{\"error\": \"Invalid file type\", \"message\": \"File 'malicious.exe' has unsupported type. Allowed types: pdf, doc, docx, xls, xlsx, ppt, pptx, jpg, jpeg, png, gif\"}"
                )
            )
        ),
        @APIResponse(
            responseCode = "413",
            description = "Payload too large (file size exceeded)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = EmailErrorResponse.class),
                examples = @ExampleObject(
                    name = "Size exceeded",
                    value = "{\"error\": \"Payload too large\", \"message\": \"Total attachment size (30.00 MB) exceeds maximum allowed (25 MB)\"}"
                )
            )
        ),
        @APIResponse(
            responseCode = "500",
            description = "Email send failed",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = EmailErrorResponse.class),
                examples = @ExampleObject(
                    name = "Send failure",
                    value = "{\"error\": \"Email send failed\", \"message\": \"Failed to send email to participant@example.com\"}"
                )
            )
        )
    })
    @RequestBody(
        required = true,
        content = @Content(
            mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = TrustworksMail.class),
            examples = {
                @ExampleObject(
                    name = "With attachments",
                    value = "{\"uuid\": \"123e4567-e89b-12d3-a456-426614174000\", \"to\": \"participant@example.com\", \"subject\": \"Conference Agenda\", \"body\": \"<html><body>Please find the agenda attached.</body></html>\", \"attachments\": [{\"filename\": \"agenda.pdf\", \"contentType\": \"application/pdf\", \"content\": \"JVBERi0xLjQK...\"}]}"
                ),
                @ExampleObject(
                    name = "Without attachments",
                    value = "{\"uuid\": \"123e4567-e89b-12d3-a456-426614174000\", \"to\": \"participant@example.com\", \"subject\": \"Conference Information\", \"body\": \"<html><body>Hello!</body></html>\"}"
                )
            }
        )
    )
    public Response message(TrustworksMail mail) {
        log.info("ConferenceResource.message - Processing email to: " + mail.getTo());

        // If no attachments, use existing deferred send mechanism
        if (!mail.hasAttachments()) {
            log.info("No attachments, using deferred send");
            mailResource.sendingHTML(mail);
            return Response.ok().build();
        }

        // Validate attachments
        try {
            validateAttachments(mail.getAttachments());
        } catch (WebApplicationException e) {
            throw e; // Re-throw JAX-RS exceptions directly
        }

        // Send immediately with attachments
        try {
            mailResource.sendWithAttachments(mail);
            return Response.ok().build();
        } catch (Exception e) {
            log.error("Failed to send email with attachments", e);
            EmailErrorResponse error = new EmailErrorResponse(
                "Email send failed",
                "Failed to send email to " + mail.getTo()
            );
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(error)
                    .build();
        }
    }

    /**
     * Validate email attachments according to business rules
     */
    private void validateAttachments(List<EmailAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }

        // Validate attachment count
        if (attachments.size() > MAX_ATTACHMENT_COUNT) {
            EmailErrorResponse error = new EmailErrorResponse(
                "Too many attachments",
                String.format("Maximum %d attachments allowed, got %d", MAX_ATTACHMENT_COUNT, attachments.size())
            );
            throw new WebApplicationException(
                Response.status(Response.Status.BAD_REQUEST).entity(error).build()
            );
        }

        // Validate individual file sizes and MIME types
        for (EmailAttachment attachment : attachments) {
            long fileSize = attachment.getSize();

            if (fileSize > MAX_FILE_SIZE_BYTES) {
                EmailErrorResponse error = new EmailErrorResponse(
                    "Payload too large",
                    String.format("File '%s' exceeds maximum size of %d MB (size: %.2f MB)",
                        attachment.getFilename(),
                        MAX_FILE_SIZE_BYTES / (1024 * 1024),
                        fileSize / (1024.0 * 1024.0))
                );
                throw new WebApplicationException(
                    Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE).entity(error).build()
                );
            }

            if (!ALLOWED_MIME_TYPES.contains(attachment.getContentType())) {
                String allowedTypes = "pdf, doc, docx, xls, xlsx, ppt, pptx, jpg, jpeg, png, gif";
                EmailErrorResponse error = new EmailErrorResponse(
                    "Invalid file type",
                    String.format("File '%s' has unsupported type. Allowed types: %s",
                        attachment.getFilename(), allowedTypes)
                );
                throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST).entity(error).build()
                );
            }
        }

        // Validate total size
        long totalSize = attachments.stream()
                .mapToLong(EmailAttachment::getSize)
                .sum();

        if (totalSize > MAX_TOTAL_SIZE_BYTES) {
            EmailErrorResponse error = new EmailErrorResponse(
                "Payload too large",
                String.format("Total attachment size (%.2f MB) exceeds maximum allowed (%d MB)",
                    totalSize / (1024.0 * 1024.0),
                    MAX_TOTAL_SIZE_BYTES / (1024 * 1024))
            );
            throw new WebApplicationException(
                Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE).entity(error).build()
            );
        }
    }

    @POST
    @Path("/bulk-message")
    @Operation(
        summary = "Send bulk email to multiple conference participants",
        description = "Sends the same email with optional attachments to multiple recipients. " +
                     "Emails are queued and sent asynchronously by a batch job with 5-second throttling " +
                     "between each send. Maximum 1000 recipients per request."
    )
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Bulk email job created successfully",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                examples = @ExampleObject(
                    value = "{\"jobId\": \"123e4567-e89b-12d3-a456-426614174000\", \"recipientCount\": 50, \"status\": \"PENDING\"}"
                )
            )
        ),
        @APIResponse(
            responseCode = "400",
            description = "Invalid request (bad file type, too many files, too many recipients, etc.)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = EmailErrorResponse.class)
            )
        ),
        @APIResponse(
            responseCode = "413",
            description = "Payload too large (file size exceeded)",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = EmailErrorResponse.class)
            )
        )
    })
    @RequestBody(
        required = true,
        content = @Content(
            mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = BulkEmailRequest.class),
            examples = @ExampleObject(
                value = "{\"subject\": \"Conference Update\", \"body\": \"<html><body>Dear participants...</body></html>\", \"recipients\": [\"participant1@example.com\", \"participant2@example.com\"], \"attachments\": [{\"filename\": \"agenda.pdf\", \"contentType\": \"application/pdf\", \"content\": \"JVBERi0xLjQK...\"}]}"
            )
        )
    )
    public Response bulkMessage(BulkEmailRequest request) {
        log.info("ConferenceResource.bulkMessage - Processing bulk email: subject='" +
                 request.getSubject() + "', recipients=" + request.getRecipients().size());

        // Validate attachments (same rules as single email)
        if (request.hasAttachments()) {
            try {
                validateAttachments(request.getAttachments());
            } catch (WebApplicationException e) {
                throw e;
            }
        }

        // Create bulk email job
        try {
            BulkEmailJob job = bulkEmailService.createBulkEmailJob(request);

            // Return job information
            Map<String, Object> response = new HashMap<>();
            response.put("jobId", job.getUuid());
            response.put("recipientCount", job.getTotalRecipients());
            response.put("status", job.getStatus().toString());
            response.put("message", "Bulk email job created successfully. Emails will be sent asynchronously.");

            return Response.ok(response).build();

        } catch (Exception e) {
            log.error("Failed to create bulk email job", e);
            EmailErrorResponse error = new EmailErrorResponse(
                "Bulk email creation failed",
                "Failed to create bulk email job: " + e.getMessage()
            );
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(error)
                    .build();
        }
    }
}