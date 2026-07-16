package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.invoice.services.RegisteredDeliveryEvidenceResolver;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Typed item-to-recipient allocation kernel.
 *
 * <p>The first complete source in the category-specific precedence wins. Identified partial
 * evidence may close only through an explicit {@code UNASSIGNED} residual; identified invalid
 * evidence stops rather than falling through. Every valued item then conserves exactly through
 * the shared signed balanced-cent allocator.</p>
 */
@ApplicationScoped
public class PracticeRevenueAllocationService {

    private static final BigDecimal ZERO_SHARE = BigDecimal.ZERO.setScale(18);
    private static final Set<String> CORE_PRACTICES = Set.of("PM", "BA", "CYB", "DEV", "SA");

    public AllocationResult allocate(AllocationRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.item(), "item");
        if (request.item().itemControlDkk() == null) {
            return new AllocationResult(request.item().itemControlKey(), List.of(),
                    AttributionSource.NONE, AttributionStatus.UNASSIGNED,
                    ReasonCode.UNVALUED_ITEM, ZERO_SHARE, BigDecimal.ZERO.setScale(2));
        }
        if (request.item().syntheticResidual()) {
            return wholeItemUnassigned(request, ReasonCode.CONTROLLED_DOCUMENT_RESIDUAL);
        }

        Map<SourceTier, SourceEvidence> byTier = new EnumMap<>(SourceTier.class);
        for (SourceEvidence source : request.sources() == null ? List.<SourceEvidence>of() : request.sources()) {
            if (source == null || byTier.putIfAbsent(source.tier(), source) != null) {
                return wholeItemUnassigned(request, ReasonCode.CONTRADICTORY_EVIDENCE);
            }
        }

