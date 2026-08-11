package dk.trustworks.intranet.recruitmentservice.airtable;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.model.Practice;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Admin-only surface of the P21 Airtable migration tooling. No feature
 * flag — the endpoints require the {@code recruitment:admin} scope (the
 * maintenance scope, same posture as the P20 projection rebuild), and the
 * tool is inert until {@code recruitment.airtable.token} / {@code base-id}
 * are configured in the environment.
 *
 * <h3>Intended sequence (runbook)</h3>
 * <ol>
 *   <li>{@code POST /mappings} until {@code POST /dry-run} reports zero
 *       unmapped faglighed/pipeline values and zero unknown statuses.</li>
 *   <li>Recruiter signs off the dry-run counts (plan §P21 DoD).</li>
 *   <li>Freeze Airtable edits, then {@code POST /import}; poll
 *       {@code GET /runs/&#123;uuid&#125;} — ledger counts are live.</li>
 * </ol>
 */
@JBossLog
@Path("/recruitment/airtable")
@RequestScoped
@RolesAllowed({"recruitment:admin"})
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AirtableImportResource {

    @Inject
    AirtableImportService importService;

    @Inject
    AirtableExportService exportService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    // Practice mapping config (spec §10: Airtable value → practice uuid)
    // ------------------------------------------------------------------

    public record MappingRow(String uuid, String airtableValue, String practiceUuid,
                             String practiceName) {
    }

    public record MappingRequest(String airtableValue, String practiceUuid) {
    }

    @GET
    @Path("/mappings")
    public List<MappingRow> listMappings() {
        List<AirtablePracticeMapping> rows = AirtablePracticeMapping.listAll();
        return rows.stream()
                .map(row -> new MappingRow(row.getUuid(), row.getAirtableValue(),
                        row.getPracticeUuid(), practiceName(row.getPracticeUuid())))
                .toList();
    }

    /** Upsert by (normalized) Airtable value. */
    @POST
    @Path("/mappings")
    @Transactional
    public MappingRow upsertMapping(MappingRequest request) {
        if (request == null || request.airtableValue() == null || request.airtableValue().isBlank()) {
            throw new BadRequestException("airtableValue is required");
        }
        if (request.practiceUuid() == null || request.practiceUuid().isBlank()) {
            throw new BadRequestException("practiceUuid is required");
        }
        // Practice's JPA @Id is `code`; uuid is the canonical UNIQUE attribute
        // (Part 2 Phase 1) — findById would silently look up by code.
        Practice practice = Practice.<Practice>find("uuid", request.practiceUuid().trim()).firstResult();
        if (practice == null) {
            throw new BadRequestException("Unknown practice uuid: " + request.practiceUuid());
        }
        String normalized = AirtablePracticeMapping.normalize(request.airtableValue());
        AirtablePracticeMapping existing = AirtablePracticeMapping
                .<AirtablePracticeMapping>listAll().stream()
                .filter(row -> normalized.equals(AirtablePracticeMapping.normalize(row.getAirtableValue())))
                .findFirst().orElse(null);
        AirtablePracticeMapping row = existing != null ? existing : new AirtablePracticeMapping();
        row.setAirtableValue(request.airtableValue().trim());
        row.setPracticeUuid(practice.getUuid());
        if (existing == null) {
            row.persist();
        }
        return new MappingRow(row.getUuid(), row.getAirtableValue(), row.getPracticeUuid(),
                practice.getName());
    }

    @DELETE
    @Path("/mappings/{uuid}")
    @Transactional
    public void deleteMapping(@PathParam("uuid") String uuid) {
        AirtablePracticeMapping row = AirtablePracticeMapping.findById(uuid);
        if (row == null) {
            throw new NotFoundException();
        }
        row.delete();
    }

    // ------------------------------------------------------------------
    // Dry-run / import
    // ------------------------------------------------------------------

    @POST
    @Path("/dry-run")
    public AirtableReconciliationReport dryRun() {
        requireConfigured();
        return importService.dryRun(requestHeaderHolder.getUserUuid());
    }

    /**
     * @param recordId     optional Airtable record id — imports ONLY that
     *                     record (the runbook's one-candidate spot check).
     * @param excludeHired when true, HIRED records are left for a later
     *                     round (no ledger rows written for them).
     */
    @POST
    @Path("/import")
    public AirtableImportService.ImportStart startImport(
            @jakarta.ws.rs.QueryParam("recordId") String recordId,
            @jakarta.ws.rs.QueryParam("excludeHired") boolean excludeHired) {
        requireConfigured();
        try {
            return importService.startImport(requestHeaderHolder.getUserUuid(), recordId, excludeHired);
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Runs
    // ------------------------------------------------------------------

    public record RunRow(String uuid, String mode, String status, LocalDateTime startedAt,
                         LocalDateTime finishedAt, String startedBy, String error) {
    }

    public record RunDetail(RunRow run, AirtableReconciliationReport report,
                            Map<String, Long> ledgerCounts) {
    }

    @GET
    @Path("/runs")
    public List<RunRow> listRuns() {
        List<AirtableImportRun> runs = AirtableImportRun
                .<AirtableImportRun>find("ORDER BY startedAt DESC").page(0, 20).list();
        return runs.stream().map(AirtableImportResource::toRow).toList();
    }

    @GET
    @Path("/runs/{uuid}")
    public RunDetail getRun(@PathParam("uuid") String uuid) {
        AirtableImportRun run = AirtableImportRun.findById(uuid);
        if (run == null) {
            throw new NotFoundException();
        }
        AirtableReconciliationReport report = null;
        if (run.getReport() != null) {
            try {
                report = objectMapper.readValue(run.getReport(), AirtableReconciliationReport.class);
            } catch (Exception e) {
                log.warnf(e, "Airtable run %s: stored report is unreadable", uuid);
            }
        }
        // Live progress straight from the ledger — survives deploys.
        Map<String, Long> ledgerCounts = Map.of(
                "IMPORTED", AirtableImportRecord.count("status", AirtableImportRecord.Status.IMPORTED),
                "SKIPPED", AirtableImportRecord.count("status", AirtableImportRecord.Status.SKIPPED));
        return new RunDetail(toRow(run), report, ledgerCounts);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static RunRow toRow(AirtableImportRun run) {
        return new RunRow(run.getUuid(), run.getMode().name(), run.getStatus().name(),
                run.getStartedAt(), run.getFinishedAt(), run.getStartedBy(), run.getError());
    }

    private static String practiceName(String practiceUuid) {
        Practice practice = Practice.<Practice>find("uuid", practiceUuid).firstResult();
        return practice == null ? null : practice.getName();
    }

    private void requireConfigured() {
        if (!exportService.isConfigured()) {
            throw new WebApplicationException(
                    "Airtable import is not configured — set AIRTABLE_TOKEN and AIRTABLE_BASE_ID",
                    Response.Status.SERVICE_UNAVAILABLE);
        }
    }
}
