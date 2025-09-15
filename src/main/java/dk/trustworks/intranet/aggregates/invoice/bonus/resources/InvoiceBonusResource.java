// src/main/java/dk/trustworks/intranet/aggregates/invoice/bonus/resources/InvoiceBonusResource.java
package dk.trustworks.intranet.aggregates.invoice.bonus.resources;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonusLine;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.InvoiceBonusService;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.BonusAggregateResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import java.util.List;

@Path("/invoices/{invoiceuuid}/bonuses")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class InvoiceBonusResource {

    @Inject InvoiceBonusService service;
    @Inject JsonWebToken jwt;

    @GET
    @Operation(
            operationId = "listInvoiceBonuses",
            summary = "List bonusser for en faktura",
            description = "Returnerer alle bonusrækker knyttet til den angivne faktura. Tom liste hvis ingen fundet."
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "OK – liste af bonusser",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = InvoiceBonus[].class)
                    )
            ),
            @APIResponse(responseCode = "401", description = "Uautoriseret"),
            @APIResponse(responseCode = "403", description = "Ingen adgang")
    })
    public List<InvoiceBonus> list(
            @Parameter(
                    name = "invoiceuuid",
                    in = ParameterIn.PATH,
                    required = true,
                    description = "UUID for fakturaen som bonusserne er knyttet til",
                    example = "2b0d9fbe-6f1a-4a17-9f8c-8a8a8a8a8a8a"
            )
            @PathParam("invoiceuuid") String invoiceuuid) {
        return service.findByInvoice(invoiceuuid);
    }

    public record CreateBonusDTO(
            @Schema(
                    description = "UUID for brugeren, der modtager bonus",
                    example = "11111111-1111-1111-1111-111111111111"
            ) String useruuid,
            @Schema(
                    description = "Type af andel: procent (0-100) eller fast beløb i fakturaens valuta",
                    implementation = InvoiceBonus.ShareType.class,
                    enumeration = {"PERCENT","AMOUNT"}
            ) InvoiceBonus.ShareType shareType,
            @Schema(
                    description = "Hvis PERCENT: værdi i [0;100]. Hvis AMOUNT: fast beløb i fakturaens valuta.",
                    example = "10.0"
            ) double shareValue,
            @Schema(
                    description = "Valgfri note/kommentar",
                    example = "Lead på casen"
            ) String note
    ) {}

    @POST
    @Path("/self")
    @Transactional
    @Operation(
            operationId = "selfAssignInvoiceBonus",
            summary = "Self-assign bonus (konsulent)",
            description = """
            Tilføjer en bonusrække for den autentificerede konsulent, hvis vedkommende er whitelisted (eligibility).
            `shareType` kan være `PERCENT` (0-100) eller `AMOUNT` (fast beløb). Serveren beregner `computedAmount`.
            Kræver rolle CONSULANT eller SYSTEM og at brugeren er markeret som 'can_self_assign' i eligibility.
            """
    )
    @RequestBody(
            required = true,
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = CreateBonusDTO.class),
                    examples = {
                            @ExampleObject(
                                    name = "Procent-andel",
                                    value = """
                        {
                          "useruuid": "11111111-1111-1111-1111-111111111111",
                          "shareType": "PERCENT",
                          "shareValue": 10.0,
                          "note": "Lead på casen"
                        }
                        """
                            ),
                            @ExampleObject(
                                    name = "Fast beløb",
                                    value = """
                        {
                          "useruuid": "11111111-1111-1111-1111-111111111111",
                          "shareType": "AMOUNT",
                          "shareValue": 2500.0,
                          "note": "Fast bonus jf. aftale"
                        }
                        """
                            )
                    }
            )
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "201",
                    description = "Oprettet – returnerer oprettet bonusrække",
                    content = @Content(schema = @Schema(implementation = InvoiceBonus.class))
            ),
            @APIResponse(responseCode = "400", description = "Ugyldig input"),
            @APIResponse(responseCode = "403", description = "Bruger er ikke berettiget til self-assign"),
            @APIResponse(responseCode = "404", description = "Faktura ikke fundet"),
            @APIResponse(responseCode = "409", description = "Bonus for (invoice,user) findes allerede"),
            @APIResponse(responseCode = "401", description = "Uautoriseret")
    })
    public Response addSelf(
            @Parameter(
                    name = "invoiceuuid",
                    in = ParameterIn.PATH,
                    required = true,
                    description = "UUID for fakturaen",
                    example = "2b0d9fbe-6f1a-4a17-9f8c-8a8a8a8a8a8a"
            )
            @PathParam("invoiceuuid") String invoiceuuid,
            CreateBonusDTO dto) {
        var ib = service.addSelfAssign(invoiceuuid, dto.useruuid(), dto.shareType(), dto.shareValue(), dto.note());
        return Response.status(Response.Status.CREATED).entity(ib).build();
    }

    @POST
    @Transactional
    @Operation(
            operationId = "adminAddInvoiceBonus",
            summary = "Tilføj bonus (admin)",
            description = """
            Admin-/Finance-/Sales-tilføjelse af bonus til vilkårlig bruger. Kræver 'X-Requested-By' header med den handlendes UUID.
            Validerer bl.a. at kombinationen (invoiceuuid,useruuid) ikke allerede findes og at procent-sum ikke overskrider 100%%.
            """
    )
    @RequestBody(
            required = true,
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = CreateBonusDTO.class)
            )
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "201",
                    description = "Oprettet – returnerer oprettet bonusrække",
                    content = @Content(schema = @Schema(implementation = InvoiceBonus.class))
            ),
            @APIResponse(responseCode = "400", description = "Ugyldig input"),
            @APIResponse(responseCode = "404", description = "Faktura ikke fundet"),
            @APIResponse(responseCode = "409", description = "Bonus for (invoice,user) findes allerede"),
            @APIResponse(responseCode = "401", description = "Uautoriseret"),
            @APIResponse(responseCode = "403", description = "Ingen adgang")
    })
    public Response add(
            @Parameter(
                    name = "invoiceuuid",
                    in = ParameterIn.PATH,
                    required = true,
                    description = "UUID for fakturaen",
                    example = "2b0d9fbe-6f1a-4a17-9f8c-8a8a8a8a8a8a"
            )
            @PathParam("invoiceuuid") String invoiceuuid,
            CreateBonusDTO dto,
            @Parameter(
                    name = "X-Requested-By",
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "UUID for den bruger, der foretager handlingen (admin/finance/sales)",
                    example = "22222222-2222-2222-2222-222222222222"
            )
            @HeaderParam("X-Requested-By") String actingUser) {
        var ib = service.addAdmin(invoiceuuid, dto.useruuid(), actingUser, dto.shareType(), dto.shareValue(), dto.note());
        return Response.status(Response.Status.CREATED).entity(ib).build();
    }

    public record UpdateBonusDTO(
            @Schema(
                    description = "Ny type af andel",
                    implementation = InvoiceBonus.ShareType.class,
                    enumeration = {"PERCENT","AMOUNT"}
            ) InvoiceBonus.ShareType shareType,
            @Schema(
                    description = "Ny værdi: [0;100] for PERCENT ellers fast beløb i fakturaens valuta",
                    example = "12.5"
            ) double shareValue,
            @Schema(
                    description = "Valgfri note",
                    example = "Justeret efter endelig fakturasum"
            ) String note
    ) {}

    @PUT
    @Path("/{bonusuuid}")
    @Transactional
    @Operation(
            operationId = "updateInvoiceBonus",
            summary = "Opdater bonusandel",
            description = "Opdaterer type/værdi/note for en eksisterende bonusrække og re-beregner `computedAmount`."
    )
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Opdateret"),
            @APIResponse(responseCode = "400", description = "Ugyldig input"),
            @APIResponse(responseCode = "404", description = "Bonus ikke fundet"),
            @APIResponse(responseCode = "401", description = "Uautoriseret"),
            @APIResponse(responseCode = "403", description = "Ingen adgang")
    })
    public void update(
            @Parameter(
                    name = "bonusuuid",
                    in = ParameterIn.PATH,
                    required = true,
                    description = "UUID for bonusrækken",
                    example = "33333333-3333-3333-3333-333333333333"
            )
            @PathParam("bonusuuid") String bonusuuid,
            UpdateBonusDTO dto) {
        service.updateShare(bonusuuid, dto.shareType(), dto.shareValue(), dto.note());
    }

    @POST
    @Path("/{bonusuuid}/approve")
    @Transactional
    public dk.trustworks.intranet.aggregates.invoice.resources.dto.BonusAggregateResponse approve(@PathParam("bonusuuid") String bonusuuid,
                        @HeaderParam("X-Requested-By") String approver) {
        String resolved = (approver != null && !approver.isBlank())
                ? approver
                : (jwt.containsClaim("uuid") ? jwt.getClaim("uuid") : jwt.getSubject());
        if (resolved == null || resolved.isBlank())
            throw new WebApplicationException(Response.Status.UNAUTHORIZED);
        service.approve(bonusuuid, resolved);
        InvoiceBonus ib = InvoiceBonus.findById(bonusuuid);
        String invoiceuuid = ib.getInvoiceuuid();
        var agg = service.aggregatedStatusForInvoice(invoiceuuid);
        double total = service.totalBonusAmountForInvoice(invoiceuuid);
        return new dk.trustworks.intranet.aggregates.invoice.resources.dto.BonusAggregateResponse(invoiceuuid, agg, total);
    }

    @POST
    @Path("/{bonusuuid}/reject")
    @Transactional
    public dk.trustworks.intranet.aggregates.invoice.resources.dto.BonusAggregateResponse reject(@PathParam("bonusuuid") String bonusuuid,
                       @HeaderParam("X-Requested-By") String approver,
                       String note) {
        String resolved = (approver != null && !approver.isBlank())
                ? approver
                : (jwt.containsClaim("uuid") ? jwt.getClaim("uuid") : jwt.getSubject());
        if (resolved == null || resolved.isBlank())
            throw new WebApplicationException(Response.Status.UNAUTHORIZED);
        service.reject(bonusuuid, resolved, note);
        InvoiceBonus ib = InvoiceBonus.findById(bonusuuid);
        String invoiceuuid = ib.getInvoiceuuid();
        var agg = service.aggregatedStatusForInvoice(invoiceuuid);
        double total = service.totalBonusAmountForInvoice(invoiceuuid);
        return new dk.trustworks.intranet.aggregates.invoice.resources.dto.BonusAggregateResponse(invoiceuuid, agg, total);
    }

    // ------------------- NYT: linjevalg pr. bonus -------------------

    public record LineDTO(String invoiceitemuuid, double percentage) {}

    @GET
    @Path("/{bonusuuid}/lines")
    public List<InvoiceBonusLine> getLines(@PathParam("bonusuuid") String bonusuuid) {
        return service.listLines(bonusuuid);
    }

    @PUT
    @Path("/{bonusuuid}/lines")
    @Transactional
    public BonusAggregateResponse putLines(@PathParam("invoiceuuid") String invoiceuuid,
                             @PathParam("bonusuuid") String bonusuuid,
                             List<LineDTO> body) {
        List<InvoiceBonusLine> mapped = body == null ? List.of() :
                body.stream().map(dto -> {
                    InvoiceBonusLine l = new InvoiceBonusLine();
                    l.setInvoiceitemuuid(dto.invoiceitemuuid());
                    l.setPercentage(dto.percentage());
                    return l;
                }).toList();
        service.putLines(invoiceuuid, bonusuuid, mapped);
        var agg = service.aggregatedStatusForInvoice(invoiceuuid);
        double total = service.totalBonusAmountForInvoice(invoiceuuid);
        return new BonusAggregateResponse(invoiceuuid, agg, total);
    }

    @DELETE
    @Path("/{bonusuuid}")
    @Transactional
    public void delete(@PathParam("bonusuuid") String bonusuuid) {
        service.delete(bonusuuid);
    }

}
