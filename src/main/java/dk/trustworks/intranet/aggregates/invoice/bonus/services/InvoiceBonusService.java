// src/main/java/dk/trustworks/intranet/aggregates/invoice/bonus/services/InvoiceBonusService.java
package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibility;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus.ShareType;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonusLine;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.model.enums.SalesApprovalStatus;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    private void setStatus(String bonusUuid, String approver, SalesApprovalStatus status) {
        InvoiceBonus ib = InvoiceBonus.findById(bonusUuid);
        if (ib == null) throw new WebApplicationException(Response.Status.NOT_FOUND);

        String approverUuid = resolveUserUuid(approver);
        if (approverUuid == null) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("Approver must be a valid user UUID or username").build());
        }

        ib.setStatus(status);
        ib.setApprovedBy(approverUuid);
        ib.setApprovedAt(java.time.LocalDateTime.now());
        ib.persist();
    }

    private String resolveUserUuid(String input) {
        if (input == null || input.isBlank()) return null;
        // If it's a UUID and exists as a user, accept it
        if (looksLikeUuid(input)) {
            if (User.findById(input) != null) return input;
        }
        // Otherwise, try resolve as username
        return User.findByUsername(input).map(u -> u.uuid).orElse(null);
    }

    private boolean looksLikeUuid(String s) {
        try {
            java.util.UUID.fromString(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Transactional
    public void delete(String bonusUuid) {
        Panache.getEntityManager().remove(Objects.requireNonNull(InvoiceBonus.findById(bonusUuid)));
        // ryd også linjevalg
        InvoiceBonusLine.delete("bonusuuid", bonusUuid);
    }

    private static void assertEligible(String useruuid) {
        BonusEligibility be = BonusEligibility
                .find("useruuid", useruuid).firstResult();
        var today = java.time.LocalDate.now();
        if (be == null || !be.isCanSelfAssign()
                || today.isBefore(be.getActiveFrom()) || today.isAfter(be.getActiveTo())) {
            throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN)
                    .entity("User is not allowed to self‑assign bonus").build());
        }
    }

    // -------------------- NYT: linjevalg pr. bonus --------------------

    public List<InvoiceBonusLine> listLines(String bonusuuid) {
        return InvoiceBonusLine.list("bonusuuid = ?1", bonusuuid);
    }

    @Transactional
    public void putLines(String invoiceuuid, String bonusuuid, List<InvoiceBonusLine> lines) {
        InvoiceBonus ib = InvoiceBonus.findById(bonusuuid);
        if (ib == null) throw new WebApplicationException(Response.Status.NOT_FOUND);
        if (!Objects.equals(ib.getInvoiceuuid(), invoiceuuid)) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("bonusuuid does not belong to invoiceuuid").build());
        }


        // Wipe existing lines for this bonus and insert the new set
        InvoiceBonusLine.delete("bonusuuid = ?1", bonusuuid);

        if (lines != null) {
            for (InvoiceBonusLine l : lines) {
                InvoiceBonusLine row = new InvoiceBonusLine();
                row.setBonusuuid(bonusuuid);
                row.setInvoiceuuid(invoiceuuid);
                row.setInvoiceitemuuid(l.getInvoiceitemuuid());
                row.setPercentage(sanitizePct(l.getPercentage()));
                row.persist();
            }
        }

        // Recompute shareValue/computedAmount based on the current set of lines
        Invoice inv = Invoice.findById(invoiceuuid);
        recomputeFromLinesOrFallback(inv, ib);
        ib.persist();
    }

    private static double sanitizePct(double pct) {
        if (Double.isNaN(pct) || Double.isInfinite(pct)) return 0.0;
        if (pct < 0) return 0.0;
        if (pct > 100) return 100.0;
        return pct;
    }

    /**
     * If bonus has lines -> compute from lines; otherwise fallback to legacy (shareType/shareValue).
     * Allows negative amounts for credit notes.
     */
    private void recomputeFromLinesOrFallback(Invoice inv, InvoiceBonus ib) {
        List<InvoiceBonusLine> lines = listLines(ib.getUuid());
        if (lines != null && !lines.isEmpty()) {
            double amount = computeAmountFromLines(inv, lines);
            amount = round2(amount);

            // When line selections exist, we express the bonus as AMOUNT
            ib.setShareType(InvoiceBonus.ShareType.AMOUNT);
            ib.setShareValue(amount);
            ib.setComputedAmount(amount);
        } else {
            // No lines -> legacy computation
            recomputeComputedAmount(inv, ib);
        }
    }

    /**
     * Sum selected BASE items (hours*rate), apply invoice discount%, then apply each line's percentage.
     * CALCULATED items are ignored. Credit notes flip the sign.
     */
    private static double computeAmountFromLines(Invoice inv, List<InvoiceBonusLine> lines) {
        if (inv == null) return 0.0;

        // Map items by id for fast lookup
        Map<String, InvoiceItem> itemById = inv.getInvoiceitems().stream()
                .collect(Collectors.toMap(InvoiceItem::getUuid, Function.identity(), (a, b)->a));

        double discountFactor = 1.0 - (Optional.ofNullable(inv.getDiscount()).orElse(0.0) / 100.0);
        double sum = 0.0;

        for (InvoiceBonusLine sel : lines) {
            InvoiceItem ii = itemById.get(sel.getInvoiceitemuuid());
            if (ii == null) continue;
            if (ii.getOrigin() == InvoiceItemOrigin.CALCULATED) continue; // never count synthetic items

            double lineAmount = ii.getHours() * ii.getRate();
            lineAmount *= discountFactor;

            sum += lineAmount * (sel.getPercentage() / 100.0);
        }

        if (inv.getType() == InvoiceType.CREDIT_NOTE) sum = -sum;
        return sum;
    }

    /** Legacy: used when there are no line selections for this bonus. */
    private static void recomputeComputedAmount(Invoice inv, InvoiceBonus ib) {
        // If invoice has server-sent totals, prefer those; otherwise fallback to no-tax sum.
        double base = 0.0;
        if (inv != null) {
            if (inv.getSumAfterDiscounts() != null) base = inv.getSumAfterDiscounts();
            else base = inv.getSumNoTax(); // adapt if your entity differs
            if (inv.getType() == InvoiceType.CREDIT_NOTE) base = -base;
        }

        double computed = switch (ib.getShareType()) {
            case PERCENT -> base * (ib.getShareValue() / 100.0);
            case AMOUNT  -> ib.getShareValue();
        };

        ib.setComputedAmount(round2(computed)); // DO NOT clamp; allow negative for credit notes
    }

    /** Recalc all bonuses for an invoice (called e.g. after pricing). Honors line selections. */
    @Transactional
    public void recalcForInvoice(String invoiceuuid) {
        Invoice inv = Invoice.findById(invoiceuuid);
        if (inv == null) return;
        List<InvoiceBonus> list = findByInvoice(invoiceuuid);
        list.forEach(ib -> {
            recomputeFromLinesOrFallback(inv, ib);
            ib.persist();
        });
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    
    public List<BonusEligibility> listEligibility() {
        return BonusEligibility.listAll();
    }

    @Transactional
    public BonusEligibility upsertEligibility(String useruuid, boolean canSelfAssign, LocalDate activeFrom, LocalDate activeTo) {
        if (activeFrom != null && activeTo != null && activeFrom.isAfter(activeTo)) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("activeFrom must be on or before activeTo").build());
        }
        BonusEligibility be = BonusEligibility.find("useruuid", useruuid).firstResult();
        if (be == null) {
            be = new BonusEligibility();
            be.setUseruuid(useruuid);
        }
        be.setCanSelfAssign(canSelfAssign);
        if (activeFrom != null) be.setActiveFrom(activeFrom);
        if (activeTo != null) be.setActiveTo(activeTo);
        be.persist();
        return be;
    }

    @Transactional
    public void deleteEligibilityByUseruuid(String useruuid) {
        BonusEligibility be = BonusEligibility.find("useruuid", useruuid).firstResult();
        if (be == null) throw new WebApplicationException(Response.Status.NOT_FOUND);
        be.delete();
    }
}
