package dk.trustworks.intranet.documentservice.utils;

import dk.trustworks.intranet.documentservice.dto.DocumentTemplateDTO;
import dk.trustworks.intranet.documentservice.dto.SharePointLocationDTO;
import dk.trustworks.intranet.documentservice.dto.TemplateDefaultSignerDTO;
import dk.trustworks.intranet.documentservice.dto.TemplatePlaceholderDTO;
import dk.trustworks.intranet.documentservice.dto.TemplateSigningSchemaDTO;
import dk.trustworks.intranet.documentservice.dto.TemplateSigningStoreDTO;
import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.documentservice.model.SharePointLocationEntity;
import dk.trustworks.intranet.documentservice.model.TemplateDefaultSignerEntity;
import dk.trustworks.intranet.documentservice.model.TemplatePlaceholderEntity;
import dk.trustworks.intranet.documentservice.model.TemplateSigningSchemaEntity;
import dk.trustworks.intranet.documentservice.model.TemplateSigningStoreEntity;
import dk.trustworks.intranet.documentservice.model.enums.TemplateCategory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Test data builders for creating test fixtures.
 * Provides fluent builders for entities and DTOs with sensible defaults.
 */
public class TestDataBuilders {

    /**
     * Builder for SharePointLocationEntity with sensible defaults.
     */
    public static class SharePointLocationEntityBuilder {
        private String uuid = UUID.randomUUID().toString();
        private String name = "Test SharePoint Location";
        private String siteUrl = "https://trustworks.sharepoint.com/sites/test";
        private String driveName = "Documents";
        private String folderPath = "/Contracts";
        private Boolean isActive = true;
        private Integer displayOrder = 1;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt = LocalDateTime.now();

        public SharePointLocationEntityBuilder uuid(String uuid) {
            this.uuid = uuid;
            return this;
        }

        public SharePointLocationEntityBuilder name(String name) {
            this.name = name;
            return this;
        }

        public SharePointLocationEntityBuilder siteUrl(String siteUrl) {
            this.siteUrl = siteUrl;
            return this;
        }

        public SharePointLocationEntityBuilder driveName(String driveName) {
            this.driveName = driveName;
            return this;
        }

        public SharePointLocationEntityBuilder folderPath(String folderPath) {
            this.folderPath = folderPath;
            return this;
        }

        public SharePointLocationEntityBuilder isActive(Boolean isActive) {
            this.isActive = isActive;
            return this;
        }

        public SharePointLocationEntityBuilder displayOrder(Integer displayOrder) {
            this.displayOrder = displayOrder;
            return this;
        }

        public SharePointLocationEntityBuilder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public SharePointLocationEntityBuilder updatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public SharePointLocationEntity build() {
            SharePointLocationEntity entity = new SharePointLocationEntity();
            entity.setUuid(uuid);
            entity.setName(name);
            entity.setSiteUrl(siteUrl);
            entity.setDriveName(driveName);
            entity.setFolderPath(folderPath);
            entity.setIsActive(isActive);
            entity.setDisplayOrder(displayOrder);
            entity.setCreatedAt(createdAt);
            entity.setUpdatedAt(updatedAt);
            return entity;
        }
    }

    /**
     * Builder for SharePointLocationDTO with sensible defaults.
     */
    public static class SharePointLocationDTOBuilder {
        private String uuid = UUID.randomUUID().toString();
        private String name = "Test SharePoint Location";
        private String siteUrl = "https://trustworks.sharepoint.com/sites/test";
        private String driveName = "Documents";
        private String folderPath = "/Contracts";
        private boolean isActive = true;
        private int displayOrder = 1;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt = LocalDateTime.now();
        private long referenceCount = 0;

        public SharePointLocationDTOBuilder uuid(String uuid) {
            this.uuid = uuid;
            return this;
        }

        public SharePointLocationDTOBuilder name(String name) {
            this.name = name;
            return this;
        }

        public SharePointLocationDTOBuilder siteUrl(String siteUrl) {
            this.siteUrl = siteUrl;
            return this;
        }

        public SharePointLocationDTOBuilder driveName(String driveName) {
            this.driveName = driveName;
            return this;
        }

