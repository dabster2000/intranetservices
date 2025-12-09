package dk.trustworks.intranet.documentservice.services;

import dk.trustworks.intranet.documentservice.dto.DocumentTemplateDTO;
import dk.trustworks.intranet.documentservice.dto.TemplateDefaultSignerDTO;
import dk.trustworks.intranet.documentservice.dto.TemplatePlaceholderDTO;
import dk.trustworks.intranet.documentservice.dto.TemplateSigningSchemaDTO;
import dk.trustworks.intranet.documentservice.dto.TemplateSigningStoreDTO;
import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.documentservice.model.TemplateDefaultSignerEntity;
import dk.trustworks.intranet.documentservice.model.TemplatePlaceholderEntity;
import dk.trustworks.intranet.documentservice.model.TemplateSigningSchemaEntity;
import dk.trustworks.intranet.documentservice.model.TemplateSigningStoreEntity;
import dk.trustworks.intranet.documentservice.model.enums.TemplateCategory;
import jakarta.enterprise.context.RequestScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing document templates.
 * Handles business logic and DTO/Entity mapping.
 */
@JBossLog
@RequestScoped
public class TemplateService {

    /**
     * Find all templates with their placeholder counts.
     *
     * @param includeInactive Whether to include inactive templates
     * @return List of template DTOs with placeholders included
     */
    public List<DocumentTemplateDTO> findAll(boolean includeInactive) {
        List<DocumentTemplateEntity> entities = DocumentTemplateEntity.findAllIncludingInactive(includeInactive);
        return entities.stream()
                .map(this::toDTOWithPlaceholders)  // Include placeholders for count display
                .collect(Collectors.toList());
    }

    /**
     * Find templates by category with their placeholders.
     *
     * @param category The template category
     * @return List of template DTOs with placeholders included
     */
    public List<DocumentTemplateDTO> findByCategory(TemplateCategory category) {
        List<DocumentTemplateEntity> entities = DocumentTemplateEntity.findByCategory(category);
        return entities.stream()
                .map(this::toDTOWithPlaceholders)  // Include placeholders for count display
                .collect(Collectors.toList());
    }

    /**
     * Find a template by UUID.
     *
     * @param uuid The template UUID
     * @return Template DTO with placeholders
     */
    public DocumentTemplateDTO findByUuid(String uuid) {
        DocumentTemplateEntity entity = DocumentTemplateEntity.findByUuidWithPlaceholders(uuid);
        if (entity == null) {
            throw new WebApplicationException("Template not found: " + uuid, 404);
        }
        return toDTOWithPlaceholders(entity);
    }

    /**
     * Create a new template.
     *
     * @param dto Template DTO
     * @param currentUserUuid UUID of the user creating the template
     * @return Created template DTO
     */
    @Transactional
    public DocumentTemplateDTO create(DocumentTemplateDTO dto, String currentUserUuid) {
        log.infof("Creating new template: %s by user %s", dto.getName(), currentUserUuid);

        validateTemplate(dto);

        DocumentTemplateEntity entity = new DocumentTemplateEntity();
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setCategory(dto.getCategory());
        entity.setTemplateContent(dto.getTemplateContent());
        entity.setActive(dto.isActive());
        entity.setCreatedBy(currentUserUuid);
        entity.setModifiedBy(currentUserUuid);

        // Add placeholders
        if (dto.getPlaceholders() != null) {
            for (TemplatePlaceholderDTO placeholderDTO : dto.getPlaceholders()) {
                TemplatePlaceholderEntity placeholder = toPlaceholderEntity(placeholderDTO);
                entity.addPlaceholder(placeholder);
            }
        }

        // Add default signers
        if (dto.getDefaultSigners() != null) {
            for (TemplateDefaultSignerDTO signerDTO : dto.getDefaultSigners()) {
                TemplateDefaultSignerEntity signer = toDefaultSignerEntity(signerDTO);
                entity.addDefaultSigner(signer);
            }
        }

        // Add signing schemas
        if (dto.getSigningSchemas() != null) {
            for (TemplateSigningSchemaDTO schemaDTO : dto.getSigningSchemas()) {
                TemplateSigningSchemaEntity schema = toSigningSchemaEntity(schemaDTO);
                entity.addSigningSchema(schema);
            }
        }

        // Add signing stores
        if (dto.getSigningStores() != null) {
            for (TemplateSigningStoreDTO storeDTO : dto.getSigningStores()) {
                TemplateSigningStoreEntity store = toSigningStoreEntity(storeDTO);
                entity.addSigningStore(store);
            }
        }

        entity.persist();
        log.infof("Template created with uuid: %s", entity.getUuid());

        return toDTOWithPlaceholders(entity);
    }

