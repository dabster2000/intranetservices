// src/main/java/dk/trustworks/intranet/aggregates/invoice/bonus/resources/InvoiceBonusResource.java
package dk.trustworks.intranet.aggregates.invoice.bonus.resources;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus.ShareType;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.InvoiceBonusService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Tag(
        name = "invoice-bonuses",
        description = "Administration af bonusser pr. faktura. Understøtter flere bonusser pr. faktura, self-assign (whitelist) og godkendelsesflow."
)
@Path("/invoices/{invoiceuuid}/bonuses")
@RequestScoped
@SecurityRequirement(name="jwt")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class InvoiceBonusResource {

    @Inject InvoiceBonusService service;

    @GET
    @RolesAllowed({"SYSTEM","FINANCE","SALES"})
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
                    implementation = ShareType.class,
                    enumeration = {"PERCENT","AMOUNT"}
            ) ShareType shareType,
            @Schema(
                    description = "Hvis PERCENT: værdi i [0;100]. Hvis AMOUNT: fast beløb i fakturaens valuta.",
                    example = "10.0"
            ) double shareValue,
            @Schema(
                    description = "Valgfri note/kommentar",
                    example = "Lead på casen"
            ) String note
    ) {}

    /** Konsulenten selv (whitelistet) kan selvtilføje sig. */
    @POST
    @Path("/self")
    @RolesAllowed({"SYSTEM","CONSULTANT"})
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

    /** Admin kan tilføje hvem som helst. */
    @POST
    @RolesAllowed({"SYSTEM","FINANCE","SALES"})
    @Transactional
    @Operation(
            operationId = "adminAddInvoiceBonus",
            summary = "Tilføj bonus (admin)",
            description = """
            Admin-/Finance-/Sales-tilføjelse af bonus til vilkårlig bruger. Kræver 'x-user-uuid' header med den handlendes UUID.
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
                    name = "x-user-uuid",
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "UUID for den bruger, der foretager handlingen (admin/finance/sales)",
                    example = "22222222-2222-2222-2222-222222222222"
            )
            @HeaderParam("x-user-uuid") String actingUser) {
        var ib = service.addAdmin(invoiceuuid, dto.useruuid(), actingUser, dto.shareType(), dto.shareValue(), dto.note());
        return Response.status(Response.Status.CREATED).entity(ib).build();
    }

    public record UpdateBonusDTO(
            @Schema(
                    description = "Ny type af andel",
                    implementation = ShareType.class,
                    enumeration = {"PERCENT","AMOUNT"}
            ) ShareType shareType,
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
    @RolesAllowed({"SYSTEM","FINANCE","SALES"})
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
    @RolesAllowed({"SYSTEM","FINANCE"})
    @Transactional
    @Operation(
            operationId = "approveInvoiceBonus",
            summary = "Godkend bonus",
            description = "Sætter status=APPROVED. Kræver FINANCE eller SYSTEM."
    )
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Godkendt"),
            @APIResponse(responseCode = "404", description = "Bonus ikke fundet"),
            @APIResponse(responseCode = "401", description = "Uautoriseret"),
            @APIResponse(responseCode = "403", description = "Ingen adgang")
    })
    public void approve(
            @Parameter(
                    name = "bonusuuid",
                    in = ParameterIn.PATH,
                    required = true,
                    description = "UUID for bonusrækken",
                    example = "33333333-3333-3333-3333-333333333333"
            )
            @PathParam("bonusuuid") String bonusuuid,
            @Parameter(
                    name = "x-user-uuid",
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "UUID for godkenderen",
                    example = "44444444-4444-4444-4444-444444444444"
            )
            @HeaderParam("x-user-uuid") String approver) {
        service.approve(bonusuuid, approver);
    }

    @POST
    @Path("/{bonusuuid}/reject")
    @RolesAllowed({"SYSTEM","FINANCE"})
    @Transactional
    @Operation(
            operationId = "rejectInvoiceBonus",
            summary = "Afvis bonus",
            description = "Sætter status=REJECTED med en forklarende note i request body (text/plain). Kræver FINANCE eller SYSTEM."
    )
    @RequestBody(
            required = false,
            content = @Content(
                    mediaType = "text/plain",
                    schema = @Schema(
                            description = "Begrundelse for afvisning",
                            example = "Afslås: mangler opfyldte kriterier"
                    )
            )
    )
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Afvist"),
            @APIResponse(responseCode = "404", description = "Bonus ikke fundet"),
            @APIResponse(responseCode = "401", description = "Uautoriseret"),
            @APIResponse(responseCode = "403", description = "Ingen adgang")
    })
    public void reject(
            @Parameter(
                    name = "bonusuuid",
                    in = ParameterIn.PATH,
                    required = true,
                    description = "UUID for bonusrækken",
                    example = "33333333-3333-3333-3333-333333333333"
            )
            @PathParam("bonusuuid") String bonusuuid,
            @Parameter(
                    name = "x-user-uuid",
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "UUID for godkenderen",
                    example = "44444444-4444-4444-4444-444444444444"
            )
            @HeaderParam("x-user-uuid") String approver,
            String note) {
        service.reject(bonusuuid, approver, note);
    }

    @DELETE
    @Path("/{bonusuuid}")
    @RolesAllowed({"SYSTEM","FINANCE","SALES"})
    @Transactional
    @Operation(
            operationId = "deleteInvoiceBonus",
            summary = "Slet bonus",
            description = "Sletter en bonusrække. Kræver SYSTEM/FINANCE/SALES."
    )
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Slettet"),
            @APIResponse(responseCode = "404", description = "Bonus ikke fundet"),
            @APIResponse(responseCode = "401", description = "Uautoriseret"),
            @APIResponse(responseCode = "403", description = "Ingen adgang")
    })
    public void delete(
            @Parameter(
                    name = "bonusuuid",
                    in = ParameterIn.PATH,
                    required = true,
                    description = "UUID for bonusrækken",
                    example = "33333333-3333-3333-3333-333333333333"
            )
            @PathParam("bonusuuid") String bonusuuid) {
        service.delete(bonusuuid);
    }
}
