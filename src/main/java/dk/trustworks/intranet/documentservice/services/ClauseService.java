package dk.trustworks.intranet.documentservice.services;

import dk.trustworks.intranet.documentservice.dto.TemplateClauseDTO;
import dk.trustworks.intranet.documentservice.dto.TemplateClauseLinkDTO;
import dk.trustworks.intranet.documentservice.dto.TemplateClausePlaceholderDTO;
import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.documentservice.model.TemplateClauseEntity;
import dk.trustworks.intranet.documentservice.model.TemplateClauseLinkEntity;
import dk.trustworks.intranet.documentservice.model.TemplateClausePlaceholderEntity;
import dk.trustworks.intranet.documentservice.model.TemplateClauseVersionEntity;
import dk.trustworks.intranet.documentservice.model.TemplatePlaceholderEntity;
import dk.trustworks.intranet.documentservice.model.enums.ClauseStatus;
import dk.trustworks.intranet.documentservice.model.enums.DataSource;
import dk.trustworks.intranet.documentservice.model.enums.FieldType;
import dk.trustworks.intranet.signing.domain.SigningCaseClause;
import dk.trustworks.intranet.utils.services.WordDocumentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CRUD and lifecycle for the clause library (template-clauses spec §4.1–
 * §4.4, §6).
 * <p>
 * Two hard guarantees live here:
 * <ul>
 *   <li><b>Tag↔parameter diff on publish</b> — a version whose extracted
 *       {@code {{TAGS}}} disagree with the clause's declared parameters
 *       cannot become active. poi-tl's DiscardHandler silently deletes
 *       unmatched tags, so a drifted fragment would render a wording with
 *       holes and no error anywhere (the 2026-08-24 blank-contract
 *       lesson).</li>
 *   <li><b>Version immutability once used (D7)</b> — a version referenced
 *       by any {@code signing_case_clauses} row can never be deleted;
 *       wording changes are always a new version.</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class ClauseService {

    /** The composition anchor — reserved, never a clause parameter. */
    public static final String CLAUSES_ANCHOR_KEY = "CLAUSES";

    @Inject
    WordDocumentService wordDocumentService;

    // ---- Read ------------------------------------------------------------------

    public List<TemplateClauseDTO> findAll() {
        return TemplateClauseEntity.findAllOrdered().stream()
                .map(clause -> toDto(clause, false))
                .toList();
    }

    public TemplateClauseDTO findByUuid(String uuid) {
        TemplateClauseEntity clause = requireClause(uuid);
        return toDto(clause, true);
    }

    /**
     * ACTIVE clauses offered on a template: explicit links first (in link
     * order, carrying preselected/required), then category-wide offers not
     * already linked. This is what the wizard/dossier "Vilkår & klausuler"
     * step renders.
     */
    public List<OfferedClause> findOfferedForTemplate(String templateUuid) {
        DocumentTemplateEntity template = DocumentTemplateEntity.findById(templateUuid);
        if (template == null) {
            throw new WebApplicationException("Template not found: " + templateUuid, 404);
        }
        List<OfferedClause> offered = new ArrayList<>();
        Set<String> linkedClauseUuids = new LinkedHashSet<>();
        for (TemplateClauseLinkEntity link : TemplateClauseLinkEntity.findByTemplate(templateUuid)) {
            TemplateClauseEntity clause = TemplateClauseEntity.findById(link.getClauseUuid());
            if (clause == null || clause.getStatus() != ClauseStatus.ACTIVE) {
                continue;
            }
            linkedClauseUuids.add(clause.getUuid());
            offered.add(new OfferedClause(toDto(clause, true), link.isPreselected(), link.isRequired(),
                    link.getDisplayOrder()));
        }
        int order = offered.size();
        for (TemplateClauseEntity clause : TemplateClauseEntity.findActiveByCategoryOffer(template.getCategory())) {
            if (linkedClauseUuids.contains(clause.getUuid())) {
                continue;
            }
            offered.add(new OfferedClause(toDto(clause, true), false, false, order++));
        }
        return offered;
    }

    /** One clause as offered on a template: the link flags ride along. */
    public record OfferedClause(TemplateClauseDTO clause, boolean preselected, boolean required, int displayOrder) {
    }

    // ---- Create / update -------------------------------------------------------

    @Transactional
    public TemplateClauseDTO create(TemplateClauseDTO dto) {
        String clauseKey = normalizeClauseKey(dto.getClauseKey());
        if (TemplateClauseEntity.findByClauseKey(clauseKey).isPresent()) {
            throw new WebApplicationException("A clause with key '" + clauseKey + "' already exists", 409);
        }
        TemplateClauseEntity clause = new TemplateClauseEntity();
        applyMetadata(clause, dto, clauseKey);
        clause.setStatus(ClauseStatus.DRAFT);
        clause.persist();
        syncPlaceholders(clause, dto.getPlaceholders());
        log.infof("Clause created: %s (%s)", clause.getName(), clauseKey);
        return toDto(clause, true);
    }

    @Transactional
    public TemplateClauseDTO update(String uuid, TemplateClauseDTO dto) {
        TemplateClauseEntity clause = requireClause(uuid);
        String clauseKey = normalizeClauseKey(dto.getClauseKey());
        TemplateClauseEntity byKey = TemplateClauseEntity.findByClauseKey(clauseKey).orElse(null);
        if (byKey != null && !byKey.getUuid().equals(uuid)) {
            throw new WebApplicationException("A clause with key '" + clauseKey + "' already exists", 409);
        }
        applyMetadata(clause, dto, clauseKey);
        if (dto.getStatus() != null) {
            if (dto.getStatus() == ClauseStatus.ACTIVE && clause.getActiveVersionUuid() == null) {
                throw new WebApplicationException(
                        "Cannot activate a clause without a published version — upload and publish a fragment first", 409);
            }
            clause.setStatus(dto.getStatus());
        }
        clause.persist();
        if (dto.getPlaceholders() != null) {
            syncPlaceholders(clause, dto.getPlaceholders());
        }
        log.infof("Clause updated: %s (%s)", clause.getName(), clauseKey);
        return toDto(clause, true);
    }

    /**
     * Retire, never delete: sent cases and dossier snapshots keep
     * referencing the clause. A DRAFT clause never offered and never used
     * is deleted outright.
     */
    @Transactional
    public void retire(String uuid) {
        TemplateClauseEntity clause = requireClause(uuid);
        boolean everUsed = SigningCaseClause.clauseInUse(uuid);
        boolean linked = !TemplateClauseLinkEntity.findByClause(uuid).isEmpty();
        if (clause.getStatus() == ClauseStatus.DRAFT && !everUsed && !linked) {
            TemplateClauseEntity.deleteById(uuid);
            log.infof("Draft clause deleted: %s", uuid);
            return;
        }
        clause.setStatus(ClauseStatus.RETIRED);
        clause.persist();
        log.infof("Clause retired: %s (%s)", clause.getName(), clause.getClauseKey());
    }

    // ---- Versions --------------------------------------------------------------

    /**
     * Upload a new wording version (append-only, D7). The fragment is
     * stored in S3; the version is created unpublished — publishing (and
     * the tag-diff gate) is a separate explicit step.
     *
     * @return the created version + the tags extracted from the fragment
     */
    @Transactional
    public UploadedVersion addVersion(String clauseUuid, byte[] fragmentBytes, String filename,
                                      String changeNote, String actorUuid) {
        TemplateClauseEntity clause = requireClause(clauseUuid);
        String fileUuid = wordDocumentService.saveWordTemplate(fragmentBytes, filename, clauseUuid);
        Set<String> extractedTags = wordDocumentService.extractPlaceholders(fragmentBytes);

        TemplateClauseVersionEntity version = new TemplateClauseVersionEntity();
        version.setClauseUuid(clauseUuid);
        version.setVersionNumber(TemplateClauseVersionEntity.nextVersionNumber(clauseUuid));
        version.setFileUuid(fileUuid);
        version.setOriginalFilename(filename);
        version.setChangeNote(changeNote);
        version.setCreatedBy(actorUuid);
        version.persist();

        log.infof("Clause %s version %d uploaded (%d tags)", clause.getClauseKey(),
                version.getVersionNumber(), extractedTags.size());
        return new UploadedVersion(toVersionDto(version), extractedTags);
    }

    public record UploadedVersion(TemplateClauseDTO.ClauseVersionDTO version, Set<String> extractedTags) {
    }

    /**
     * Make a version the clause's active wording. Blocked when the
     * fragment's extracted tags and the clause's declared parameters
     * disagree — the diff in the error message names both directions.
     */
    @Transactional
    public TemplateClauseDTO publishVersion(String clauseUuid, String versionUuid, String actorUuid) {
        TemplateClauseEntity clause = requireClause(clauseUuid);
        TemplateClauseVersionEntity version = TemplateClauseVersionEntity.findById(versionUuid);
        if (version == null || !clauseUuid.equals(version.getClauseUuid())) {
            throw new WebApplicationException("Version not found on clause: " + versionUuid, 404);
        }

        byte[] fragmentBytes = wordDocumentService.getWordTemplate(version.getFileUuid());
        Set<String> extractedTags = wordDocumentService.extractPlaceholders(fragmentBytes);
        Set<String> declaredKeys = TemplateClausePlaceholderEntity.findByClause(clauseUuid).stream()
                .map(TemplateClausePlaceholderEntity::getPlaceholderKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        TagDiff diff = diffTags(extractedTags, declaredKeys);
        if (!diff.isEmpty()) {
            throw new WebApplicationException(diff.describe(clause.getClauseKey()), 409);
        }

        if (version.getPublishedAt() == null) {
            version.setPublishedAt(LocalDateTime.now());
            version.setPublishedBy(actorUuid);
            version.persist();
        }
        clause.setActiveVersionUuid(version.getUuid());
        clause.persist();
        log.infof("Clause %s version %d published as active", clause.getClauseKey(), version.getVersionNumber());
        return toDto(clause, true);
    }

    /**
     * Delete an unused, unpublished version. A version any sent case
     * references is immutable (D7) — deletion is refused with 409.
     */
    @Transactional
    public void deleteVersion(String clauseUuid, String versionUuid) {
        TemplateClauseEntity clause = requireClause(clauseUuid);
        TemplateClauseVersionEntity version = TemplateClauseVersionEntity.findById(versionUuid);
        if (version == null || !clauseUuid.equals(version.getClauseUuid())) {
            throw new WebApplicationException("Version not found on clause: " + versionUuid, 404);
        }
        if (SigningCaseClause.versionInUse(versionUuid)) {
            throw new WebApplicationException(
                    "Version " + version.getVersionNumber() + " was sent with at least one signing case and is immutable"
                            + " — upload a new version instead", 409);
        }
        if (versionUuid.equals(clause.getActiveVersionUuid())) {
            throw new WebApplicationException(
                    "Version " + version.getVersionNumber() + " is the active version — publish another version first", 409);
        }
        TemplateClauseVersionEntity.deleteById(versionUuid);
        log.infof("Clause %s version %d deleted (never used)", clause.getClauseKey(), version.getVersionNumber());
    }

    // ---- Links -----------------------------------------------------------------

    public List<TemplateClauseLinkDTO> findLinks(String templateUuid) {
        return TemplateClauseLinkEntity.findByTemplate(templateUuid).stream()
                .map(link -> TemplateClauseLinkDTO.builder()
                        .uuid(link.getUuid())
                        .templateUuid(link.getTemplateUuid())
                        .clauseUuid(link.getClauseUuid())
                        .preselected(link.isPreselected())
                        .required(link.isRequired())
                        .displayOrder(link.getDisplayOrder())
                        .build())
                .toList();
    }

    /**
     * Replace a template's offered-clause set. Rejects when any linked
     * clause's parameter keys collide with the template's own placeholder
     * keys or with another linked clause's keys — a collision would make
     * one form value silently win in the merged render map (spec §4.3).
     */
    @Transactional
    public List<TemplateClauseLinkDTO> replaceLinks(String templateUuid, List<TemplateClauseLinkDTO> links) {
        DocumentTemplateEntity template = DocumentTemplateEntity.findById(templateUuid);
        if (template == null) {
            throw new WebApplicationException("Template not found: " + templateUuid, 404);
        }
        List<TemplateClauseLinkDTO> requested = links == null ? List.of() : links;

        Set<String> templateKeys = TemplatePlaceholderEntity.findByTemplate(template).stream()
                .map(TemplatePlaceholderEntity::getPlaceholderKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<ClauseKeys> clauseKeySets = new ArrayList<>();
        for (TemplateClauseLinkDTO link : requested) {
            TemplateClauseEntity clause = requireClause(link.getClauseUuid());
            Set<String> keys = TemplateClausePlaceholderEntity.findByClause(clause.getUuid()).stream()
                    .map(TemplateClausePlaceholderEntity::getPlaceholderKey)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            clauseKeySets.add(new ClauseKeys(clause.getClauseKey(), keys));
        }
        List<String> collisions = findKeyCollisions(templateKeys, clauseKeySets);
        if (!collisions.isEmpty()) {
            throw new WebApplicationException(
                    "Cannot link clauses to template '" + template.getName() + "': placeholder key"
                            + (collisions.size() == 1 ? "" : "s") + " " + String.join(", ", collisions)
                            + " would collide in the merged value map — rename the clause parameter"
                            + (collisions.size() == 1 ? "" : "s") + " (prefix convention, e.g. GB_AMOUNT)", 409);
        }

        TemplateClauseLinkEntity.deleteByTemplate(templateUuid);
        List<TemplateClauseLinkDTO> saved = new ArrayList<>(requested.size());
        int order = 0;
        for (TemplateClauseLinkDTO dto : requested) {
            TemplateClauseLinkEntity link = new TemplateClauseLinkEntity();
            link.setTemplateUuid(templateUuid);
            link.setClauseUuid(dto.getClauseUuid());
            link.setPreselected(dto.isPreselected());
            link.setRequired(dto.isRequired());
            link.setDisplayOrder(order++);
            link.persist();
            saved.add(TemplateClauseLinkDTO.builder()
                    .uuid(link.getUuid())
                    .templateUuid(link.getTemplateUuid())
                    .clauseUuid(link.getClauseUuid())
                    .preselected(link.isPreselected())
                    .required(link.isRequired())
                    .displayOrder(link.getDisplayOrder())
                    .build());
        }
        log.infof("Template %s clause links replaced: %d links", templateUuid, saved.size());
        return saved;
    }

    // ---- Pure core (DB-free, unit-tested) --------------------------------------

    /** A clause's key set for collision checking. */
    public record ClauseKeys(String clauseKey, Set<String> placeholderKeys) {
    }

    /**
     * Keys that appear in more than one of: the base template's
     * placeholders, each clause's parameters. Order-stable for messages.
     */
    static List<String> findKeyCollisions(Set<String> templateKeys, List<ClauseKeys> clauses) {
        List<String> collisions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(templateKeys);
        for (ClauseKeys clause : clauses) {
            for (String key : clause.placeholderKeys()) {
                if (!seen.add(key) && !collisions.contains(key)) {
                    collisions.add(key);
                }
            }
        }
        return collisions;
    }

    /** Both directions of the publish-time tag↔parameter diff. */
    public record TagDiff(Set<String> tagsWithoutParameter, Set<String> parametersWithoutTag) {
        public boolean isEmpty() {
            return tagsWithoutParameter.isEmpty() && parametersWithoutTag.isEmpty();
        }

        public String describe(String clauseKey) {
            StringBuilder message = new StringBuilder("Cannot publish clause ").append(clauseKey)
                    .append(": the fragment and the declared parameters disagree.");
            if (!tagsWithoutParameter.isEmpty()) {
                message.append(" Tags in the document with no declared parameter: ")
                        .append(String.join(", ", tagsWithoutParameter))
                        .append(" (poi-tl would silently delete them).");
            }
            if (!parametersWithoutTag.isEmpty()) {
                message.append(" Declared parameters not present in the document: ")
                        .append(String.join(", ", parametersWithoutTag)).append(".");
            }
            return message.toString();
        }
    }

    /**
     * Diff extracted fragment tags against declared parameter keys. The
     * {@code CLAUSES} anchor and {@code COMPANY_*} fact tags are exempt:
     * the anchor is structural and company facts resolve server-side from
     * the merged value map without being clause parameters.
     */
    static TagDiff diffTags(Set<String> extractedTags, Set<String> declaredKeys) {
        Set<String> tagsWithoutParameter = extractedTags.stream()
                .filter(tag -> !declaredKeys.contains(tag))
                .filter(tag -> !CLAUSES_ANCHOR_KEY.equals(tag))
                .filter(tag -> !tag.startsWith("COMPANY_"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> parametersWithoutTag = declaredKeys.stream()
                .filter(key -> !extractedTags.contains(key))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new TagDiff(tagsWithoutParameter, parametersWithoutTag);
    }

    static String normalizeClauseKey(String clauseKey) {
        String normalized = clauseKey == null ? "" : clauseKey.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]*")) {
            throw new WebApplicationException(
                    "Invalid clause key '" + clauseKey + "' — use uppercase letters, digits and underscores"
                            + " (e.g. GARANTIBONUS)", 400);
        }
        return normalized;
    }

    // ---- Internals -------------------------------------------------------------

    private void applyMetadata(TemplateClauseEntity clause, TemplateClauseDTO dto, String clauseKey) {
        clause.setClauseKey(clauseKey);
        clause.setName(dto.getName());
        clause.setDescription(dto.getDescription());
        clause.setAgreementType(normalizeAgreementType(dto.getAgreementType()));
        if (dto.getRenderMode() != null) {
            clause.setRenderMode(dto.getRenderMode());
        }
        if (dto.getCategory() != null) {
            clause.setCategory(dto.getCategory());
        }
        clause.setOfferOnCategory(dto.isOfferOnCategory());
    }

    private static String normalizeAgreementType(String agreementType) {
        if (agreementType == null || agreementType.isBlank()) {
            return null;
        }
        String normalized = agreementType.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]*")) {
            throw new WebApplicationException(
                    "Invalid agreement type '" + agreementType + "' — use uppercase letters, digits and underscores", 400);
        }
        return normalized;
    }

    private void syncPlaceholders(TemplateClauseEntity clause, List<TemplateClausePlaceholderDTO> placeholders) {
        List<TemplateClausePlaceholderDTO> requested = placeholders == null ? List.of() : placeholders;
        Set<String> keys = new LinkedHashSet<>();
        for (TemplateClausePlaceholderDTO dto : requested) {
            String key = dto.getPlaceholderKey() == null ? "" : dto.getPlaceholderKey().trim();
            if (!key.matches("[A-Z][A-Z0-9_]*")) {
                throw new WebApplicationException("Invalid placeholder key '" + key + "'", 400);
            }
            if (CLAUSES_ANCHOR_KEY.equals(key)) {
                throw new WebApplicationException(
                        "'" + CLAUSES_ANCHOR_KEY + "' is the reserved composition anchor and cannot be a clause parameter", 400);
            }
            if (!keys.add(key)) {
                throw new WebApplicationException("Duplicate placeholder key '" + key + "'", 400);
            }
        }
        TemplateClausePlaceholderEntity.delete("clauseUuid = ?1", clause.getUuid());
        int order = 0;
        for (TemplateClausePlaceholderDTO dto : requested) {
            TemplateClausePlaceholderEntity placeholder = new TemplateClausePlaceholderEntity();
            placeholder.setClauseUuid(clause.getUuid());
            placeholder.setPlaceholderKey(dto.getPlaceholderKey().trim());
            placeholder.setLabel(dto.getLabel());
            placeholder.setFieldType(dto.getFieldType() != null ? dto.getFieldType() : FieldType.TEXT);
            placeholder.setRequired(dto.isRequired());
            placeholder.setDisplayOrder(dto.getDisplayOrder() > 0 ? dto.getDisplayOrder() : order);
            placeholder.setDefaultValue(dto.getDefaultValue());
            placeholder.setHelpText(dto.getHelpText());
            placeholder.setSource(dto.getSource() != null ? dto.getSource() : DataSource.MANUAL);
            placeholder.setSourceField(dto.getSourceField());
            placeholder.setRegistryField(normalizeRegistryField(dto.getRegistryField()));
            placeholder.setFieldGroup(dto.getFieldGroup());
            placeholder.setValidationRules(dto.getValidationRules());
            placeholder.setSelectOptions(dto.getSelectOptions());
            placeholder.persist();
            order++;
        }
    }

    private static final Set<String> REGISTRY_FIELDS =
            Set.of("AMOUNT", "CURRENCY", "VALID_FROM", "VALID_TO", "EFFECTIVE_DATE");

    private static String normalizeRegistryField(String registryField) {
        if (registryField == null || registryField.isBlank()) {
            return null;
        }
        String normalized = registryField.trim().toUpperCase(Locale.ROOT);
        if (!REGISTRY_FIELDS.contains(normalized)) {
            throw new WebApplicationException(
                    "Invalid registry field '" + registryField + "' — one of " + REGISTRY_FIELDS, 400);
        }
        return normalized;
    }

    private static TemplateClauseEntity requireClause(String uuid) {
        TemplateClauseEntity clause = uuid == null ? null : TemplateClauseEntity.findById(uuid);
        if (clause == null) {
            throw new WebApplicationException("Clause not found: " + uuid, 404);
        }
        return clause;
    }

    private TemplateClauseDTO toDto(TemplateClauseEntity clause, boolean includeDetails) {
        TemplateClauseDTO.TemplateClauseDTOBuilder builder = TemplateClauseDTO.builder()
                .uuid(clause.getUuid())
                .clauseKey(clause.getClauseKey())
                .name(clause.getName())
                .description(clause.getDescription())
                .agreementType(clause.getAgreementType())
                .renderMode(clause.getRenderMode())
                .category(clause.getCategory())
                .offerOnCategory(clause.isOfferOnCategory())
                .status(clause.getStatus())
                .activeVersionUuid(clause.getActiveVersionUuid())
                .usageCount(SigningCaseClause.countByClause(clause.getUuid()))
                .createdAt(clause.getCreatedAt())
                .updatedAt(clause.getUpdatedAt());

        List<TemplateClauseVersionEntity> versions = TemplateClauseVersionEntity.findByClause(clause.getUuid());
        versions.stream()
                .filter(v -> v.getUuid().equals(clause.getActiveVersionUuid()))
                .findFirst()
                .ifPresent(v -> builder.activeVersionNumber(v.getVersionNumber()));

        builder.placeholders(TemplateClausePlaceholderEntity.findByClause(clause.getUuid()).stream()
                .map(ClauseService::toPlaceholderDto)
                .toList());
        if (includeDetails) {
            builder.versions(versions.stream().map(ClauseService::toVersionDto).toList());
        }
        return builder.build();
    }

    static TemplateClausePlaceholderDTO toPlaceholderDto(TemplateClausePlaceholderEntity placeholder) {
        return TemplateClausePlaceholderDTO.builder()
                .uuid(placeholder.getUuid())
                .placeholderKey(placeholder.getPlaceholderKey())
                .label(placeholder.getLabel())
                .fieldType(placeholder.getFieldType())
                .required(placeholder.isRequired())
                .displayOrder(placeholder.getDisplayOrder())
                .defaultValue(placeholder.getDefaultValue())
                .helpText(placeholder.getHelpText())
                .source(placeholder.getSource())
                .sourceField(placeholder.getSourceField())
                .registryField(placeholder.getRegistryField())
                .fieldGroup(placeholder.getFieldGroup())
                .validationRules(placeholder.getValidationRules())
                .selectOptions(placeholder.getSelectOptions())
                .build();
    }

    private static TemplateClauseDTO.ClauseVersionDTO toVersionDto(TemplateClauseVersionEntity version) {
        return TemplateClauseDTO.ClauseVersionDTO.builder()
                .uuid(version.getUuid())
                .versionNumber(version.getVersionNumber())
                .fileUuid(version.getFileUuid())
                .originalFilename(version.getOriginalFilename())
                .changeNote(version.getChangeNote())
                .publishedAt(version.getPublishedAt())
                .publishedBy(version.getPublishedBy())
                .inUse(SigningCaseClause.versionInUse(version.getUuid()))
                .createdAt(version.getCreatedAt())
                .build();
    }
}
