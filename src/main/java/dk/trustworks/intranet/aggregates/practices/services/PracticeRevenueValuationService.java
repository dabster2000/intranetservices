package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.invoice.pricing.InvoiceDiscountNormalizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure valuation kernel for the versioned practice-revenue materializer.
 *
 * <p>The database adapter owns bounded reads. This class owns the frozen population, numeric,
 * classification, GL/FX and document-to-item conservation contracts. It deliberately accepts
 * typed evidence rather than ORM objects so historical dependency documents can be valued by the
 * same code without accidentally being recognized as current revenue.</p>
 */
@ApplicationScoped
public class PracticeRevenueValuationService {

    public static final int OPERAND_SCALE = 6;
    public static final int NATIVE_SCALE = 12;
    public static final int GL_SCALE = 4;
    public static final int FX_SCALE = 8;
    public static final int MONEY_SCALE = 2;
    public static final BigDecimal MATERIAL_NATIVE_FLOOR = new BigDecimal("0.010000000000");
    public static final BigDecimal MATERIAL_RELATIVE_FACTOR = new BigDecimal("0.0001");
    public static final BigDecimal MAX_FX = new BigDecimal("999999999.99999999");

    private static final BigDecimal ZERO_NATIVE = BigDecimal.ZERO.setScale(NATIVE_SCALE);
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE);

    private final InvoiceDiscountNormalizer discountNormalizer;

    public PracticeRevenueValuationService() {
        this(new InvoiceDiscountNormalizer());
    }

    @Inject
    public PracticeRevenueValuationService(InvoiceDiscountNormalizer discountNormalizer) {
        this.discountNormalizer = Objects.requireNonNull(discountNormalizer, "discountNormalizer");
    }

    public ValuationBatch value(List<DocumentInput> documents) {
        Objects.requireNonNull(documents, "documents");
        List<DocumentInput> recognized = documents.stream().filter(this::isRecognized).toList();
        List<DocumentInput> controlPopulation = documents.stream()
                .filter(document -> isRecognized(document) || (document != null && document.dependencyOnly()))
                .toList();

        Map<String, List<DocumentInput>> inverseVoucherDocuments = new HashMap<>();
        for (DocumentInput document : controlPopulation) {
            for (String key : qualifyingVoucherKeys(document)) {
                inverseVoucherDocuments.computeIfAbsent(key, ignored -> new ArrayList<>()).add(document);
            }
        }

        List<DocumentValuation> valuations = new ArrayList<>(recognized.size());
        for (DocumentInput document : recognized) {
            valuations.add(valueRecognized(document, inverseVoucherDocuments));
        }
        return new ValuationBatch(
                List.copyOf(valuations),
                recognized.size(),
                documents.size() - recognized.size());
    }

    public boolean isRecognized(DocumentInput document) {
        if (document == null || document.type() == null || document.invoiceDate() == null) return false;
        if (document.dependencyOnly()) return false;
        if (!"CREATED".equals(document.status())) return false;
        if (!Set.of(DocumentType.INVOICE, DocumentType.PHANTOM, DocumentType.CREDIT_NOTE)
                .contains(document.type())) return false;
        return document.type() != DocumentType.CREDIT_NOTE || !document.internalDebtorCredit();
    }

    private DocumentValuation valueRecognized(
            DocumentInput document,
            Map<String, List<DocumentInput>> inverseVoucherDocuments) {
        LocalDate recognizedMonth = YearMonth.from(document.invoiceDate()).atDay(1);
        List<PreparedItem> items = prepareItems(document);
        HeaderEvidence header = evaluateHeader(document, items);
        GlResolution gl = resolveGl(document, inverseVoucherDocuments);

        if (document.type() == DocumentType.PHANTOM && items.size() != 1) {
            return unavailableDocument(document, recognizedMonth, items, gl,
                    ValuationStatus.UNAVAILABLE_PHANTOM_ITEM_GRAIN,
                    ReasonCode.PHANTOM_ITEM_GRAIN_INVALID);
        }
        if (items.isEmpty()) {
            return documentEvidenceSentinel(document, recognizedMonth, gl);
        }
        if (items.stream().anyMatch(item -> item.failureReason() != null)) {
            ReasonCode reason = items.stream().map(PreparedItem::failureReason)
                    .filter(Objects::nonNull).findFirst().orElse(ReasonCode.ITEM_NUMERIC_INVALID);
            return unavailableDocument(document, recognizedMonth, items, gl,
                    ValuationStatus.UNAVAILABLE_CLASSIFICATION, reason);
        }
        if (header.state() == HeaderState.INVALID_RANGE) {
            return unavailableDocument(document, recognizedMonth, items, gl,
                    ValuationStatus.UNAVAILABLE_CLASSIFICATION,
                    ReasonCode.HEADER_DISCOUNT_OUT_OF_RANGE);
        }

        if (gl.usable()) {
            if (header.state() == HeaderState.MONETARY_STRUCTURE_GAP) {
                if (gl.documentControl().signum() == 0 && absoluteNativeMovement(items).signum() != 0) {
                    return unavailableDocument(document, recognizedMonth, items, gl,
                            ValuationStatus.UNAVAILABLE_AMBIGUOUS,
                            ReasonCode.OFFSETTING_ITEM_CONTROL_UNAVAILABLE);
                }
                return controlledResidual(document, recognizedMonth, items, gl,
                        ReasonCode.HEADER_DISCOUNT_MONETARY_STRUCTURE_UNAVAILABLE);
            }
            return valueWithGl(document, recognizedMonth, items, header, gl);
        }

        if (gl.failureStatus() == ValuationStatus.UNAVAILABLE_DUPLICATE_RISK
                || gl.failureStatus() == ValuationStatus.UNAVAILABLE_AMBIGUOUS) {
            return unavailableDocument(document, recognizedMonth, items, gl,
                    gl.failureStatus(), gl.reason());
        }
        if (header.state() == HeaderState.MONETARY_STRUCTURE_GAP
                || header.state() == HeaderState.LEGACY_PROVENANCE_ONLY) {
            return unavailableDocument(document, recognizedMonth, items, gl,
                    ValuationStatus.UNAVAILABLE_MISSING,
                    header.state() == HeaderState.LEGACY_PROVENANCE_ONLY
                            ? ReasonCode.HEADER_DISCOUNT_PROVENANCE_UNAVAILABLE
                            : ReasonCode.HEADER_DISCOUNT_MONETARY_STRUCTURE_UNAVAILABLE);
        }
        return valueProvisionally(document, recognizedMonth, items, header, gl);
    }

    private List<PreparedItem> prepareItems(DocumentInput document) {
        if (document.items() == null) return List.of();
        List<PreparedItem> result = new ArrayList<>(document.items().size());
        short sign = (short) (document.type() == DocumentType.CREDIT_NOTE ? -1 : 1);
        for (ItemInput input : document.items()) {
            if (input == null || blank(input.itemUuid())) {
                result.add(PreparedItem.invalid("MISSING_ITEM", ReasonCode.ITEM_NUMERIC_INVALID));
                continue;
            }
            try {
                BigDecimal hours = normalizeFixed(input.hoursText(), OPERAND_SCALE, 24);
                BigDecimal rate = normalizeFixed(input.rateText(), OPERAND_SCALE, 24);
                BigDecimal nativeAmount = requireRepresentable(
                        hours.multiply(rate).setScale(NATIVE_SCALE, RoundingMode.UNNECESSARY),
                        48, NATIVE_SCALE);
                Classification classification = classify(input, nativeAmount);
                result.add(new PreparedItem(input, hours, rate, nativeAmount,
                        nativeAmount.multiply(BigDecimal.valueOf(sign)), sign,
                        classification.category(), classification.subtype(), null));
            } catch (NumericFailure failure) {
                result.add(PreparedItem.invalid(input.itemUuid(), ReasonCode.ITEM_NUMERIC_INVALID));
            } catch (ClassificationFailure failure) {
                result.add(PreparedItem.invalid(input.itemUuid(), ReasonCode.ITEM_CLASSIFICATION_UNAVAILABLE));
            }
        }
        result.sort(Comparator.comparing(item -> item.input().itemUuid()));
        return List.copyOf(result);
    }

    private Classification classify(ItemInput item, BigDecimal nativeAmount) {
        boolean calculated = item.origin() == ItemOrigin.CALCULATED
                || !blank(item.calculationRef()) || !blank(item.ruleId()) || !blank(item.label());
        if (calculated) {
            AdjustmentSubtype subtype = nativeAmount.signum() < 0
                    ? AdjustmentSubtype.DISCOUNT
                    : nativeAmount.signum() > 0
                    ? AdjustmentSubtype.FEE_OR_UPLIFT
                    : AdjustmentSubtype.ZERO_ADJUSTMENT;
            return new Classification(ItemCategory.COMMERCIAL_ADJUSTMENT, subtype);
        }
        if (item.origin() != ItemOrigin.BASE) throw new ClassificationFailure();
        if (!blank(item.consultantUuid()) || item.explicitDeliveryAnchor()) {
            return new Classification(ItemCategory.DELIVERY_BASE, null);
        }
        // Consultant-free legacy BASE lines are commercial movement. No text heuristic is used.
        return new Classification(ItemCategory.COMMERCIAL_ADJUSTMENT,
                nativeAmount.signum() < 0
                        ? AdjustmentSubtype.LEGACY_REBATE
                        : nativeAmount.signum() > 0
                        ? AdjustmentSubtype.LEGACY_FIXED_PRICE_OR_FEE
                        : AdjustmentSubtype.ZERO_ADJUSTMENT);
    }

    private HeaderEvidence evaluateHeader(DocumentInput document, List<PreparedItem> items) {
        InvoiceDiscountNormalizer.NormalizedDiscount normalized =
                discountNormalizer.normalizeText(document.headerDiscountText() == null
                        ? "0" : document.headerDiscountText());
        if (!normalized.valid()) {
            return new HeaderEvidence(HeaderState.INVALID_RANGE, normalized.originalRepresentation(),
                    null, false);
        }
        if (document.type() == DocumentType.CREDIT_NOTE || normalized.value().signum() == 0) {
            return new HeaderEvidence(HeaderState.NOT_APPLICABLE_OR_ZERO,
                    normalized.originalRepresentation(), normalized.value(), normalized.normalizationChanged());
        }

        List<PreparedItem> candidates = items.stream()
                .filter(item -> item.failureReason() == null)
                .filter(item -> item.input().headerDiscountCandidate())
                .toList();
        if (candidates.size() != 1) {
            return new HeaderEvidence(HeaderState.MONETARY_STRUCTURE_GAP,
                    normalized.originalRepresentation(), normalized.value(), normalized.normalizationChanged());
        }
        PreparedItem candidate = candidates.getFirst();
        if (candidate.input().pricingOutputAmount() != null
                && candidate.input().pricingOutputAmount().setScale(NATIVE_SCALE, RoundingMode.HALF_UP)
                .compareTo(candidate.nativeAmount()) != 0) {
            return new HeaderEvidence(HeaderState.MONETARY_STRUCTURE_GAP,
                    normalized.originalRepresentation(), normalized.value(), normalized.normalizationChanged());
        }
        boolean complete = !blank(candidate.input().pricingPolicyVersion())
                && !blank(candidate.input().pricingStepId())
                && candidate.input().pricingStepSequence() != null
                && !blank(candidate.input().pricingRuleType())
                && !blank(candidate.input().pricingInputFingerprint())
                && !blank(candidate.input().pricingOutputFingerprint())
                && candidate.input().pricingOutputAmount() != null
                && !blank(candidate.input().calculationAlgorithmVersion());
        return new HeaderEvidence(complete ? HeaderState.PROVEN : HeaderState.LEGACY_PROVENANCE_ONLY,
                normalized.originalRepresentation(), normalized.value(), normalized.normalizationChanged());
    }

    private GlResolution resolveGl(
            DocumentInput document,
            Map<String, List<DocumentInput>> inverseVoucherDocuments) {
        if (document.glEvidenceConflict()) {
            return GlResolution.ambiguous(ReasonCode.GL_CONTROL_AMBIGUOUS, null, null, null);
        }
        Map<String, List<GlEntry>> byKey = new LinkedHashMap<>();
        if (document.glEntries() != null) {
            for (GlEntry entry : document.glEntries()) {
                if (entry == null) continue;
                if (!Objects.equals(document.companyUuid(), entry.companyUuid())) continue;
                if (!"BOOKED".equals(entry.postingStatus()) || !"REVENUE".equals(entry.accountCostType())) continue;
                if (entry.voucherNumber() <= 0 || entry.financialYearStartYear() <= 0) continue;
                byKey.computeIfAbsent(canonicalVoucherKey(entry), ignored -> new ArrayList<>()).add(entry);
            }
        }
        if (byKey.isEmpty()) return GlResolution.missing();
        if (byKey.size() != 1) return GlResolution.ambiguous(ReasonCode.GL_CONTROL_AMBIGUOUS, null, null, null);

        Map.Entry<String, List<GlEntry>> only = byKey.entrySet().iterator().next();
        String key = only.getKey();
        List<DocumentInput> inverse = inverseVoucherDocuments.getOrDefault(key, List.of());
        if (inverse.size() != 1) {
            boolean duplicateRisk = inverse.size() == 2
                    && inverse.stream().map(DocumentInput::type).collect(java.util.stream.Collectors.toSet())
                    .equals(Set.of(DocumentType.INVOICE, DocumentType.PHANTOM));
            return GlResolution.ambiguous(
                    duplicateRisk ? ReasonCode.MANUAL_PHANTOM_DUPLICATE_RISK : ReasonCode.GL_CONTROL_AMBIGUOUS,
                    key, null, null);
        }

        try {
            BigDecimal financeAmountSum = BigDecimal.ZERO.setScale(GL_SCALE);
            for (GlEntry entry : only.getValue()) {
                financeAmountSum = financeAmountSum.add(normalizeFixed(entry.amountText(), GL_SCALE, 24));
            }
            BigDecimal rawRevenue = requireRepresentable(financeAmountSum.negate(), 48, GL_SCALE);
            BigDecimal control = rawRevenue.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            return GlResolution.usable(key, rawRevenue, control,
                    control.subtract(rawRevenue), only.getValue().getFirst().accountingIdentifier());
        } catch (NumericFailure failure) {
            return GlResolution.ambiguous(ReasonCode.GL_CONTROL_INVALID, key, null, null);
        }
    }

    private Set<String> qualifyingVoucherKeys(DocumentInput document) {
        if (document == null || document.glEntries() == null) return Set.of();
        return document.glEntries().stream()
                .filter(Objects::nonNull)
                .filter(entry -> Objects.equals(document.companyUuid(), entry.companyUuid()))
                .filter(entry -> "BOOKED".equals(entry.postingStatus()))
                .filter(entry -> "REVENUE".equals(entry.accountCostType()))
                .filter(entry -> entry.voucherNumber() > 0 && entry.financialYearStartYear() > 0)
                .map(PracticeRevenueValuationService::canonicalVoucherKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String canonicalVoucherKey(GlEntry entry) {
        return entry.companyUuid() + ":" + entry.financialYearStartYear()
                + ":BOOKED:" + entry.voucherNumber();
    }

    private DocumentValuation valueWithGl(
            DocumentInput document,
            LocalDate month,
            List<PreparedItem> items,
            HeaderEvidence header,
            GlResolution gl) {
        BigDecimal nativeSum = signedNativeSum(items);
        BigDecimal absoluteMovement = absoluteNativeMovement(items);
        if (nativeSum.signum() != 0 && gl.documentControl().signum() != 0
                && nativeSum.signum() != gl.documentControl().signum()) {
            return unavailableDocument(document, month, items, gl,
                    ValuationStatus.UNAVAILABLE_CLASSIFICATION, ReasonCode.CONTROL_SIGN_MISMATCH);
        }
        List<BigDecimal> nativeDkkControls = exactNativeDkkControls(document, items);
        if (gl.documentControl().signum() == 0) {
            if (absoluteMovement.signum() == 0) {
                return directItemControls(document, month, items, header, gl,
                        items.stream().map(ignored -> ZERO_MONEY).toList());
            }
            if (!nativeDkkControls.isEmpty()
                    && nativeDkkControls.stream().reduce(ZERO_MONEY, BigDecimal::add).signum() == 0) {
                return directItemControls(document, month, items, header, gl, nativeDkkControls);
            }
            return unavailableDocument(document, month, items, gl,
                    ValuationStatus.UNAVAILABLE_AMBIGUOUS,
                    ReasonCode.OFFSETTING_ITEM_CONTROL_UNAVAILABLE);
        }

        BigDecimal threshold = MATERIAL_NATIVE_FLOOR.max(absoluteMovement.multiply(MATERIAL_RELATIVE_FACTOR));
        if (nativeSum.abs().compareTo(threshold) < 0) {
            if (!nativeDkkControls.isEmpty()
                    && nativeDkkControls.stream().reduce(ZERO_MONEY, BigDecimal::add)
                    .compareTo(gl.documentControl()) == 0) {
                return directItemControls(document, month, items, header, gl, nativeDkkControls);
            }
            return controlledResidual(document, month, items, gl,
                    ReasonCode.NEAR_ZERO_SIGNED_NATIVE_DENOMINATOR);
        }

        try {
            List<DeterministicShareNormalizer.SignedControlCandidate<String>> ratioCandidates = items.stream()
                    .map(item -> new DeterministicShareNormalizer.SignedControlCandidate<>(
                            item.input().itemUuid(), item.signedNativeControl()))
                    .toList();
            DeterministicShareNormalizer.SignedRatioResult<String> ratios =
                    DeterministicShareNormalizer.normalizeSignedRatios(ratioCandidates);
            Map<String, DeterministicShareNormalizer.NormalizedSignedRatio<String>> ratioByKey =
                    ratios.ratios().stream().collect(java.util.stream.Collectors.toMap(
                            DeterministicShareNormalizer.NormalizedSignedRatio::stableKey, value -> value));
            List<BalancedCentAllocator.Candidate<String>> moneyCandidates = items.stream()
                    .map(item -> new BalancedCentAllocator.Candidate<>(item.input().itemUuid(),
                            gl.documentControl().multiply(ratioByKey.get(item.input().itemUuid()).signedRatio())))
                    .toList();
            BalancedCentAllocator.Result<String> allocated = BalancedCentAllocator.allocate(
                    gl.documentControl(), moneyCandidates, BalancedCentAllocator.TargetMode.AUTHORITATIVE);
            Map<String, BalancedCentAllocator.Allocation<String>> moneyByKey = allocated.allocations().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            BalancedCentAllocator.Allocation::stableKey, value -> value));

            List<ItemControl> controls = new ArrayList<>(items.size());
            for (PreparedItem item : items) {
                var ratio = ratioByKey.get(item.input().itemUuid());
                var money = moneyByKey.get(item.input().itemUuid());
                controls.add(toControl(document, month, item, gl, ControlSource.ECONOMIC_GL,
                        ValuationStatus.CONFIRMED_GL, money.roundedAmount(), gl.documentControl(),
                        ratio.signedRatio(), ratio.closureRow(), money, null, false,
                        header.state() == HeaderState.LEGACY_PROVENANCE_ONLY
                                ? ReasonCode.HEADER_DISCOUNT_PROVENANCE_UNAVAILABLE : ReasonCode.NONE));
            }
            return documentResult(document, month, gl, controls,
                    header.state() == HeaderState.LEGACY_PROVENANCE_ONLY
                            ? SourceStatus.INCOMPLETE : SourceStatus.COMPLETE,
                    header.state() == HeaderState.LEGACY_PROVENANCE_ONLY
                            ? ReasonCode.HEADER_DISCOUNT_PROVENANCE_UNAVAILABLE : ReasonCode.NONE);
        } catch (RuntimeException failure) {
            return unavailableDocument(document, month, items, gl,
                    ValuationStatus.UNAVAILABLE_CLASSIFICATION, ReasonCode.DOCUMENT_RATIO_INVALID);
        }
    }

    private DocumentValuation directItemControls(
            DocumentInput document, LocalDate month, List<PreparedItem> items,
            HeaderEvidence header, GlResolution gl, List<BigDecimal> controls) {
        List<ItemControl> result = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            PreparedItem item = items.get(index);
            BigDecimal amount = controls.get(index);
            BalancedCentAllocator.Allocation<String> evidence = new BalancedCentAllocator.Allocation<>(
                    item.input().itemUuid(), amount, amount, BigDecimal.ZERO, false, amount);
            result.add(toControl(document, month, item, gl, ControlSource.ECONOMIC_GL,
                    ValuationStatus.CONFIRMED_GL, amount, gl.documentControl(), null, false,
                    evidence, null, false, ReasonCode.NONE));
        }
        return documentResult(document, month, gl, result,
                header.state() == HeaderState.LEGACY_PROVENANCE_ONLY
                        ? SourceStatus.INCOMPLETE : SourceStatus.COMPLETE,
                header.state() == HeaderState.LEGACY_PROVENANCE_ONLY
                        ? ReasonCode.HEADER_DISCOUNT_PROVENANCE_UNAVAILABLE : ReasonCode.NONE);
    }

    private List<BigDecimal> exactNativeDkkControls(DocumentInput document, List<PreparedItem> items) {
        if (!"DKK".equalsIgnoreCase(document.nativeCurrency())) return List.of();
        return items.stream().map(item -> item.signedNativeControl().setScale(MONEY_SCALE, RoundingMode.HALF_UP)).toList();
    }

    private DocumentValuation valueProvisionally(
            DocumentInput document, LocalDate month, List<PreparedItem> items,
            HeaderEvidence header, GlResolution gl) {
        FxResolution fx = resolveFx(document);
        if (!fx.usable()) {
            return unavailableDocument(document, month, items, gl,
                    fx.ambiguous() ? ValuationStatus.UNAVAILABLE_AMBIGUOUS : ValuationStatus.UNAVAILABLE_MISSING,
                    fx.reason());
        }
        try {
            List<BalancedCentAllocator.Candidate<String>> candidates = items.stream()
                    .map(item -> new BalancedCentAllocator.Candidate<>(item.input().itemUuid(),
                            requireRepresentable(item.signedNativeControl().multiply(fx.rate()), 65, 20)))
                    .toList();
            BalancedCentAllocator.Result<String> allocated = BalancedCentAllocator.allocateOnceRounded(candidates);
            Map<String, BalancedCentAllocator.Allocation<String>> byKey = allocated.allocations().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            BalancedCentAllocator.Allocation::stableKey, value -> value));
            ValuationStatus status = fx.source() == ControlSource.NATIVE_DKK
                    ? ValuationStatus.PROVISIONAL_NATIVE_DKK
                    : ValuationStatus.PROVISIONAL_MONTHLY_FX;
            List<ItemControl> controls = new ArrayList<>(items.size());
            for (PreparedItem item : items) {
                var money = byKey.get(item.input().itemUuid());
                controls.add(toControl(document, month, item, gl, fx.source(), status,
                        money.roundedAmount(), allocated.targetControl(), null, false,
                        money, fx, false, ReasonCode.NONE));
            }
            return documentResult(document, month, gl, controls, SourceStatus.COMPLETE, ReasonCode.NONE);
        } catch (RuntimeException failure) {
            return unavailableDocument(document, month, items, gl,
                    ValuationStatus.UNAVAILABLE_CLASSIFICATION, ReasonCode.FX_PRODUCT_OVERFLOW);
        }
    }

    private FxResolution resolveFx(DocumentInput document) {
        if ("DKK".equalsIgnoreCase(document.nativeCurrency())) {
            return new FxResolution(true, false, ControlSource.NATIVE_DKK,
                    "1.00000000", new BigDecimal("1.00000000"), false, ReasonCode.NONE);
        }
        List<String> rates = document.fxRateTexts() == null ? List.of()
                : document.fxRateTexts().stream().filter(Objects::nonNull).toList();
        if (rates.isEmpty()) return FxResolution.missing(ReasonCode.FX_RATE_MISSING);
        if (rates.size() != 1) return FxResolution.ambiguous(ReasonCode.FX_RATE_AMBIGUOUS);
        String text = rates.getFirst();
        try {
            BigDecimal original = new BigDecimal(text.trim());
            BigDecimal normalized = original.setScale(FX_SCALE, RoundingMode.HALF_UP);
            requireRepresentable(normalized, 17, FX_SCALE);
            if (normalized.signum() <= 0 || normalized.compareTo(MAX_FX) > 0) {
                return FxResolution.missing(ReasonCode.FX_RATE_INVALID);
            }
            return new FxResolution(true, false, ControlSource.MONTHLY_FX, text, normalized,
                    original.compareTo(normalized) != 0, ReasonCode.NONE);
        } catch (RuntimeException failure) {
            return FxResolution.missing(ReasonCode.FX_RATE_INVALID);
        }
    }

    private DocumentValuation controlledResidual(
            DocumentInput document, LocalDate month, List<PreparedItem> items,
            GlResolution gl, ReasonCode reason) {
        List<ItemControl> controls = new ArrayList<>(items.size() + 1);
        for (PreparedItem item : items) {
            controls.add(toUnavailableControl(document, month, item, gl,
                    ValuationStatus.CONTROLLED_BY_DOCUMENT_RESIDUAL, reason));
        }
        String key = "DOCUMENT_RESIDUAL:" + document.documentUuid();
        BigDecimal control = gl.documentControl();
        controls.add(new ItemControl(key, null, document.documentUuid(), month,
                ItemRowKind.DOCUMENT_RESIDUAL, null, null, document.nativeCurrency(), null, null,
                control, control, ControlSource.ECONOMIC_GL, ValuationStatus.CONFIRMED_GL,
                gl.rawGl(), gl.centAdjustment(), null, false, control, control,
                BigDecimal.ZERO, false, null, false, true, reason));
        return documentResult(document, month, gl, controls, SourceStatus.INCOMPLETE, reason);
    }

    private DocumentValuation documentEvidenceSentinel(
            DocumentInput document, LocalDate month, GlResolution gl) {
        ItemControl sentinel = new ItemControl("DOCUMENT_EVIDENCE:" + document.documentUuid(), null,
                document.documentUuid(), month, ItemRowKind.DOCUMENT_EVIDENCE, null, null,
                document.nativeCurrency(), null, null, null, null, ControlSource.NONE,
                ValuationStatus.UNAVAILABLE_MISSING, gl.rawGl(), gl.centAdjustment(), null, false,
                null, null, null, false, null, false, false, ReasonCode.ZERO_ITEM_DOCUMENT);
        return documentResult(document, month, gl, List.of(sentinel), SourceStatus.INCOMPLETE,
                ReasonCode.ZERO_ITEM_DOCUMENT);
    }

    private DocumentValuation unavailableDocument(
            DocumentInput document, LocalDate month, List<PreparedItem> items, GlResolution gl,
            ValuationStatus status, ReasonCode reason) {
        List<ItemControl> controls = items.stream()
                .map(item -> toUnavailableControl(document, month, item, gl, status, reason))
                .toList();
        return documentResult(document, month, gl, controls, SourceStatus.INCOMPLETE, reason);
    }

    private ItemControl toUnavailableControl(
            DocumentInput document, LocalDate month, PreparedItem item, GlResolution gl,
            ValuationStatus status, ReasonCode reason) {
        return new ItemControl("ITEM:" + document.documentUuid() + ":" + item.input().itemUuid(),
                item.input().itemUuid(), document.documentUuid(), month, ItemRowKind.SOURCE_ITEM,
                item.category(), item.subtype(), document.nativeCurrency(), item.nativeAmount(),
                item.signedNativeControl(), null, null, ControlSource.NONE, status,
                gl.rawGl(), gl.centAdjustment(), null, false, null, null, null,
                false, null, false, false, reason);
    }

    private ItemControl toControl(
            DocumentInput document, LocalDate month, PreparedItem item, GlResolution gl,
            ControlSource source, ValuationStatus status, BigDecimal itemControl,
            BigDecimal documentControl, BigDecimal ratio, boolean ratioClosure,
            BalancedCentAllocator.Allocation<String> money, FxResolution fx,
            boolean synthetic, ReasonCode reason) {
        return new ItemControl("ITEM:" + document.documentUuid() + ":" + item.input().itemUuid(),
                item.input().itemUuid(), document.documentUuid(), month, ItemRowKind.SOURCE_ITEM,
                item.category(), item.subtype(), document.nativeCurrency(), item.nativeAmount(),
                item.signedNativeControl(), itemControl, documentControl, source, status,
                gl.rawGl(), gl.centAdjustment(), ratio, ratioClosure,
                money.unroundedAmount(), money.floorAmount(), money.fractionalCent(), money.centAwarded(),
                fx == null ? null : fx.rate(), fx != null && fx.normalizationChanged(), synthetic, reason);
    }

    private DocumentValuation documentResult(
            DocumentInput document, LocalDate month, GlResolution gl, List<ItemControl> controls,
            SourceStatus sourceStatus, ReasonCode reason) {
        List<BigDecimal> authoritativeItems = controls.stream()
                .filter(item -> item.valuationStatus() == ValuationStatus.CONFIRMED_GL)
                .map(ItemControl::itemControlDkk).filter(Objects::nonNull)
                .toList();
        BigDecimal authoritative = authoritativeItems.isEmpty() ? null
                : authoritativeItems.stream().reduce(ZERO_MONEY, BigDecimal::add);
        List<BigDecimal> provisionalItems = controls.stream()
                .filter(item -> item.valuationStatus() == ValuationStatus.PROVISIONAL_NATIVE_DKK
                        || item.valuationStatus() == ValuationStatus.PROVISIONAL_MONTHLY_FX)
                .map(ItemControl::itemControlDkk).filter(Objects::nonNull)
                .toList();
        BigDecimal provisional = provisionalItems.isEmpty() ? null
                : provisionalItems.stream().reduce(ZERO_MONEY, BigDecimal::add);
        return new DocumentValuation(document.documentUuid(), month, document.type(),
                List.copyOf(controls), authoritative, provisional, gl.voucherKey(), gl.rawGl(),
                gl.documentControl(), sourceStatus, reason);
    }

    private static BigDecimal signedNativeSum(List<PreparedItem> items) {
        return items.stream().map(PreparedItem::signedNativeControl).filter(Objects::nonNull)
                .reduce(ZERO_NATIVE, BigDecimal::add);
    }

    private static BigDecimal absoluteNativeMovement(List<PreparedItem> items) {
        return items.stream().map(PreparedItem::signedNativeControl).filter(Objects::nonNull)
                .map(BigDecimal::abs).reduce(ZERO_NATIVE, BigDecimal::add);
    }

    private static BigDecimal normalizeFixed(String text, int scale, int precision) {
        if (text == null || text.isBlank()) throw new NumericFailure();
        try {
            BigDecimal value = new BigDecimal(text.trim()).setScale(scale, RoundingMode.HALF_UP);
            return requireRepresentable(value, precision, scale);
        } catch (NumberFormatException failure) {
            throw new NumericFailure();
        }
    }

    private static BigDecimal requireRepresentable(BigDecimal value, int precision, int scale) {
        if (value == null || value.scale() > scale || value.precision() > precision) throw new NumericFailure();
        int integerDigits = Math.max(0, value.precision() - value.scale());
        if (integerDigits > precision - scale) throw new NumericFailure();
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public enum DocumentType { INVOICE, PHANTOM, CREDIT_NOTE, INTERNAL, OTHER }
    public enum ItemOrigin { BASE, CALCULATED, UNKNOWN }
    public enum ItemCategory { DELIVERY_BASE, COMMERCIAL_ADJUSTMENT }
    public enum AdjustmentSubtype {
        DISCOUNT, FEE_OR_UPLIFT, LEGACY_REBATE, LEGACY_FIXED_PRICE_OR_FEE, ZERO_ADJUSTMENT
    }
    public enum ItemRowKind { SOURCE_ITEM, DOCUMENT_RESIDUAL, DOCUMENT_EVIDENCE }
    public enum ControlSource { ECONOMIC_GL, NATIVE_DKK, MONTHLY_FX, NONE }
    public enum ValuationStatus {
        CONFIRMED_GL, PROVISIONAL_NATIVE_DKK, PROVISIONAL_MONTHLY_FX,
        CONTROLLED_BY_DOCUMENT_RESIDUAL, UNAVAILABLE_MISSING, UNAVAILABLE_AMBIGUOUS,
        UNAVAILABLE_DUPLICATE_RISK, UNAVAILABLE_CLASSIFICATION, UNAVAILABLE_PHANTOM_ITEM_GRAIN
    }
    public enum SourceStatus { COMPLETE, INCOMPLETE }
    public enum ReasonCode {
        NONE, ITEM_NUMERIC_INVALID, ITEM_CLASSIFICATION_UNAVAILABLE,
        HEADER_DISCOUNT_OUT_OF_RANGE, HEADER_DISCOUNT_PROVENANCE_UNAVAILABLE,
        HEADER_DISCOUNT_MONETARY_STRUCTURE_UNAVAILABLE,
        GL_CONTROL_MISSING, GL_CONTROL_AMBIGUOUS, GL_CONTROL_INVALID,
        MANUAL_PHANTOM_DUPLICATE_RISK, CONTROL_SIGN_MISMATCH, DOCUMENT_RATIO_INVALID,
        NEAR_ZERO_SIGNED_NATIVE_DENOMINATOR, OFFSETTING_ITEM_CONTROL_UNAVAILABLE,
        FX_RATE_MISSING, FX_RATE_AMBIGUOUS, FX_RATE_INVALID, FX_PRODUCT_OVERFLOW,
        PHANTOM_ITEM_GRAIN_INVALID, ZERO_ITEM_DOCUMENT
    }
    private enum HeaderState {
        NOT_APPLICABLE_OR_ZERO, PROVEN, LEGACY_PROVENANCE_ONLY,
        MONETARY_STRUCTURE_GAP, INVALID_RANGE
    }

    public record ItemInput(
            String itemUuid, ItemOrigin origin, String hoursText, String rateText,
            String consultantUuid, boolean explicitDeliveryAnchor,
            String calculationRef, String ruleId, String label,
            boolean headerDiscountCandidate, String pricingPolicyVersion,
            String pricingStepId, Integer pricingStepSequence, String pricingRuleType,
            String pricingInputFingerprint, String pricingOutputFingerprint,
            BigDecimal pricingOutputAmount, String calculationAlgorithmVersion) {
    }

    public record GlEntry(
            String voucherKey, String companyUuid, int financialYearStartYear,
            String postingStatus, long voucherNumber, long journalNumber,
            String accountCostType, String amountText, String accountingIdentifier) {
    }

    public record DocumentInput(
            String documentUuid, String companyUuid, DocumentType type, String status,
            boolean internalDebtorCredit, LocalDate invoiceDate, String nativeCurrency,
            String headerDiscountText, List<ItemInput> items, List<GlEntry> glEntries,
            List<String> fxRateTexts, boolean dependencyOnly, boolean glEvidenceConflict) {
        public DocumentInput {
            Objects.requireNonNull(documentUuid, "documentUuid");
            items = items == null ? List.of() : List.copyOf(items);
            glEntries = glEntries == null ? List.of() : List.copyOf(glEntries);
            fxRateTexts = fxRateTexts == null ? List.of() : List.copyOf(fxRateTexts);
        }
        public DocumentInput(
                String documentUuid, String companyUuid, DocumentType type, String status,
                boolean internalDebtorCredit, LocalDate invoiceDate, String nativeCurrency,
                String headerDiscountText, List<ItemInput> items, List<GlEntry> glEntries,
                List<String> fxRateTexts, boolean dependencyOnly) {
            this(documentUuid, companyUuid, type, status, internalDebtorCredit, invoiceDate,
                    nativeCurrency, headerDiscountText, items, glEntries, fxRateTexts,
                    dependencyOnly, false);
        }
        public DocumentInput(
                String documentUuid, String companyUuid, DocumentType type, String status,
                boolean internalDebtorCredit, LocalDate invoiceDate, String nativeCurrency,
                String headerDiscountText, List<ItemInput> items, List<GlEntry> glEntries,
                List<String> fxRateTexts) {
            this(documentUuid, companyUuid, type, status, internalDebtorCredit, invoiceDate,
                    nativeCurrency, headerDiscountText, items, glEntries, fxRateTexts, false, false);
        }

        /** Same document, but never recognized: it only participates in inverse voucher-key uniqueness. */
        public DocumentInput asDependencyOnly() {
            return new DocumentInput(documentUuid, companyUuid, type, status, internalDebtorCredit,
                    invoiceDate, nativeCurrency, headerDiscountText, items, glEntries, fxRateTexts,
                    true, glEvidenceConflict);
        }
    }

    public record ItemControl(
            String itemControlKey, String sourceItemUuid, String sourceDocumentUuid,
            LocalDate recognizedMonth, ItemRowKind rowKind, ItemCategory itemCategory,
            AdjustmentSubtype adjustmentSubtype, String nativeCurrency,
            BigDecimal nativeItemAmount, BigDecimal signedNativeControl,
            BigDecimal itemControlDkk, BigDecimal documentControlDkk,
            ControlSource controlSource, ValuationStatus valuationStatus,
            BigDecimal rawGlControlDkk, BigDecimal glCentAdjustmentDkk,
            BigDecimal effectiveDocumentRatio, boolean documentRatioClosureRow,
            BigDecimal unroundedItemDkk, BigDecimal floorItemDkk,
            BigDecimal fractionalCentResidue, boolean oneCentAwarded,
            BigDecimal dkkPerNativeUnit, boolean fxNormalizationChanged,
            boolean syntheticResidual, ReasonCode reasonCode) {
    }

    public record DocumentValuation(
            String documentUuid, LocalDate recognizedMonth, DocumentType documentType,
            List<ItemControl> items, BigDecimal authoritativeControlDkk,
            BigDecimal provisionalControlDkk, String matchedVoucherKey,
            BigDecimal matchedRawGlDkk, BigDecimal matchedGlCandidateCentDkk,
            SourceStatus sourceStatus, ReasonCode reasonCode) {
        public DocumentValuation {
            items = List.copyOf(items);
        }
    }

    public record ValuationBatch(
            List<DocumentValuation> documents, int recognizedDocumentCount, int excludedDocumentCount) {
        public ValuationBatch {
            documents = List.copyOf(documents);
        }
    }

    private record Classification(ItemCategory category, AdjustmentSubtype subtype) { }
    private record HeaderEvidence(HeaderState state, String original, BigDecimal normalized, boolean changed) { }
    private record PreparedItem(
            ItemInput input, BigDecimal hours, BigDecimal rate, BigDecimal nativeAmount,
            BigDecimal signedNativeControl, short documentSign, ItemCategory category,
            AdjustmentSubtype subtype, ReasonCode failureReason) {
        static PreparedItem invalid(String key, ReasonCode reason) {
            return new PreparedItem(new ItemInput(key, ItemOrigin.UNKNOWN, "0", "0", null,
                    false, null, null, null, false, null, null, null, null,
                    null, null, null, null), null, null, null, null, (short) 1,
                    null, null, reason);
        }
    }

    private record GlResolution(
            boolean usable, String voucherKey, BigDecimal rawGl, BigDecimal documentControl,
            BigDecimal centAdjustment, String accountingIdentifier,
            ValuationStatus failureStatus, ReasonCode reason) {
        static GlResolution usable(String key, BigDecimal raw, BigDecimal control,
                                   BigDecimal adjustment, String identifier) {
            return new GlResolution(true, key, raw, control, adjustment, identifier, null, ReasonCode.NONE);
        }
        static GlResolution missing() {
            return new GlResolution(false, null, null, null, null, null,
                    ValuationStatus.UNAVAILABLE_MISSING, ReasonCode.GL_CONTROL_MISSING);
        }
        static GlResolution ambiguous(ReasonCode reason, String key, BigDecimal raw, BigDecimal control) {
            ValuationStatus status = reason == ReasonCode.MANUAL_PHANTOM_DUPLICATE_RISK
                    ? ValuationStatus.UNAVAILABLE_DUPLICATE_RISK : ValuationStatus.UNAVAILABLE_AMBIGUOUS;
            return new GlResolution(false, key, raw, control, null, null, status, reason);
        }
    }

    private record FxResolution(
            boolean usable, boolean ambiguous, ControlSource source, String original,
            BigDecimal rate, boolean normalizationChanged, ReasonCode reason) {
        static FxResolution missing(ReasonCode reason) {
            return new FxResolution(false, false, ControlSource.NONE, null, null, false, reason);
        }
        static FxResolution ambiguous(ReasonCode reason) {
            return new FxResolution(false, true, ControlSource.NONE, null, null, false, reason);
        }
    }

    private static final class NumericFailure extends RuntimeException { }
    private static final class ClassificationFailure extends RuntimeException { }
}