        public SharePointLocationDTOBuilder folderPath(String folderPath) {
            this.folderPath = folderPath;
            return this;
        }

        public SharePointLocationDTOBuilder isActive(boolean isActive) {
            this.isActive = isActive;
            return this;
        }

        public SharePointLocationDTOBuilder displayOrder(int displayOrder) {
            this.displayOrder = displayOrder;
            return this;
        }

        public SharePointLocationDTOBuilder referenceCount(long referenceCount) {
            this.referenceCount = referenceCount;
            return this;
        }

        public SharePointLocationDTO build() {
            return SharePointLocationDTO.builder()
                    .uuid(uuid)
                    .name(name)
                    .siteUrl(siteUrl)
                    .driveName(driveName)
                    .folderPath(folderPath)
                    .isActive(isActive)
                    .displayOrder(displayOrder)
                    .createdAt(createdAt)
                    .updatedAt(updatedAt)
                    .referenceCount(referenceCount)
                    .build();
        }
    }

    /**
     * Builder for DocumentTemplateEntity with sensible defaults.
     */
    public static class DocumentTemplateEntityBuilder {
        private String uuid = UUID.randomUUID().toString();
        private String name = "Test Template";
        private String description = "Test description";
        private TemplateCategory category = TemplateCategory.EMPLOYMENT;
        private boolean active = true;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt = LocalDateTime.now();
        private String createdBy = "test-user";
        private String modifiedBy = "test-user";
        private List<TemplatePlaceholderEntity> placeholders = new ArrayList<>();
        private List<TemplateDefaultSignerEntity> defaultSigners = new ArrayList<>();
        private List<TemplateSigningSchemaEntity> signingSchemas = new ArrayList<>();
        private List<TemplateSigningStoreEntity> signingStores = new ArrayList<>();

        public DocumentTemplateEntityBuilder uuid(String uuid) {
            this.uuid = uuid;
            return this;
        }

        public DocumentTemplateEntityBuilder name(String name) {
            this.name = name;
            return this;
        }

        public DocumentTemplateEntityBuilder description(String description) {
            this.description = description;
            return this;
        }

        public DocumentTemplateEntityBuilder category(TemplateCategory category) {
            this.category = category;
            return this;
        }

        public DocumentTemplateEntityBuilder active(boolean active) {
            this.active = active;
            return this;
        }

        public DocumentTemplateEntityBuilder createdBy(String createdBy) {
            this.createdBy = createdBy;
            return this;
        }

        public DocumentTemplateEntityBuilder modifiedBy(String modifiedBy) {
            this.modifiedBy = modifiedBy;
            return this;
        }

        public DocumentTemplateEntityBuilder addPlaceholder(TemplatePlaceholderEntity placeholder) {
            this.placeholders.add(placeholder);
            return this;
        }

        public DocumentTemplateEntityBuilder addDefaultSigner(TemplateDefaultSignerEntity signer) {
            this.defaultSigners.add(signer);
            return this;
        }

        public DocumentTemplateEntity build() {
            DocumentTemplateEntity entity = new DocumentTemplateEntity();
            entity.setUuid(uuid);
            entity.setName(name);
            entity.setDescription(description);
            entity.setCategory(category);
            entity.setActive(active);
            entity.setCreatedAt(createdAt);
            entity.setUpdatedAt(updatedAt);
            entity.setCreatedBy(createdBy);
            entity.setModifiedBy(modifiedBy);
            entity.setPlaceholders(placeholders);
            entity.setDefaultSigners(defaultSigners);
            entity.setSigningSchemas(signingSchemas);
            entity.setSigningStores(signingStores);
            return entity;
        }
    }

    /**
     * Builder for DocumentTemplateDTO with sensible defaults.
     */
    public static class DocumentTemplateDTOBuilder {
        private String uuid = UUID.randomUUID().toString();
        private String name = "Test Template";
        private String description = "Test description";
        private TemplateCategory category = TemplateCategory.EMPLOYMENT;
        private boolean active = true;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt = LocalDateTime.now();
        private String createdBy = "test-user";
        private String modifiedBy = "test-user";
        private List<TemplatePlaceholderDTO> placeholders = new ArrayList<>();
        private List<TemplateDefaultSignerDTO> defaultSigners = new ArrayList<>();
        private List<TemplateSigningSchemaDTO> signingSchemas = new ArrayList<>();
        private List<TemplateSigningStoreDTO> signingStores = new ArrayList<>();

