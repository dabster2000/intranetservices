package dk.trustworks.intranet.vacationservice.services;

import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine;
import dk.trustworks.intranet.vacationservice.model.VacationLedgerEntry;
import dk.trustworks.intranet.vacationservice.model.enums.VacationEntrySource;
import dk.trustworks.intranet.vacationservice.model.enums.VacationEntryType;
import dk.trustworks.intranet.vacationservice.model.enums.VacationPoolType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static dk.trustworks.intranet.vacationservice.engine.VacationRules.round2;

/**
 * Posts vacation ledger facts. All writes go through here so validation,
 * idempotency and audit stamping live in one place.
 */
@JBossLog
@ApplicationScoped
public class VacationLedgerService {

    static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");
    private static final double EPS = 0.005;

    @Inject
    VacationBalanceService balanceService;

    // ── Generic posting ───────────────────────────────────────────────────

    public VacationLedgerEntry buildEntry(String useruuid, int ferieaar, VacationPoolType pool,
                                          VacationEntryType type, double days, LocalDate effectiveDate,
                                          VacationEntrySource source, String sourceRef, String note,
                                          String createdBy) {
        VacationLedgerEntry entry = new VacationLedgerEntry();
        entry.setUuid(UUID.randomUUID().toString());
        entry.setUseruuid(useruuid);
        entry.setFerieaar(ferieaar);
        entry.setPool(pool);
        entry.setEntryType(type);
        entry.setDays(round2(days));
        entry.setEffectiveDate(effectiveDate);
        entry.setSource(source);
        entry.setSourceRef(sourceRef);
        entry.setNote(note);
        entry.setCreatedBy(createdBy);
        return entry;
    }

    // ── Transfers (ferieoverførsel) ───────────────────────────────────────

    /**
     * Posts a transfer of {@code days} from {@code fromYear} to the following
     * ferieår, split by the rules: statutory ferie first, but only what is
     * beyond the protected 20 days; feriefridage cover the rest (they carry
     * over by agreement in full). A remainder beyond both — possible when a
     * signed agreement outruns the ledger — still posts, against ferie, so
     * the written agreement and the ledger never disagree.
     *
     * <p>Idempotent per {@code sourceRef}: a second call with the same ref
     * (e.g. a retried HR-letter approval) is a no-op.</p>
     */
    @Transactional
    public void applyTransfer(String useruuid, int fromYear, double days,
                              VacationEntrySource source, String sourceRef, String note, String actorUuid) {
        if (days <= 0 || days > 25) {
            throw new BadRequestException("Transfer days must be between 0 and 25");
        }
        if (VacationLedgerEntry.existsBySourceRef(sourceRef)) {
            log.infof("vacation: transfer %s already posted — skipping", sourceRef);
            return;
        }

        VacationBalanceEngine.Result result = balanceService.computeForUser(useruuid);
        double transferableFerie = poolFigure(result, fromYear, VacationPoolType.FERIE);
        double transferableFf = poolFigure(result, fromYear, VacationPoolType.FERIEFRIDAGE);

        double feriePart = Math.min(days, transferableFerie);
        double ffPart = Math.min(days - feriePart, transferableFf);
        double remainder = round2(days - feriePart - ffPart);
        if (remainder > EPS) {
            log.warnf("vacation: transfer %s for %s exceeds transferable days by %.2f — posting against ferie per the signed agreement",
                    sourceRef, useruuid, remainder);
            feriePart += remainder;
        }

        LocalDate today = LocalDate.now(COPENHAGEN);
        postTransferPair(useruuid, fromYear, VacationPoolType.FERIE, feriePart, today, source, sourceRef, note, actorUuid);
        postTransferPair(useruuid, fromYear, VacationPoolType.FERIEFRIDAGE, ffPart, today, source, sourceRef, note, actorUuid);
        log.infof("vacation: transfer posted for %s — %s: ferie %.2f, feriefridage %.2f, %d -> %d",
                useruuid, sourceRef, feriePart, ffPart, fromYear, fromYear + 1);
    }

    private void postTransferPair(String useruuid, int fromYear, VacationPoolType pool, double days,
                                  LocalDate effectiveDate, VacationEntrySource source, String sourceRef,
                                  String note, String actorUuid) {
        if (days <= EPS) return;
        buildEntry(useruuid, fromYear, pool, VacationEntryType.TRANSFER_OUT, days, effectiveDate,
                source, sourceRef, note, actorUuid).persist();
        buildEntry(useruuid, fromYear + 1, pool, VacationEntryType.TRANSFER_IN, days, effectiveDate,
                source, sourceRef, note, actorUuid).persist();
    }

    private static double poolFigure(VacationBalanceEngine.Result result, int ferieaar, VacationPoolType pool) {
        Optional<VacationBalanceEngine.PoolStatus> status = result.pools().stream()
                .filter(p -> p.ferieaar == ferieaar && p.pool == pool)
                .findFirst();
        return status.map(VacationBalanceEngine.PoolStatus::transferableNow).orElse(0.0);
    }

    // ── Manual admin postings ─────────────────────────────────────────────

    @Transactional
    public VacationLedgerEntry postManualEntry(String useruuid, int ferieaar, VacationPoolType pool,
                                               String entryType, double days, String note, String actorUuid) {
        VacationEntryType type;
        try {
            type = VacationEntryType.valueOf(entryType == null ? "" : entryType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown entry type: " + entryType);
        }
        if (type != VacationEntryType.PAYOUT && type != VacationEntryType.ADJUSTMENT) {
            throw new BadRequestException("Only PAYOUT and ADJUSTMENT can be posted manually — transfers use the transfer endpoint");
        }
        if (pool == null) {
            throw new BadRequestException("A pool (FERIE or FERIEFRIDAGE) is required");
        }
        if (type == VacationEntryType.PAYOUT && days <= 0) {
            throw new BadRequestException("Payout days must be positive");
        }
        if (Math.abs(days) < EPS || Math.abs(days) > 50) {
            throw new BadRequestException("Days must be between -50 and 50 and not zero");
        }

        VacationLedgerEntry entry = buildEntry(useruuid, ferieaar, pool, type, days,
                LocalDate.now(COPENHAGEN), VacationEntrySource.ADMIN, UUID.randomUUID().toString(), note, actorUuid);
        entry.persist();
        log.infof("vacation: %s posted for %s by %s — %s %s %.2f days (%s)",
                type, useruuid, actorUuid, ferieaar, pool, days, note);
        return entry;
    }
}