    /**
     * Update an existing template.
     *
     * @param uuid Template UUID
     * @param dto Template DTO
     * @param currentUserUuid UUID of the user updating the template
     * @return Updated template DTO
     */
    @Transactional
    public DocumentTemplateDTO update(String uuid, DocumentTemplateDTO dto, String currentUserUuid) {
        log.infof("Updating template: %s by user %s", uuid, currentUserUuid);

        DocumentTemplateEntity entity = DocumentTemplateEntity.findById(uuid);
        if (entity == null) {
            throw new WebApplicationException("Template not found: " + uuid, 404);
        }

        validateTemplate(dto);

        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setCategory(dto.getCategory());
        entity.setTemplateContent(dto.getTemplateContent());
        entity.setActive(dto.isActive());
        entity.setModifiedBy(currentUserUuid);

        // Delete old placeholders from database first to avoid Hibernate persistence context conflicts
        long deletedCount = TemplatePlaceholderEntity.delete("template.uuid = ?1", uuid);
        log.infof("Deleted %d existing placeholders for template %s", deletedCount, uuid);
        entity.clearPlaceholders();

        // Rebuild placeholders from DTO - explicitly persist each new placeholder
        if (dto.getPlaceholders() != null && !dto.getPlaceholders().isEmpty()) {
            log.infof("Adding %d placeholders to template %s", dto.getPlaceholders().size(), uuid);
            for (TemplatePlaceholderDTO placeholderDTO : dto.getPlaceholders()) {
                TemplatePlaceholderEntity placeholder = toPlaceholderEntity(placeholderDTO);
                entity.addPlaceholder(placeholder);
                placeholder.persist();  // Explicitly persist each new placeholder
            }
        } else {
            log.infof("No placeholders to add for template %s", uuid);
        }

        // Delete old default signers from database first to avoid Hibernate persistence context conflicts
        long deletedSignerCount = TemplateDefaultSignerEntity.deleteByTemplateUuid(uuid);
        log.infof("Deleted %d existing default signers for template %s", deletedSignerCount, uuid);
        entity.clearDefaultSigners();

        // Rebuild default signers from DTO - explicitly persist each new signer
        if (dto.getDefaultSigners() != null && !dto.getDefaultSigners().isEmpty()) {
            log.infof("Adding %d default signers to template %s", dto.getDefaultSigners().size(), uuid);
            for (TemplateDefaultSignerDTO signerDTO : dto.getDefaultSigners()) {
                TemplateDefaultSignerEntity signer = toDefaultSignerEntity(signerDTO);
                entity.addDefaultSigner(signer);
                signer.persist();  // Explicitly persist each new default signer
            }
        } else {
            log.infof("No default signers to add for template %s", uuid);
        }

        // Delete old signing schemas from database first to avoid Hibernate persistence context conflicts
        long deletedSchemaCount = TemplateSigningSchemaEntity.deleteByTemplateUuid(uuid);
        log.infof("Deleted %d existing signing schemas for template %s", deletedSchemaCount, uuid);
        entity.clearSigningSchemas();

        // Rebuild signing schemas from DTO - explicitly persist each new schema
        if (dto.getSigningSchemas() != null && !dto.getSigningSchemas().isEmpty()) {
            log.infof("Adding %d signing schemas to template %s", dto.getSigningSchemas().size(), uuid);
            for (TemplateSigningSchemaDTO schemaDTO : dto.getSigningSchemas()) {
                TemplateSigningSchemaEntity schema = toSigningSchemaEntity(schemaDTO);
                entity.addSigningSchema(schema);
                schema.persist();  // Explicitly persist each new signing schema
            }
        } else {
            log.infof("No signing schemas to add for template %s", uuid);
        }

        // Delete old signing stores from database first to avoid Hibernate persistence context conflicts
        long deletedStoreCount = TemplateSigningStoreEntity.deleteByTemplateUuid(uuid);
        log.infof("Deleted %d existing signing stores for template %s", deletedStoreCount, uuid);
        entity.clearSigningStores();

        // Rebuild signing stores from DTO - explicitly persist each new store
        if (dto.getSigningStores() != null && !dto.getSigningStores().isEmpty()) {
            log.infof("Adding %d signing stores to template %s", dto.getSigningStores().size(), uuid);
            for (TemplateSigningStoreDTO storeDTO : dto.getSigningStores()) {
                TemplateSigningStoreEntity store = toSigningStoreEntity(storeDTO);
                entity.addSigningStore(store);
                store.persist();  // Explicitly persist each new signing store
            }
        } else {
            log.infof("No signing stores to add for template %s", uuid);
        }

        log.infof("Template updated: %s", uuid);

        return toDTOWithPlaceholders(entity);
    }

