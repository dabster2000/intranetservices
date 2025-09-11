// src/main/java/dk/trustworks/intranet/aggregates/invoice/bonus/services/InvoiceBonusService.java
package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus.ShareType;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibility;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.model.enums.SalesApprovalStatus;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Objects;

@ApplicationScoped
public class InvoiceBonusService {

    public List<InvoiceBonus> findByInvoice(String invoiceuuid) {
        return InvoiceBonus.list("invoiceuuid = ?1", invoiceuuid);
    }

    public double sumApproved(String invoiceuuid) {
        return InvoiceBonus.<InvoiceBonus>stream("invoiceuuid = ?1 and status = ?2",
                invoiceuuid, SalesApprovalStatus.APPROVED)
                .mapToDouble(InvoiceBonus::getComputedAmount)
                .sum();
    }

    @Transactional
    public InvoiceBonus addSelfAssign(String invoiceuuid, String useruuid,
                                      ShareType type, double value, String note) {
        assertEligible(useruuid);
        return addInternal(invoiceuuid, useruuid, useruuid, type, value, note);
    }

    @Transactional
    public InvoiceBonus addAdmin(String invoiceuuid, String targetUseruuid, String addedBy,
                                 ShareType type, double value, String note) {
        return addInternal(invoiceuuid, targetUseruuid, addedBy, type, value, note);
    }

    private InvoiceBonus addInternal(String invoiceuuid, String useruuid, String addedBy,
                                     ShareType type, double value, String note) {
        // unik pr. (invoice,user)
        if (InvoiceBonus.count("invoiceuuid = ?1 and useruuid = ?2", invoiceuuid, useruuid) > 0) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                .entity("User already added for invoice bonus").build());
        }
        Invoice inv = Invoice.findById(invoiceuuid);
        if (inv == null) throw new WebApplicationException(Response.Status.NOT_FOUND);

        InvoiceBonus ib = new InvoiceBonus();
        ib.setInvoiceuuid(invoiceuuid);
        ib.setUseruuid(useruuid);
        ib.setAddedBy(addedBy);
        ib.setShareType(type);
        ib.setShareValue(value);
        ib.setOverrideNote(note);
        recomputeComputedAmount(inv, ib);
        ib.persist();

        // valider procent-sum ≤ 100
        if (type == ShareType.PERCENT) {
            double sumPct = InvoiceBonus.<InvoiceBonus>stream("invoiceuuid = ?1 and shareType = ?2",
                    invoiceuuid, ShareType.PERCENT).mapToDouble(InvoiceBonus::getShareValue).sum();
            if (sumPct > 100.0 + 1e-9) {
                throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("Sum of percent shares exceeds 100%").build());
            }
        }
        return ib;
    }

    @Transactional
    public void updateShare(String bonusUuid, ShareType type, double value, String note) {
        InvoiceBonus ib = InvoiceBonus.findById(bonusUuid);
        if (ib == null) throw new WebApplicationException(Response.Status.NOT_FOUND);
        ib.setShareType(type);
        ib.setShareValue(value);
        ib.setOverrideNote(note);
        Invoice inv = Invoice.findById(ib.getInvoiceuuid());
        recomputeComputedAmount(inv, ib);
        ib.persist();
    }

    @Transactional
    public void approve(String bonusUuid, String approverUuid) {
        setStatus(bonusUuid, approverUuid, SalesApprovalStatus.APPROVED);
    }

    @Transactional
    public void reject(String bonusUuid, String approverUuid, String note) {
        InvoiceBonus ib = InvoiceBonus.findById(bonusUuid);
        if (ib == null) throw new WebApplicationException(Response.Status.NOT_FOUND);
        ib.setOverrideNote(note);
        setStatus(bonusUuid, approverUuid, SalesApprovalStatus.REJECTED);
    }

    private void setStatus(String bonusUuid, String approverUuid, SalesApprovalStatus status) {
        InvoiceBonus ib = InvoiceBonus.findById(bonusUuid);
        if (ib == null) throw new WebApplicationException(Response.Status.NOT_FOUND);
        ib.setStatus(status);
        ib.setApprovedBy(approverUuid);
        ib.setApprovedAt(java.time.LocalDateTime.now());
        ib.persist();
    }

    @Transactional
    public void delete(String bonusUuid) {
        Panache.getEntityManager().remove(Objects.requireNonNull(InvoiceBonus.findById(bonusUuid)));
    }

    /** Kald denne efter pricing/recalc så computed_amount altid matcher seneste sum. */
    @Transactional
    public void recalcForInvoice(String invoiceuuid) {
        Invoice inv = Invoice.findById(invoiceuuid);
        if (inv == null) return;
        List<InvoiceBonus> list = findByInvoice(invoiceuuid);
        list.forEach(ib -> {
            recomputeComputedAmount(inv, ib);
            ib.persist();
        });
    }

    private static void recomputeComputedAmount(Invoice inv, InvoiceBonus ib) {
        double base = inv.getSumAfterDiscounts() != null ? inv.getSumAfterDiscounts() : inv.getSumNoTax();
        double computed = switch (ib.getShareType()) {
            case PERCENT -> base * (ib.getShareValue() / 100.0);
            case AMOUNT  -> ib.getShareValue();
        };
        ib.setComputedAmount(Math.max(0.0, round2(computed)));
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static void assertEligible(String useruuid) {
        BonusEligibility be = BonusEligibility.find("useruuid", useruuid).firstResult();
        var today = java.time.LocalDate.now();
        if (be == null || !be.isCanSelfAssign()
                || today.isBefore(be.getActiveFrom()) || today.isAfter(be.getActiveTo())) {
            throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN)
                .entity("User is not allowed to self‑assign bonus").build());
        }
    }
}