        public DocumentTemplateDTOBuilder uuid(String uuid) {
            this.uuid = uuid;
            return this;
        }

        public DocumentTemplateDTOBuilder name(String name) {
            this.name = name;
            return this;
        }

        public DocumentTemplateDTOBuilder description(String description) {
            this.description = description;
            return this;
        }

        public DocumentTemplateDTOBuilder category(TemplateCategory category) {
            this.category = category;
            return this;
        }

        public DocumentTemplateDTOBuilder active(boolean active) {
            this.active = active;
            return this;
        }

        public DocumentTemplateDTOBuilder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public DocumentTemplateDTOBuilder updatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public DocumentTemplateDTOBuilder createdBy(String createdBy) {
            this.createdBy = createdBy;
            return this;
        }

        public DocumentTemplateDTOBuilder modifiedBy(String modifiedBy) {
            this.modifiedBy = modifiedBy;
            return this;
        }

        public DocumentTemplateDTOBuilder addPlaceholder(TemplatePlaceholderDTO placeholder) {
            this.placeholders.add(placeholder);
            return this;
        }

        public DocumentTemplateDTO build() {
            return DocumentTemplateDTO.builder()
                    .uuid(uuid)
                    .name(name)
                    .description(description)
                    .category(category)
                    .active(active)
                    .createdAt(createdAt)
                    .updatedAt(updatedAt)
                    .createdBy(createdBy)
                    .modifiedBy(modifiedBy)
                    .placeholders(placeholders)
                    .defaultSigners(defaultSigners)
                    .signingSchemas(signingSchemas)
                    .signingStores(signingStores)
                    .build();
        }
    }

    // Static factory methods
    public static SharePointLocationEntityBuilder sharePointLocationEntity() {
        return new SharePointLocationEntityBuilder();
    }

    public static SharePointLocationDTOBuilder sharePointLocationDTO() {
        return new SharePointLocationDTOBuilder();
    }

    public static DocumentTemplateEntityBuilder documentTemplateEntity() {
        return new DocumentTemplateEntityBuilder();
    }

    public static DocumentTemplateDTOBuilder documentTemplateDTO() {
        return new DocumentTemplateDTOBuilder();
    }

    /**
     * Creates a valid Word document byte array with ZIP signature.
     * DOCX files are ZIP archives, so they must start with the ZIP signature: 0x50 0x4B 0x03 0x04.
     *
     * @return Minimal valid Word document byte array
     */
    public static byte[] validWordDocumentBytes() {
        // Minimal ZIP file with required signature
        return new byte[]{
                0x50, 0x4B, 0x03, 0x04,  // ZIP signature
                0x14, 0x00, 0x00, 0x00,  // Version, flags
                0x08, 0x00, 0x00, 0x00,  // Compression method, time
                0x00, 0x00, 0x00, 0x00,  // CRC-32
                0x00, 0x00, 0x00, 0x00,  // Compressed size
                0x00, 0x00, 0x00, 0x00,  // Uncompressed size
                0x00, 0x00,              // Filename length
                0x00, 0x00,              // Extra field length
        };
    }

    /**
     * Creates Base64-encoded valid Word document content.
     *
     * @return Base64-encoded Word document
     */
    public static String validWordDocumentBase64() {
        return Base64.getEncoder().encodeToString(validWordDocumentBytes());
    }

    /**
     * Creates an invalid file (not a Word document).
     *
     * @return Invalid file bytes
     */
    public static byte[] invalidFileBytes() {
        return "This is not a Word document".getBytes();
    }

    /**
     * Creates Base64-encoded invalid file content.
     *
     * @return Base64-encoded invalid file
     */
    public static String invalidFileBase64() {
        return Base64.getEncoder().encodeToString(invalidFileBytes());
    }
}