        for (SourceTier tier : precedence(request.item(), request.documentType())) {
            SourceEvidence source = byTier.get(tier);
            if (source == null || source.state() == EvidenceState.ABSENT) continue;
            if (source.state() == EvidenceState.INVALID) {
                return wholeItemUnassigned(request,
                        source.reason() == ReasonCode.NONE ? ReasonCode.ATTRIBUTION_INVALID : source.reason());
            }
            SourceResolution resolution = resolveSource(source);
            if (resolution.outcome() == SourceOutcome.ZERO_OR_ABSENT) continue;
            if (resolution.outcome() == SourceOutcome.INVALID) {
                return wholeItemUnassigned(request, resolution.reason());
            }
            return allocateResolved(request, source, resolution);
        }
        return wholeItemUnassigned(request, ReasonCode.ATTRIBUTION_MISSING);
    }

    /**
     * Resolves recipient scope for a non-monetary item without fabricating an amount or allocation.
     * The same category-specific source precedence, share validation, and segment classification as
     * {@link #allocate(AllocationRequest)} are used; only the monetary cent-allocation step is skipped.
     */
    public EvidenceScope resolveEvidenceScope(AllocationRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.item(), "item");

        Map<SourceTier, SourceEvidence> byTier = new EnumMap<>(SourceTier.class);
        for (SourceEvidence source : request.sources() == null ? List.<SourceEvidence>of() : request.sources()) {
            if (source == null || byTier.putIfAbsent(source.tier(), source) != null) {
                return EvidenceScope.ambiguous(ReasonCode.CONTRADICTORY_EVIDENCE);
            }
        }

        for (SourceTier tier : precedence(request.item(), request.documentType())) {
            SourceEvidence source = byTier.get(tier);
            if (source == null || source.state() == EvidenceState.ABSENT) continue;
            if (source.state() == EvidenceState.INVALID) {
                return EvidenceScope.ambiguous(
                        source.reason() == ReasonCode.NONE ? ReasonCode.ATTRIBUTION_INVALID : source.reason());
            }
            SourceResolution resolution = resolveSource(source);
            if (resolution.outcome() == SourceOutcome.ZERO_OR_ABSENT) continue;
            if (resolution.outcome() == SourceOutcome.INVALID) {
                return EvidenceScope.ambiguous(resolution.reason());
            }

            Set<SegmentId> segments = resolution.recipients().stream()
                    .map(ResolvedRecipient::segmentId).collect(java.util.stream.Collectors.toSet());
            if (segments.size() != 1 || segments.contains(SegmentId.UNASSIGNED)) {
                return EvidenceScope.ambiguous(resolution.reason() == ReasonCode.NONE
                        ? ReasonCode.ATTRIBUTION_INVALID : resolution.reason());
            }
            Set<String> practiceBases = resolution.recipients().stream()
                    .map(recipient -> recipient.historicalPracticeFallback()
                            ? "CURRENT_PRACTICE_FALLBACK" : "HISTORY")
                    .collect(java.util.stream.Collectors.toSet());
            SegmentId segment = segments.iterator().next();
            String consultantTypeBasis = segment == SegmentId.EXTERNAL ? "EXTERNAL" : "INTERNAL";
            return EvidenceScope.resolved(segment,
                    practiceBases.size() == 1 ? practiceBases.iterator().next() : "MIXED",
                    consultantTypeBasis);
        }
        return EvidenceScope.unresolved(ReasonCode.ATTRIBUTION_MISSING);
    }

    private List<SourceTier> precedence(
            PracticeRevenueValuationService.ItemControl item,
            PracticeRevenueValuationService.DocumentType type) {
        if (item.rowKind() == PracticeRevenueValuationService.ItemRowKind.DOCUMENT_RESIDUAL) {
            return List.of();
        }
        if (type == PracticeRevenueValuationService.DocumentType.CREDIT_NOTE) {
            return List.of(SourceTier.CREDIT_COPY, SourceTier.CREDIT_SOURCE_ITEM,
                    SourceTier.CREDIT_EXACT_RULE, SourceTier.CREDIT_SOURCE_INVOICE);
        }
        if (type == PracticeRevenueValuationService.DocumentType.PHANTOM) {
            return List.of(SourceTier.HUMAN, SourceTier.PHANTOM_AUTO);
        }
        if (item.itemCategory() == PracticeRevenueValuationService.ItemCategory.COMMERCIAL_ADJUSTMENT) {
            return List.of(SourceTier.BASE_DISTRIBUTION);
        }
        return List.of(SourceTier.HUMAN, SourceTier.PERSISTED,
                SourceTier.PROSPECTIVE_DELIVERY_LINEAGE, SourceTier.LEGACY_DIRECT,
                SourceTier.REGISTERED_VALUE, SourceTier.REGISTERED_HOURS);
    }

    private SourceResolution resolveSource(SourceEvidence source) {
        if (source.candidates() == null || source.candidates().isEmpty()) {
            return new SourceResolution(SourceOutcome.ZERO_OR_ABSENT, List.of(), ZERO_SHARE,
                    false, ReasonCode.NONE);
        }
        Set<String> sourceIds = new HashSet<>();
        Map<CanonicalRecipientKey, CanonicalAccumulator> canonical = new LinkedHashMap<>();
        try {
            for (RecipientCandidate candidate : source.candidates()) {
                if (candidate == null || blank(candidate.sourceRecordUuid())
                        || !sourceIds.add(candidate.sourceRecordUuid())) {
                    return SourceResolution.invalid(ReasonCode.DUPLICATE_SOURCE_RECORD);
                }
                if (candidate.explicitUnassigned()) {
                    return SourceResolution.invalid(ReasonCode.CONTRADICTORY_EVIDENCE);
                }
                if (blank(candidate.consultantUuid())) {
                    return SourceResolution.invalid(ReasonCode.MISSING_RECIPIENT);
                }
                BigDecimal raw = normalizeFraction(candidate.rawFraction());
                CanonicalRecipientKey key = new CanonicalRecipientKey(candidate.consultantUuid(),
                        candidate.deliveryStart(), candidate.deliveryEndExclusive(),
                        candidate.attributionSourceType());
                canonical.computeIfAbsent(key, ignored -> new CanonicalAccumulator(candidate))
                        .add(raw, candidate.sourceRecordUuid());
            }
        } catch (RuntimeException failure) {
            return SourceResolution.invalid(ReasonCode.ATTRIBUTION_INVALID);
        }

        List<DeterministicShareNormalizer.Candidate<CanonicalRecipientKey>> normalizerCandidates = canonical.entrySet()
                .stream().map(entry -> new DeterministicShareNormalizer.Candidate<>(
                        entry.getKey(), entry.getValue().rawFraction)).toList();
        CanonicalRecipientKey unassigned = CanonicalRecipientKey.unassigned();
        final DeterministicShareNormalizer.Result<CanonicalRecipientKey> normalized;
        try {
            normalized = DeterministicShareNormalizer.normalize(normalizerCandidates,
                    source.residualPermitted(), unassigned, CanonicalRecipientKey.ORDER);
        } catch (DeterministicShareNormalizer.NormalizationException failure) {
            return SourceResolution.invalid(ReasonCode.ATTRIBUTION_INVALID);
        }
        if (normalized.outcome() == DeterministicShareNormalizer.Outcome.UNUSABLE_ZERO) {
            return new SourceResolution(SourceOutcome.ZERO_OR_ABSENT, List.of(), normalized.rawShareSum(),
                    normalized.normalizationApplied(), ReasonCode.NONE);
        }

        List<ResolvedRecipient> recipients = new ArrayList<>(normalized.shares().size());
        for (var share : normalized.shares()) {
            if (share.effectiveShare().signum() == 0) continue;
            if (share.residual()) {
                recipients.add(ResolvedRecipient.unassigned(share, normalized.rawShareSum()));
                continue;
            }
            CanonicalAccumulator accumulator = canonical.get(share.stableKey());
            RecipientCandidate exemplar = accumulator.exemplar;
            SegmentResolution segment = classifySegment(exemplar);
            if (segment.segmentId() == SegmentId.UNASSIGNED) {
                return SourceResolution.invalid(segment.reason());
            }
            recipients.add(new ResolvedRecipient(exemplar.consultantUuid(), segment.segmentId(),
                    segment.effectivePracticeCode(), exemplar.practiceResolutionMethod(),
                    exemplar.historicalPracticeFallback(), exemplar.inheritedCreditResolution(),
                    exemplar.sourceAllocationReference(), exemplar.sourceDependencyReference(),
                    source.source(), source.attributionStatus(), share.rawShare(), share.effectiveShare(),
                    normalized.rawShareSum(), share.closureRow(), normalized.normalizationApplied(),
                    List.copyOf(accumulator.sortedSourceIds()), exemplar.deliveryStart(),
                    exemplar.deliveryEndExclusive(), exemplar.attributionSourceType(), null));
        }
        return new SourceResolution(SourceOutcome.USABLE, List.copyOf(recipients),
                normalized.rawShareSum(), normalized.normalizationApplied(),
                normalized.outcome() == DeterministicShareNormalizer.Outcome.PARTIAL_WITH_RESIDUAL
                        ? ReasonCode.PARTIAL_SOURCE_RESIDUAL : ReasonCode.NONE);
    }

    private AllocationResult allocateResolved(
            AllocationRequest request, SourceEvidence source, SourceResolution resolution) {
        Map<FinalRecipientKey, FinalAccumulator> grouped = new LinkedHashMap<>();
        for (ResolvedRecipient recipient : resolution.recipients()) {
            FinalRecipientKey key = new FinalRecipientKey(recipient.consultantUuid(), recipient.segmentId(),
                    recipient.effectivePracticeCode(), recipient.practiceResolutionMethod(),
                    recipient.historicalPracticeFallback(), recipient.inheritedCreditResolution(),
                    recipient.deliveryStart(), recipient.deliveryEndExclusive(),
                    recipient.attributionSourceType(), recipient.status(), recipient.residualReason(),
                    recipient.sourceAllocationReference(), recipient.sourceDependencyReference());
            grouped.computeIfAbsent(key, ignored -> new FinalAccumulator(recipient)).add(recipient);
        }

        List<FinalAccumulator> ordered = new ArrayList<>(grouped.values());
        ordered.sort(Comparator.comparing(accumulator -> accumulator.stableKey(), AllocationStableKey.ORDER));
        List<BalancedCentAllocator.Candidate<AllocationStableKey>> moneyCandidates = ordered.stream()
                .map(accumulator -> new BalancedCentAllocator.Candidate<>(accumulator.stableKey(),
                        request.item().itemControlDkk().multiply(accumulator.effectiveShare)))
                .toList();
        final BalancedCentAllocator.Result<AllocationStableKey> money;
        try {
            money = BalancedCentAllocator.allocate(request.item().itemControlDkk(), moneyCandidates,
                    AllocationStableKey.ORDER, BalancedCentAllocator.TargetMode.AUTHORITATIVE);
        } catch (BalancedCentAllocator.AllocationException failure) {
            return wholeItemUnassigned(request, ReasonCode.ROUNDING_CONSERVATION_FAILURE);
        }
        Map<AllocationStableKey, BalancedCentAllocator.Allocation<AllocationStableKey>> moneyByKey =
                money.allocations().stream().collect(java.util.stream.Collectors.toMap(
                        BalancedCentAllocator.Allocation::stableKey, value -> value));

        List<Allocation> allocations = new ArrayList<>(ordered.size());
        BigDecimal sum = BigDecimal.ZERO.setScale(2);
        int sequence = 0;
        for (FinalAccumulator accumulator : ordered) {
            var rounded = moneyByKey.get(accumulator.stableKey());
            allocations.add(new Allocation(sequence++, accumulator.consultantUuid,
                    accumulator.segmentId, accumulator.effectivePracticeCode,
                    accumulator.practiceResolutionMethod, accumulator.historicalPracticeFallback,
                    accumulator.inheritedCreditResolution, accumulator.sourceAllocationReference,
                    accumulator.sourceDependencyReference, source.source(), accumulator.status,
                    accumulator.rawFraction, accumulator.effectiveShare, resolution.rawShareSum(),
                    accumulator.closureRow, resolution.normalizationApplied(),
                    List.copyOf(accumulator.sortedSourceIds()), rounded.unroundedAmount(),
                    rounded.floorAmount(), rounded.fractionalCent(), rounded.centAwarded(),
                    rounded.roundedAmount(), accumulator.deliveryStart, accumulator.deliveryEndExclusive,
                    accumulator.attributionSourceType, accumulator.residualReason));
            sum = sum.add(rounded.roundedAmount());
        }
        if (sum.compareTo(request.item().itemControlDkk()) != 0) {
            return wholeItemUnassigned(request, ReasonCode.ROUNDING_CONSERVATION_FAILURE);
        }
        AttributionStatus worst = allocations.stream().anyMatch(a -> a.status() == AttributionStatus.UNASSIGNED)
                ? AttributionStatus.UNASSIGNED : source.attributionStatus();
        return new AllocationResult(request.item().itemControlKey(), List.copyOf(allocations),
                source.source(), worst, resolution.reason(), resolution.rawShareSum(), sum);
    }

    private AllocationResult wholeItemUnassigned(AllocationRequest request, ReasonCode reason) {
        BigDecimal amount = request.item().itemControlDkk();
        if (amount == null) {
            return new AllocationResult(request.item().itemControlKey(), List.of(),
                    AttributionSource.NONE, AttributionStatus.UNASSIGNED, reason, ZERO_SHARE,
                    BigDecimal.ZERO.setScale(2));
        }
        Allocation allocation = new Allocation(0, null, SegmentId.UNASSIGNED, null,
                PracticeResolutionMethod.NONE, false, false, null, null,
                AttributionSource.UNASSIGNED, AttributionStatus.UNASSIGNED,
                DeterministicShareNormalizer.ONE, DeterministicShareNormalizer.ONE,
                DeterministicShareNormalizer.ONE, true, false, List.of(), amount,
                amount, BigDecimal.ZERO, false, amount, null, null,
                "UNASSIGNED", reason);
        return new AllocationResult(request.item().itemControlKey(), List.of(allocation),
                AttributionSource.UNASSIGNED, AttributionStatus.UNASSIGNED, reason,
                DeterministicShareNormalizer.ONE, amount);
    }

    /** Builds the Tier-5/Tier-6 source from canonical non-coalesced delivery evidence. */
    public SourceEvidence registeredDeliveryEvidence(
            List<RegisteredDeliveryEvidenceResolver.ResolvedDelivery> rows,
            Function<RegisteredDeliveryEvidenceResolver.ResolvedDelivery, RecipientIdentity> resolver) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(resolver, "resolver");
        if (rows.isEmpty()) return SourceEvidence.absent(SourceTier.REGISTERED_VALUE);
        if (rows.stream().anyMatch(row -> row == null || !row.usableForContribution())) {
            return SourceEvidence.invalid(SourceTier.REGISTERED_VALUE,
                    AttributionSource.REGISTERED_VALUE, ReasonCode.DELIVERY_EVIDENCE_AMBIGUOUS);
        }

        Map<DeliveryKey, DeliveryAccumulator> grouped = new HashMap<>();
        for (var row : rows) {
            RecipientIdentity identity = resolver.apply(row);
            if (identity == null || blank(identity.consultantUuid())) {
                return SourceEvidence.invalid(SourceTier.REGISTERED_VALUE,
                        AttributionSource.REGISTERED_VALUE, ReasonCode.MISSING_RECIPIENT);
            }
            DeliveryKey key = new DeliveryKey(identity, row.deliveryDate());
            grouped.computeIfAbsent(key, ignored -> new DeliveryAccumulator(identity, row.deliveryDate()))
                    .add(row);
        }
        boolean everyValueZero = grouped.values().stream()
                .map(accumulator -> accumulator.deliveryValue.max(BigDecimal.ZERO))
                .allMatch(value -> value.signum() == 0);
        List<WeightedRecipient> weights = grouped.values().stream()
                .map(accumulator -> new WeightedRecipient(accumulator,
                        everyValueZero ? accumulator.duration.max(BigDecimal.ZERO)
                                : accumulator.deliveryValue.max(BigDecimal.ZERO)))
                .filter(weight -> weight.weight().signum() > 0)
                .sorted(Comparator
                        .comparing((WeightedRecipient weight) -> weight.accumulator().identity.consultantUuid())
                        .thenComparing(weight -> weight.accumulator().date))
                .toList();
        if (weights.isEmpty()) {
            return SourceEvidence.absent(everyValueZero
                    ? SourceTier.REGISTERED_HOURS : SourceTier.REGISTERED_VALUE);
        }
        BigDecimal total = weights.stream().map(WeightedRecipient::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<RecipientCandidate> candidates = weights.stream().map(weight -> {
            DeliveryAccumulator accumulator = weight.accumulator();
            BigDecimal fraction = weight.weight().divide(total, MathContext.DECIMAL128)
                    .setScale(18, RoundingMode.HALF_UP);
            return new RecipientCandidate(String.join("+", accumulator.workUuids),
                    accumulator.identity.consultantUuid(), accumulator.identity.practiceCode(),
                    accumulator.identity.consultantType(), accumulator.date,
                    accumulator.date.plusDays(1), "REGISTERED_DELIVERY", fraction,
                    accumulator.identity.practiceResolutionMethod(),
                    accumulator.identity.historicalPracticeFallback(), false,
                    null, null, null, false);
        }).toList();
        SourceTier tier = everyValueZero ? SourceTier.REGISTERED_HOURS : SourceTier.REGISTERED_VALUE;
        AttributionSource attributionSource = everyValueZero
                ? AttributionSource.REGISTERED_HOURS : AttributionSource.REGISTERED_VALUE;
        return new SourceEvidence(tier, EvidenceState.PRESENT, attributionSource,
                AttributionStatus.ESTIMATED, true, candidates, ReasonCode.NONE);
    }

    public static Coverage coverage(Collection<AllocationResult> results) {
        BigDecimal usable = BigDecimal.ZERO.setScale(2);
        BigDecimal confirmed = BigDecimal.ZERO.setScale(2);
        BigDecimal attributed = BigDecimal.ZERO.setScale(2);
        BigDecimal partialAffected = BigDecimal.ZERO.setScale(2);
        for (AllocationResult result : results) {
            for (Allocation allocation : result.allocations()) {
                BigDecimal movement = allocation.allocationDkk().abs();
                usable = usable.add(movement);
                if (allocation.status() == AttributionStatus.CONFIRMED
                        && allocation.segmentId() != SegmentId.UNASSIGNED) confirmed = confirmed.add(movement);
                if (allocation.status() != AttributionStatus.UNASSIGNED
                        && allocation.segmentId() != SegmentId.UNASSIGNED) attributed = attributed.add(movement);
                if (allocation.status() == AttributionStatus.UNASSIGNED
                        || allocation.segmentId() == SegmentId.UNASSIGNED) partialAffected = partialAffected.add(movement);
            }
        }
        return new Coverage(usable, confirmed, attributed, partialAffected);
    }

    private SegmentResolution classifySegment(RecipientCandidate candidate) {
        if (candidate.explicitUnassigned()) return new SegmentResolution(SegmentId.UNASSIGNED, null, ReasonCode.ATTRIBUTION_MISSING);
        if (candidate.consultantType() == null) return new SegmentResolution(SegmentId.UNASSIGNED, null, ReasonCode.MISSING_CONSULTANT_TYPE);
        if (candidate.inheritedCreditResolution() && candidate.inheritedSegmentId() != null) {
            return new SegmentResolution(candidate.inheritedSegmentId(), candidate.practiceCode(), ReasonCode.NONE);
        }
        if (candidate.consultantType() == ConsultantType.EXTERNAL) {
            return new SegmentResolution(SegmentId.EXTERNAL, candidate.practiceCode(), ReasonCode.NONE);
        }
        String practice = candidate.practiceCode() == null ? null : candidate.practiceCode().trim().toUpperCase();
        if (CORE_PRACTICES.contains(practice)) return new SegmentResolution(SegmentId.valueOf(practice), practice, ReasonCode.NONE);
        if ("JK".equals(practice)) return new SegmentResolution(SegmentId.JK, practice, ReasonCode.NONE);
        if (practice == null || practice.isBlank() || "UD".equals(practice)) return new SegmentResolution(SegmentId.UD, practice, ReasonCode.NONE);
        return new SegmentResolution(SegmentId.OTHER, practice, ReasonCode.NONE);
    }

    private static BigDecimal normalizeFraction(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("fraction outside [0,1]");
        }
        BigDecimal result = value.setScale(18, RoundingMode.HALF_UP);
        if (result.precision() > 38) throw new IllegalArgumentException("fraction overflow");
        return result;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public enum EvidenceState { ABSENT, PRESENT, INVALID }
    public enum SourceTier {
        HUMAN, PERSISTED, PROSPECTIVE_DELIVERY_LINEAGE, LEGACY_DIRECT,
        REGISTERED_VALUE, REGISTERED_HOURS, BASE_DISTRIBUTION,
        CREDIT_COPY, CREDIT_SOURCE_ITEM, CREDIT_EXACT_RULE, CREDIT_SOURCE_INVOICE,
        PHANTOM_AUTO
    }
    public enum AttributionSource {
        HUMAN, PERSISTED_MANUAL, PERSISTED_AUTO, PROSPECTIVE_DELIVERY_LINEAGE,
        LEGACY_DIRECT_FALLBACK, REGISTERED_VALUE, REGISTERED_HOURS, BASE_DISTRIBUTION,
        CREDIT_COPY, CREDIT_SOURCE_ITEM, CREDIT_EXACT_RULE, CREDIT_SOURCE_INVOICE,
        PHANTOM_AUTO, UNASSIGNED, NONE
    }
    public enum AttributionStatus { CONFIRMED, ESTIMATED, UNASSIGNED }
    public enum ConsultantType { INTERNAL, EXTERNAL }
    public enum SegmentId { PM, BA, CYB, DEV, SA, JK, UD, EXTERNAL, OTHER, UNASSIGNED }
    public enum PracticeResolutionMethod { DATED_DELIVERY, SCHEDULED_CAPACITY, MONTH_END_PRACTICE, NONE }
    public enum ScopeResolutionStatus { RESOLVED, UNRESOLVED, AMBIGUOUS }
    public enum ReasonCode {
        NONE, UNVALUED_ITEM, ATTRIBUTION_MISSING, ATTRIBUTION_INVALID,
        DUPLICATE_SOURCE_RECORD, MISSING_RECIPIENT, MISSING_CONSULTANT_TYPE,
        CONTRADICTORY_EVIDENCE, DELIVERY_EVIDENCE_AMBIGUOUS,
        PARTIAL_SOURCE_RESIDUAL, CONTROLLED_DOCUMENT_RESIDUAL,
        ROUNDING_CONSERVATION_FAILURE
    }
    private enum SourceOutcome { USABLE, ZERO_OR_ABSENT, INVALID }

    public record RecipientCandidate(
            String sourceRecordUuid, String consultantUuid, String practiceCode,
            ConsultantType consultantType, LocalDate deliveryStart, LocalDate deliveryEndExclusive,
            String attributionSourceType, BigDecimal rawFraction,
            PracticeResolutionMethod practiceResolutionMethod, boolean historicalPracticeFallback,
            boolean inheritedCreditResolution, SegmentId inheritedSegmentId,
            String sourceAllocationReference, String sourceDependencyReference,
            boolean explicitUnassigned) {
    }

    public record SourceEvidence(
            SourceTier tier, EvidenceState state, AttributionSource source,
            AttributionStatus attributionStatus, boolean residualPermitted,
            List<RecipientCandidate> candidates, ReasonCode reason) {
        public SourceEvidence {
            Objects.requireNonNull(tier, "tier");
            Objects.requireNonNull(state, "state");
            source = source == null ? AttributionSource.NONE : source;
            attributionStatus = attributionStatus == null ? AttributionStatus.ESTIMATED : attributionStatus;
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            reason = reason == null ? ReasonCode.NONE : reason;
        }
        public static SourceEvidence absent(SourceTier tier) {
            return new SourceEvidence(tier, EvidenceState.ABSENT, AttributionSource.NONE,
                    AttributionStatus.ESTIMATED, false, List.of(), ReasonCode.NONE);
        }
        public static SourceEvidence invalid(SourceTier tier, AttributionSource source, ReasonCode reason) {
            return new SourceEvidence(tier, EvidenceState.INVALID, source,
                    AttributionStatus.UNASSIGNED, false, List.of(), reason);
        }
    }

    public record AllocationRequest(
            PracticeRevenueValuationService.ItemControl item,
            PracticeRevenueValuationService.DocumentType documentType,
            List<SourceEvidence> sources) {
        public AllocationRequest {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(documentType, "documentType");
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    public record EvidenceScope(
            SegmentId resolvedSegment, String practiceBasis, String consultantTypeBasis,
            ScopeResolutionStatus status, ReasonCode reason) {
        public EvidenceScope {
            Objects.requireNonNull(status, "status");
            reason = reason == null ? ReasonCode.NONE : reason;
            if (status == ScopeResolutionStatus.RESOLVED && resolvedSegment == null) {
                throw new IllegalArgumentException("resolved evidence scope requires a segment");
            }
            if (status != ScopeResolutionStatus.RESOLVED
                    && (resolvedSegment != null || practiceBasis != null || consultantTypeBasis != null)) {
                throw new IllegalArgumentException("unresolved evidence scope cannot carry a recipient");
            }
        }
        static EvidenceScope resolved(SegmentId segment, String practiceBasis, String consultantTypeBasis) {
            return new EvidenceScope(segment, practiceBasis, consultantTypeBasis,
                    ScopeResolutionStatus.RESOLVED, ReasonCode.NONE);
        }
        static EvidenceScope unresolved(ReasonCode reason) {
            return new EvidenceScope(null, null, null, ScopeResolutionStatus.UNRESOLVED, reason);
        }
        static EvidenceScope ambiguous(ReasonCode reason) {
            return new EvidenceScope(null, null, null, ScopeResolutionStatus.AMBIGUOUS, reason);
        }
    }

    public record Allocation(
            int sequence, String consultantUuid, SegmentId segmentId,
            String effectivePracticeCode, PracticeResolutionMethod practiceResolutionMethod,
            boolean historicalPracticeFallback, boolean inheritedCreditResolution,
            String sourceAllocationReference, String sourceDependencyReference,
            AttributionSource source, AttributionStatus status,
            BigDecimal rawFraction, BigDecimal effectiveFraction, BigDecimal rawShareSum,
            boolean closureRow, boolean normalizationApplied, List<String> contributingSourceIds,
            BigDecimal unroundedAllocationDkk, BigDecimal floorAllocationDkk,
            BigDecimal fractionalCentResidue, boolean oneCentAwarded, BigDecimal allocationDkk,
            LocalDate deliveryStart, LocalDate deliveryEndExclusive,
            String attributionSourceType, ReasonCode residualReason) {
        public Allocation { contributingSourceIds = List.copyOf(contributingSourceIds); }
    }

    public record AllocationResult(
            String itemControlKey, List<Allocation> allocations, AttributionSource source,
            AttributionStatus status, ReasonCode reason, BigDecimal rawShareSum,
            BigDecimal allocatedControlDkk) {
        public AllocationResult { allocations = List.copyOf(allocations); }
    }

    public record RecipientIdentity(
            String consultantUuid, String practiceCode, ConsultantType consultantType,
            PracticeResolutionMethod practiceResolutionMethod, boolean historicalPracticeFallback) { }

    public record Coverage(
            BigDecimal usableAbsoluteMovementDkk, BigDecimal confirmedAttributedAbsoluteMovementDkk,
            BigDecimal attributedAbsoluteMovementDkk, BigDecimal partialAttributionAffectedRevenueDkk) { }

    private record CanonicalRecipientKey(
            String consultantUuid, LocalDate start, LocalDate endExclusive, String sourceType) {
        static final Comparator<CanonicalRecipientKey> ORDER = Comparator
                .comparing(CanonicalRecipientKey::consultantUuid, Comparator.nullsLast(String::compareTo))
                .thenComparing(CanonicalRecipientKey::start, Comparator.nullsLast(LocalDate::compareTo))
                .thenComparing(CanonicalRecipientKey::endExclusive, Comparator.nullsLast(LocalDate::compareTo))
                .thenComparing(CanonicalRecipientKey::sourceType, Comparator.nullsLast(String::compareTo));
        static CanonicalRecipientKey unassigned() { return new CanonicalRecipientKey(null, null, null, "UNASSIGNED"); }
    }

    private static final class CanonicalAccumulator {
        private final RecipientCandidate exemplar;
        private BigDecimal rawFraction = ZERO_SHARE;
        private final Set<String> sourceIds = new HashSet<>();
        private CanonicalAccumulator(RecipientCandidate exemplar) { this.exemplar = exemplar; }
        private void add(BigDecimal value, String sourceId) {
            rawFraction = rawFraction.add(value);
            if (rawFraction.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("merged share overflow");
            sourceIds.add(sourceId);
        }
        private List<String> sortedSourceIds() { return sourceIds.stream().sorted().toList(); }
    }

    private record ResolvedRecipient(
            String consultantUuid, SegmentId segmentId, String effectivePracticeCode,
            PracticeResolutionMethod practiceResolutionMethod, boolean historicalPracticeFallback,
            boolean inheritedCreditResolution, String sourceAllocationReference,
            String sourceDependencyReference, AttributionSource source, AttributionStatus status,
            BigDecimal rawFraction, BigDecimal effectiveFraction, BigDecimal rawShareSum,
            boolean closureRow, boolean normalizationApplied, List<String> sourceIds,
            LocalDate deliveryStart, LocalDate deliveryEndExclusive,
            String attributionSourceType, ReasonCode residualReason) {
        static ResolvedRecipient unassigned(
                DeterministicShareNormalizer.NormalizedShare<CanonicalRecipientKey> share,
                BigDecimal rawShareSum) {
            return new ResolvedRecipient(null, SegmentId.UNASSIGNED, null,
                    PracticeResolutionMethod.NONE, false, false, null, null,
                    AttributionSource.UNASSIGNED, AttributionStatus.UNASSIGNED,
                    share.rawShare(), share.effectiveShare(), rawShareSum, share.closureRow(),
                    true, List.of(), null, null, "UNASSIGNED", ReasonCode.PARTIAL_SOURCE_RESIDUAL);
        }
    }

    private record SourceResolution(
            SourceOutcome outcome, List<ResolvedRecipient> recipients, BigDecimal rawShareSum,
            boolean normalizationApplied, ReasonCode reason) {
        static SourceResolution invalid(ReasonCode reason) {
            return new SourceResolution(SourceOutcome.INVALID, List.of(), ZERO_SHARE, false, reason);
        }
    }
    private record SegmentResolution(SegmentId segmentId, String effectivePracticeCode, ReasonCode reason) { }
    private record FinalRecipientKey(
            String consultantUuid, SegmentId segmentId, String practice,
            PracticeResolutionMethod method, boolean historical, boolean inherited,
            LocalDate start, LocalDate end, String sourceType, AttributionStatus status,
            ReasonCode residualReason, String sourceAllocationReference,
            String sourceDependencyReference) { }

    private static final class FinalAccumulator {
        private final String consultantUuid;
        private final SegmentId segmentId;
        private final String effectivePracticeCode;
        private final PracticeResolutionMethod practiceResolutionMethod;
        private final boolean historicalPracticeFallback;
        private final boolean inheritedCreditResolution;
        private final String sourceAllocationReference;
        private final String sourceDependencyReference;
        private final AttributionStatus status;
        private final LocalDate deliveryStart;
        private final LocalDate deliveryEndExclusive;
        private final String attributionSourceType;
        private final ReasonCode residualReason;
        private BigDecimal rawFraction = ZERO_SHARE;
        private BigDecimal effectiveShare = ZERO_SHARE;
        private boolean closureRow;
        private final Set<String> sourceIds = new HashSet<>();
        private FinalAccumulator(ResolvedRecipient recipient) {
            consultantUuid = recipient.consultantUuid(); segmentId = recipient.segmentId();
            effectivePracticeCode = recipient.effectivePracticeCode();
            practiceResolutionMethod = recipient.practiceResolutionMethod();
            historicalPracticeFallback = recipient.historicalPracticeFallback();
            inheritedCreditResolution = recipient.inheritedCreditResolution();
            sourceAllocationReference = recipient.sourceAllocationReference();
            sourceDependencyReference = recipient.sourceDependencyReference(); status = recipient.status();
            deliveryStart = recipient.deliveryStart(); deliveryEndExclusive = recipient.deliveryEndExclusive();
            attributionSourceType = recipient.attributionSourceType(); residualReason = recipient.residualReason();
        }
        private void add(ResolvedRecipient recipient) {
            rawFraction = rawFraction.add(recipient.rawFraction());
            effectiveShare = effectiveShare.add(recipient.effectiveFraction());
            closureRow |= recipient.closureRow(); sourceIds.addAll(recipient.sourceIds());
        }
        private List<String> sortedSourceIds() { return sourceIds.stream().sorted().toList(); }
        private AllocationStableKey stableKey() {
            return new AllocationStableKey(segmentId == SegmentId.UNASSIGNED, consultantUuid, segmentId,
                    deliveryStart, deliveryEndExclusive, attributionSourceType,
                    String.join(",", sortedSourceIds()));
        }
    }

    private record AllocationStableKey(
            boolean unassigned, String consultant, SegmentId segment, LocalDate start,
            LocalDate end, String sourceType, String sourceIds) {
        static final Comparator<AllocationStableKey> ORDER = Comparator
                .comparing(AllocationStableKey::unassigned)
                .thenComparing(AllocationStableKey::consultant, Comparator.nullsLast(String::compareTo))
                .thenComparing(key -> key.segment().name())
                .thenComparing(AllocationStableKey::start, Comparator.nullsLast(LocalDate::compareTo))
                .thenComparing(AllocationStableKey::end, Comparator.nullsLast(LocalDate::compareTo))
                .thenComparing(AllocationStableKey::sourceType, Comparator.nullsLast(String::compareTo))
                .thenComparing(AllocationStableKey::sourceIds);
    }

    private record DeliveryKey(RecipientIdentity identity, LocalDate date) { }
    private static final class DeliveryAccumulator {
        private final RecipientIdentity identity; private final LocalDate date;
        private BigDecimal deliveryValue = BigDecimal.ZERO; private BigDecimal duration = BigDecimal.ZERO;
        private final List<String> workUuids = new ArrayList<>();
        private DeliveryAccumulator(RecipientIdentity identity, LocalDate date) { this.identity = identity; this.date = date; }
        private void add(RegisteredDeliveryEvidenceResolver.ResolvedDelivery row) {
            deliveryValue = deliveryValue.add(row.deliveryValue()); duration = duration.add(row.normalizedDuration());
            workUuids.add(row.workUuid()); workUuids.sort(String::compareTo);
        }
    }
    private record WeightedRecipient(DeliveryAccumulator accumulator, BigDecimal weight) { }
}