    /**
     * Delete a template (soft delete).
     *
     * @param uuid Template UUID
     */
    @Transactional
    public void delete(String uuid) {
        log.infof("Deleting template: %s", uuid);

        DocumentTemplateEntity entity = DocumentTemplateEntity.findById(uuid);
        if (entity == null) {
            throw new WebApplicationException("Template not found: " + uuid, 404);
        }

        entity.softDelete();
        log.infof("Template deleted (soft): %s", uuid);
    }

    /**
     * Validate a template DTO.
     *
     * @param dto Template DTO to validate
     * @throws WebApplicationException if validation fails
     */
    public void validateTemplate(DocumentTemplateDTO dto) {
        if (dto.getName() == null || dto.getName().trim().isEmpty()) {
            throw new WebApplicationException("Template name is required", 400);
        }
        if (dto.getCategory() == null) {
            throw new WebApplicationException("Template category is required", 400);
        }
        if (dto.getTemplateContent() == null || dto.getTemplateContent().trim().isEmpty()) {
            throw new WebApplicationException("Template content is required", 400);
        }

        // Validate placeholders
        if (dto.getPlaceholders() != null) {
            for (TemplatePlaceholderDTO placeholder : dto.getPlaceholders()) {
                if (placeholder.getPlaceholderKey() == null || placeholder.getPlaceholderKey().trim().isEmpty()) {
                    throw new WebApplicationException("Placeholder key is required", 400);
                }
                if (placeholder.getLabel() == null || placeholder.getLabel().trim().isEmpty()) {
                    throw new WebApplicationException("Placeholder label is required", 400);
                }
                if (placeholder.getFieldType() == null) {
                    throw new WebApplicationException("Placeholder field type is required", 400);
                }
                if (placeholder.getSource() == null) {
                    throw new WebApplicationException("Placeholder source is required", 400);
                }
            }
        }
    }

    // --- DTO/Entity Mapping ---

    /**
     * Convert entity to DTO (without placeholders).
     */
    private DocumentTemplateDTO toDTO(DocumentTemplateEntity entity) {
        return DocumentTemplateDTO.builder()
                .uuid(entity.getUuid())
                .name(entity.getName())
                .description(entity.getDescription())
                .category(entity.getCategory())
                .templateContent(entity.getTemplateContent())
                .active(entity.isActive())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .createdBy(entity.getCreatedBy())
                .modifiedBy(entity.getModifiedBy())
                .build();
    }

    /**
     * Convert entity to DTO with placeholders, defaultSigners, and signingSchemas eagerly loaded.
     */
    private DocumentTemplateDTO toDTOWithPlaceholders(DocumentTemplateEntity entity) {
        DocumentTemplateDTO dto = toDTO(entity);

        List<TemplatePlaceholderDTO> placeholderDTOs = entity.getPlaceholders().stream()
                .map(this::toPlaceholderDTO)
                .collect(Collectors.toList());
        dto.setPlaceholders(placeholderDTOs);

        List<TemplateDefaultSignerDTO> defaultSignerDTOs = entity.getDefaultSigners().stream()
                .map(this::toDefaultSignerDTO)
                .collect(Collectors.toList());
        dto.setDefaultSigners(defaultSignerDTOs);

        List<TemplateSigningSchemaDTO> signingSchemasDTOs = entity.getSigningSchemas().stream()
                .map(this::toSigningSchemaDTO)
                .collect(Collectors.toList());
        dto.setSigningSchemas(signingSchemasDTOs);

        List<TemplateSigningStoreDTO> signingStoreDTOs = entity.getSigningStores().stream()
                .map(this::toSigningStoreDTO)
                .collect(Collectors.toList());
        dto.setSigningStores(signingStoreDTOs);

        return dto;
    }

