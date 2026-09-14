package dk.trustworks.intranet.aggregates.crm.merge.resources;

import dk.trustworks.intranet.aggregates.crm.merge.dto.ClientMergePreviewDTO;
import dk.trustworks.intranet.aggregates.crm.merge.dto.ClientMergeRequest;
import dk.trustworks.intranet.aggregates.crm.merge.dto.ClientMergeResultDTO;
import dk.trustworks.intranet.aggregates.crm.merge.services.ClientMergeService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.regex.Pattern;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Merging a duplicate client into the right one — docs/specs/crm-client-merge-2026-09-14.md.
 *
 * <p><b>Its own class path, deliberately longer than {@code /clients}.</b> RESTEasy
 * Reactive selects the resource class by its class-level {@code @Path} before it looks at
 * a method, longest prefix first — which is how {@code /clients/enrichment} coexists with
 * {@code ClientResource} and how {@code /clients/merge} does here. Both paths below are
 * therefore {@code /clients/merge/...}, never {@code /clients/{uuid}/...}.
 *
 * <p>{@code clients:merge} on the whole class (spec §7). {@code crm:write} would not do: a
 * merge repoints invoices and contracts, and leaves e-conomic customers for somebody to
 * deactivate by hand — a bookkeeping act, granted to the roles that can book invoices.
 * The preview is under the same scope on purpose: it lists a company's invoices,
 * contracts and e-conomic customer numbers, which is more than {@code crm:read} shows.
 * {@code @RolesAllowed} gates the CLIENT, not the person — the per-person gate is the
 * BFF's {@code requirePermission}.
 */
@Tag(name = "crm")
@JBossLog
@Path("/clients/merge")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@RolesAllowed({"clients:merge"})
public class ClientMergeResource {

    private static final Pattern UUID_SHAPE = Pattern.compile("^[0-9a-fA-F-]{36}$");

    @Inject
    ClientMergeService mergeService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @GET
    @Path("/{winnerUuid}/preview/{loserUuid}")
    @Operation(summary = "What merging one client into another would do",
            description = "Reads only. Per table, how many of the loser's rows move to the winner and how many are dropped "
                    + "as duplicates; the two accounts when both rows have one (a person chooses); the month controls both "
                    + "rows hold; and the loser's e-conomic customer numbers that would be orphaned. `problem` is non-null "
                    + "when the merge would be refused, with the sentence the POST answers 409 with.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "The preview"),
            @APIResponse(responseCode = "404", description = "Either client is unknown")
    })
    public ClientMergePreviewDTO preview(@PathParam("winnerUuid") String winnerUuid,
                                         @PathParam("loserUuid") String loserUuid) {
        requireUuid(winnerUuid);
        requireUuid(loserUuid);
        return mergeService.preview(winnerUuid, loserUuid);
    }

    @POST
    @Path("/{winnerUuid}/from/{loserUuid}")
    @Operation(summary = "Merge one client into another",
            description = "One transaction: the loser's rows that would collide with the winner's unique keys are removed by "
                    + "the spec's rules, every client-referencing column is repointed, the loser is tombstoned "
                    + "(client.merged_into_uuid), and the result is verified before commit. e-conomic is never touched: "
                    + "orphaned customer numbers come back in the answer and on the audit row. Only a PROSPECT can be merged away.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Merged; what moved and what was dropped"),
            @APIResponse(responseCode = "400", description = "Both rows have an account and accountFrom does not say whose to keep, or X-Requested-By is missing"),
            @APIResponse(responseCode = "404", description = "Either client is unknown"),
            @APIResponse(responseCode = "409", description = "The pair may not be merged: same row, already merged, or the loser is not a prospect")
    })
    public ClientMergeResultDTO merge(@PathParam("winnerUuid") String winnerUuid,
                                      @PathParam("loserUuid") String loserUuid,
                                      ClientMergeRequest request) {
        requireUuid(winnerUuid);
        requireUuid(loserUuid);
        log.infof("Client merge requested: loser=%s -> winner=%s by=%s", loserUuid, winnerUuid, requestHeaderHolder.getUserUuid());
        return mergeService.merge(winnerUuid, loserUuid, request);
    }

    private static void requireUuid(String value) {
        if (value == null || !UUID_SHAPE.matcher(value).matches()) {
            throw new BadRequestException("Not a client uuid: " + value);
        }
    }
}
