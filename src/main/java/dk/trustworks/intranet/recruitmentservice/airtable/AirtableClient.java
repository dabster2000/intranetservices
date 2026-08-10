package dk.trustworks.intranet.recruitmentservice.airtable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;
import java.util.Map;

/**
 * Minimal Airtable Web API client (ATS P21 import tooling). Two calls:
 * the Meta API table listing (so the importer discovers the per-team
 * pipeline tables instead of hardcoding them) and paged record listing.
 * <p>
 * Auth is a personal access token with {@code data.records:read} +
 * {@code schema.bases:read} scopes on the recruitment base, passed as a
 * Bearer header by {@link AirtableExportService} — the token itself lives
 * in config ({@code recruitment.airtable.token}), never in code.
 */
@Path("/v0")
@RegisterRestClient(configKey = "airtable-api")
public interface AirtableClient {

    /** Meta API: every table in the base (id, name). */
    @GET
    @Path("/meta/bases/{baseId}/tables")
    @Produces(MediaType.APPLICATION_JSON)
    TablesResponse listTables(@HeaderParam("Authorization") String authorization,
                              @PathParam("baseId") String baseId);

    /**
     * One page of records (max 100). Pass the previous page's
     * {@code offset} to continue; a {@code null} offset in the response
     * means the listing is complete.
     */
    @GET
    @Path("/{baseId}/{tableId}")
    @Produces(MediaType.APPLICATION_JSON)
    RecordsPage listRecords(@HeaderParam("Authorization") String authorization,
                            @PathParam("baseId") String baseId,
                            @PathParam("tableId") String tableId,
                            @QueryParam("pageSize") int pageSize,
                            @QueryParam("offset") String offset);

    /**
     * One page of a record's COMMENTS — the collaboration thread on the
     * record (interview feedback, scheduling agreements). A separate API
     * from the record's fields; needs the {@code data.recordComments:read}
     * token scope.
     */
    @GET
    @Path("/{baseId}/{tableId}/{recordId}/comments")
    @Produces(MediaType.APPLICATION_JSON)
    CommentsPage listComments(@HeaderParam("Authorization") String authorization,
                              @PathParam("baseId") String baseId,
                              @PathParam("tableId") String tableId,
                              @PathParam("recordId") String recordId,
                              @QueryParam("pageSize") int pageSize,
                              @QueryParam("offset") String offset);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TablesResponse(List<TableInfo> tables) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TableInfo(String id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RecordsPage(List<AirtableRecord> records, String offset) {
    }

    /**
     * One raw Airtable record. {@code fields} holds whatever the base
     * defines — strings for selects/text, lists of maps for attachments,
     * maps for collaborators. Empty fields are absent, never null values.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AirtableRecord(String id, String createdTime, Map<String, Object> fields) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommentsPage(List<AirtableComment> comments, String offset) {
    }

    /**
     * One record comment. {@code text} may contain {@code @[usrXXX]}
     * mention placeholders resolved through {@code mentioned}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AirtableComment(String id, CommentAuthor author, String text, String createdTime,
                           Map<String, CommentMention> mentioned) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommentAuthor(String email, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommentMention(String displayName) {
    }
}