    /**
     * Convert placeholder entity to DTO.
     */
    private TemplatePlaceholderDTO toPlaceholderDTO(TemplatePlaceholderEntity entity) {
        return TemplatePlaceholderDTO.builder()
                .uuid(entity.getUuid())
                .placeholderKey(entity.getPlaceholderKey())
                .label(entity.getLabel())
                .fieldType(entity.getFieldType())
                .required(entity.isRequired())
                .displayOrder(entity.getDisplayOrder())
                .defaultValue(entity.getDefaultValue())
                .helpText(entity.getHelpText())
                .source(entity.getSource())
                .fieldGroup(entity.getFieldGroup())
                .validationRules(entity.getValidationRules())
                .selectOptions(entity.getSelectOptions())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    /**
     * Convert placeholder DTO to entity.
     */
    private TemplatePlaceholderEntity toPlaceholderEntity(TemplatePlaceholderDTO dto) {
        TemplatePlaceholderEntity entity = new TemplatePlaceholderEntity();
        if (dto.getUuid() != null) {
            entity.setUuid(dto.getUuid());
        }
        entity.setPlaceholderKey(dto.getPlaceholderKey());
        entity.setLabel(dto.getLabel());
        entity.setFieldType(dto.getFieldType());
        entity.setRequired(dto.isRequired());
        entity.setDisplayOrder(dto.getDisplayOrder());
        entity.setDefaultValue(dto.getDefaultValue());
        entity.setHelpText(dto.getHelpText());
        entity.setSource(dto.getSource());
        entity.setFieldGroup(dto.getFieldGroup());
        entity.setValidationRules(dto.getValidationRules());
        entity.setSelectOptions(dto.getSelectOptions());
        return entity;
    }

    /**
     * Convert default signer entity to DTO.
     */
    private TemplateDefaultSignerDTO toDefaultSignerDTO(TemplateDefaultSignerEntity entity) {
        return TemplateDefaultSignerDTO.builder()
                .uuid(entity.getUuid())
                .signerGroup(entity.getSignerGroup())
                .name(entity.getName())
                .email(entity.getEmail())
                .role(entity.getRole())
                .displayOrder(entity.getDisplayOrder())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    /**
     * Convert default signer DTO to entity.
     */
    private TemplateDefaultSignerEntity toDefaultSignerEntity(TemplateDefaultSignerDTO dto) {
        TemplateDefaultSignerEntity entity = new TemplateDefaultSignerEntity();
        if (dto.getUuid() != null) {
            entity.setUuid(dto.getUuid());
        }
        entity.setSignerGroup(dto.getSignerGroup());
        entity.setName(dto.getName());
        entity.setEmail(dto.getEmail());
        entity.setRole(dto.getRole());
        entity.setDisplayOrder(dto.getDisplayOrder());
        return entity;
    }

    /**
     * Convert signing schema entity to DTO.
     */
    private TemplateSigningSchemaDTO toSigningSchemaDTO(TemplateSigningSchemaEntity entity) {
        return TemplateSigningSchemaDTO.builder()
                .uuid(entity.getUuid())
                .schemaType(entity.getSchemaType())
                .displayOrder(entity.getDisplayOrder())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /**
     * Convert signing schema DTO to entity.
     */
    private TemplateSigningSchemaEntity toSigningSchemaEntity(TemplateSigningSchemaDTO dto) {
        TemplateSigningSchemaEntity entity = new TemplateSigningSchemaEntity();
        if (dto.getUuid() != null) {
            entity.setUuid(dto.getUuid());
        }
        entity.setSchemaType(dto.getSchemaType());
        entity.setDisplayOrder(dto.getDisplayOrder());
        return entity;
    }

    /**
     * Convert signing store entity to DTO.
     */
    private TemplateSigningStoreDTO toSigningStoreDTO(TemplateSigningStoreEntity entity) {
        return TemplateSigningStoreDTO.builder()
                .uuid(entity.getUuid())
                .siteUrl(entity.getSiteUrl())
                .driveName(entity.getDriveName())
                .folderPath(entity.getFolderPath())
                .displayName(entity.getDisplayName())
                .isActive(entity.getIsActive() != null ? entity.getIsActive() : true)
                .displayOrder(entity.getDisplayOrder() != null ? entity.getDisplayOrder() : 1)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    /**
     * Convert signing store DTO to entity.
     */
    private TemplateSigningStoreEntity toSigningStoreEntity(TemplateSigningStoreDTO dto) {
        TemplateSigningStoreEntity entity = new TemplateSigningStoreEntity();
        if (dto.getUuid() != null) {
            entity.setUuid(dto.getUuid());
        }
        entity.setSiteUrl(dto.getSiteUrl());
        entity.setDriveName(dto.getDriveName());
        entity.setFolderPath(dto.getFolderPath());
        entity.setDisplayName(dto.getDisplayName());
        entity.setIsActive(dto.isActive());
        entity.setDisplayOrder(dto.getDisplayOrder());
        return entity;
    }
}
