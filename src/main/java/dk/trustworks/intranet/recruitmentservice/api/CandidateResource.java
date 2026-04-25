package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.api.dto.*;
import dk.trustworks.intranet.recruitmentservice.application.CandidateService;
import dk.trustworks.intranet.recruitmentservice.application.RecruitmentRecordAccessService;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.enums.CandidateState;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/recruitment/candidates")
@RequestScoped
@Produces("application/json")
@Consumes("application/json")
@RolesAllowed({"recruitment:read"})
public class CandidateResource {

    @Inject CandidateService service;
    @Inject RecruitmentRecordAccessService recordAccess;
    @Inject RequestHeaderHolder header;

    @GET
    public List<CandidateResponse> list(
            @QueryParam("state") CandidateState state,
            @QueryParam("practice") String practice,
            @QueryParam("q") String q,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size) {
        return service.list(state, practice, q, page, size).stream()
                .filter(recordAccess.candidatePredicate(header.getUserUuid()))
                .map(CandidateResponse::from).toList();
    }

    @POST
    @RolesAllowed({"recruitment:write"})
    public Response create(@Valid CandidateCreateRequest req) {
        Candidate c = Candidate.withFreshUuid();
        c.firstName = req.firstName();
        c.lastName = req.lastName();
        c.email = req.email();
        c.phone = req.phone();
        c.currentCompany = req.currentCompany();
        c.desiredPractice = req.desiredPractice();
        c.desiredCareerLevelUuid = req.desiredCareerLevelUuid();
        c.noticePeriodDays = req.noticePeriodDays();
        c.salaryExpectation = req.salaryExpectation();
        c.salaryCurrency = req.salaryCurrency();
        c.locationPreference = req.locationPreference();
        c.linkedinUrl = req.linkedinUrl();
        c.firstContactSource = req.firstContactSource();
        Candidate created = service.create(c, header.getUserUuid());
        return Response.status(201).entity(CandidateResponse.from(created)).build();
    }

    @GET
    @Path("/{uuid}")
    public CandidateResponse find(@PathParam("uuid") String uuid) {
        Candidate c = service.find(uuid);
        if (!recordAccess.canSeeCandidate(c, header.getUserUuid())) {
            throw new NotFoundException("Candidate " + uuid);
        }
        return CandidateResponse.from(c);
    }

    @PATCH
    @Path("/{uuid}")
    @RolesAllowed({"recruitment:write"})
    public CandidateResponse patch(@PathParam("uuid") String uuid, CandidatePatchRequest req) {
        Candidate existing = service.find(uuid);
        if (!recordAccess.canSeeCandidate(existing, header.getUserUuid())) {
            throw new NotFoundException("Candidate " + uuid);
        }
        return CandidateResponse.from(service.patch(uuid, c -> {
            if (req.firstName() != null) c.firstName = req.firstName();
            if (req.lastName() != null) c.lastName = req.lastName();
            if (req.email() != null) c.email = req.email();
            if (req.phone() != null) c.phone = req.phone();
            if (req.currentCompany() != null) c.currentCompany = req.currentCompany();
            if (req.desiredPractice() != null) c.desiredPractice = req.desiredPractice();
            if (req.desiredCareerLevelUuid() != null) c.desiredCareerLevelUuid = req.desiredCareerLevelUuid();
            if (req.noticePeriodDays() != null) c.noticePeriodDays = req.noticePeriodDays();
            if (req.salaryExpectation() != null) c.salaryExpectation = req.salaryExpectation();
            if (req.salaryCurrency() != null) c.salaryCurrency = req.salaryCurrency();
            if (req.locationPreference() != null) c.locationPreference = req.locationPreference();
            if (req.linkedinUrl() != null) c.linkedinUrl = req.linkedinUrl();
        }));
    }

    @POST
    @Path("/{uuid}/notes")
    @RolesAllowed({"recruitment:write"})
    public Response addNote(@PathParam("uuid") String uuid, @Valid CandidateNoteRequest req) {
        Candidate existing = service.find(uuid);
        if (!recordAccess.canSeeCandidate(existing, header.getUserUuid())) {
            throw new NotFoundException("Candidate " + uuid);
        }
        var note = service.addNote(uuid, req.body(), req.visibility(), header.getUserUuid());
        return Response.status(201).entity(CandidateNoteResponse.from(note)).build();
    }
}
