package dk.trustworks.intranet.aggregates.invoice.economics;

import dk.trustworks.intranet.model.Company;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for the e-conomics payment-term and VAT-zone mapping tables.
 * The mappings are populated manually by the accountant/admin; consumed by
 * Phase D (contract form) and Phase H (invoice finalization).
 *
 * SPEC-INV-001 §7.4, §16.3.
 */
@Path("/invoices/economics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EconomicsConfigResource {

    @Inject PaymentTermsMappingRepository paymentTermsRepo;
    @Inject VatZoneMappingRepository      vatZoneRepo;
    @Inject EntityManager                 em;

    public record PaymentTermsRequest(
            @NotNull PaymentTermsType paymentTermsType,
            Integer paymentDays,
            String companyUuid,
            @NotNull Integer economicsPaymentTermsNumber,
            String economicsPaymentTermsName
    ) {}

    public record VatZoneRequest(
            @NotNull String currency,
            String companyUuid,
            @NotNull Integer economicsVatZoneNumber,
            String economicsVatZoneName
    ) {}

    // ----- Payment Terms -----

    @GET
    @Path("/payment-terms")
    @RolesAllowed({"invoices:read", "invoices:write"})
    public List<PaymentTermsMapping> listPaymentTerms(
            @QueryParam("companyUuid") @DefaultValue("") String companyUuid) {
        return paymentTermsRepo.listForCompany(companyUuid.isBlank() ? null : companyUuid);
    }

    @POST
    @Path("/payment-terms")
    @RolesAllowed("invoices:write")
    @Transactional
    public Response createPaymentTerms(@Valid PaymentTermsRequest req) {
        validatePaymentTerms(req);
        PaymentTermsMapping m = new PaymentTermsMapping();
        m.setUuid(UUID.randomUUID().toString());
        m.setPaymentTermsType(req.paymentTermsType());
        m.setPaymentDays(req.paymentDays());
        m.setCompany(resolveCompany(req.companyUuid()));
        m.setEconomicsPaymentTermsNumber(req.economicsPaymentTermsNumber());
        m.setEconomicsPaymentTermsName(req.economicsPaymentTermsName());
        paymentTermsRepo.persist(m);
        return Response.status(Response.Status.CREATED).entity(m).build();
    }

    @PUT
    @Path("/payment-terms/{uuid}")
    @RolesAllowed("invoices:write")
    @Transactional
    public Response updatePaymentTerms(@PathParam("uuid") String uuid, @Valid PaymentTermsRequest req) {
        validatePaymentTerms(req);
        PaymentTermsMapping m = paymentTermsRepo.findById(uuid);
        if (m == null) return Response.status(Response.Status.NOT_FOUND).build();
        m.setPaymentTermsType(req.paymentTermsType());
        m.setPaymentDays(req.paymentDays());
        m.setCompany(resolveCompany(req.companyUuid()));
        m.setEconomicsPaymentTermsNumber(req.economicsPaymentTermsNumber());
        m.setEconomicsPaymentTermsName(req.economicsPaymentTermsName());
        return Response.ok(m).build();
    }

    @DELETE
    @Path("/payment-terms/{uuid}")
    @RolesAllowed("invoices:write")
    @Transactional
    public Response deletePaymentTerms(@PathParam("uuid") String uuid) {
        boolean deleted = paymentTermsRepo.deleteById(uuid);
        return deleted
                ? Response.noContent().build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }

    // ----- VAT Zones -----

    @GET
    @Path("/vat-zones")
    @RolesAllowed({"invoices:read", "invoices:write"})
    public List<VatZoneMapping> listVatZones(
            @QueryParam("companyUuid") @DefaultValue("") String companyUuid) {
        return vatZoneRepo.listForCompany(companyUuid.isBlank() ? null : companyUuid);
    }

    @POST
    @Path("/vat-zones")
    @RolesAllowed("invoices:write")
    @Transactional
    public Response createVatZone(@Valid VatZoneRequest req) {
        VatZoneMapping m = new VatZoneMapping();
        m.setUuid(UUID.randomUUID().toString());
        m.setCurrency(req.currency());
        m.setCompany(resolveCompany(req.companyUuid()));
        m.setEconomicsVatZoneNumber(req.economicsVatZoneNumber());
        m.setEconomicsVatZoneName(req.economicsVatZoneName());
        vatZoneRepo.persist(m);
        return Response.status(Response.Status.CREATED).entity(m).build();
    }

    @PUT
    @Path("/vat-zones/{uuid}")
    @RolesAllowed("invoices:write")
    @Transactional
    public Response updateVatZone(@PathParam("uuid") String uuid, @Valid VatZoneRequest req) {
        VatZoneMapping m = vatZoneRepo.findById(uuid);
        if (m == null) return Response.status(Response.Status.NOT_FOUND).build();
        m.setCurrency(req.currency());
        m.setCompany(resolveCompany(req.companyUuid()));
        m.setEconomicsVatZoneNumber(req.economicsVatZoneNumber());
        m.setEconomicsVatZoneName(req.economicsVatZoneName());
        return Response.ok(m).build();
    }

    @DELETE
    @Path("/vat-zones/{uuid}")
    @RolesAllowed("invoices:write")
    @Transactional
    public Response deleteVatZone(@PathParam("uuid") String uuid) {
        boolean deleted = vatZoneRepo.deleteById(uuid);
        return deleted
                ? Response.noContent().build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }

    // ----- helpers -----

    private void validatePaymentTerms(PaymentTermsRequest req) {
        if (req.paymentTermsType().requiresPaymentDays() && req.paymentDays() == null) {
            throw new BadRequestException(
                    "paymentDays is required for type " + req.paymentTermsType().economicsValue());
        }
        if (!req.paymentTermsType().requiresPaymentDays() && req.paymentDays() != null) {
            throw new BadRequestException(
                    "paymentDays must be null for type " + req.paymentTermsType().economicsValue());
        }
    }

    private Company resolveCompany(String companyUuid) {
        if (companyUuid == null || companyUuid.isBlank()) return null;
        return em.find(Company.class, companyUuid);
    }
}
