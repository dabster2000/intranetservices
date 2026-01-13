package dk.trustworks.intranet.documentservice.services;

import dk.trustworks.intranet.documentservice.dto.DocumentTemplateDTO;
import dk.trustworks.intranet.documentservice.dto.TemplateDocumentDTO;
import dk.trustworks.intranet.documentservice.dto.TemplatePlaceholderDTO;
import dk.trustworks.intranet.documentservice.dto.TemplateSigningStoreDTO;
import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.documentservice.model.SharePointLocationEntity;
import dk.trustworks.intranet.documentservice.model.TemplateDocumentEntity;
import dk.trustworks.intranet.documentservice.model.TemplatePlaceholderEntity;
import dk.trustworks.intranet.documentservice.model.TemplateSigningStoreEntity;
import dk.trustworks.intranet.documentservice.model.enums.FieldType;
import dk.trustworks.intranet.documentservice.model.enums.DataSource;
import dk.trustworks.intranet.documentservice.model.enums.TemplateCategory;
import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.*;

import static dk.trustworks.intranet.documentservice.utils.TestDataBuilders.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Unit tests for TemplateService.
 * Tests business logic for template management including CRUD operations,
 * validation, cascading deletes, and DTO/Entity mapping.
 */
@QuarkusTest
@DisplayName("TemplateService Unit Tests")
class TemplateServiceTest {

    @Inject
    TemplateService templateService;

    @BeforeEach
    void setupMocks() {
        PanacheMock.reset();
    }

    // =========================================================================
    // FIND ALL Tests
    // =========================================================================

    @Nested
    @DisplayName("findAll() Tests")
    class FindAllTests {

        @Test
        @DisplayName("includeInactive=false → returns only active templates")
        void findAll_excludeInactive_returnsActiveOnly() {
            // Given
            DocumentTemplateEntity activeTemplate = documentTemplateEntity()
                    .uuid("active-uuid")
                    .name("Active Template")
                    .active(true)
                    .build();

            PanacheMock.mock(DocumentTemplateEntity.class);
            PanacheMock.doReturn(Collections.singletonList(activeTemplate))
                    .when(DocumentTemplateEntity.class)
                    .findAllIncludingInactive(false);

            // When
            List<DocumentTemplateDTO> result = templateService.findAll(false);

            // Then
            assertEquals(1, result.size());
            assertEquals("Active Template", result.get(0).getName());
            assertTrue(result.get(0).isActive());
        }

        @Test
        @DisplayName("includeInactive=true → returns all templates")
        void findAll_includeInactive_returnsAll() {
            // Given
            DocumentTemplateEntity activeTemplate = documentTemplateEntity()
                    .active(true)
                    .build();

            DocumentTemplateEntity inactiveTemplate = documentTemplateEntity()
                    .active(false)
                    .build();

            PanacheMock.mock(DocumentTemplateEntity.class);
            PanacheMock.doReturn(Arrays.asList(activeTemplate, inactiveTemplate))
                    .when(DocumentTemplateEntity.class)
                    .findAllIncludingInactive(true);

            // When
            List<DocumentTemplateDTO> result = templateService.findAll(true);

            // Then
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("Empty result → returns empty list")
        void findAll_noTemplates_returnsEmptyList() {
            // Given
            PanacheMock.mock(DocumentTemplateEntity.class);
            PanacheMock.doReturn(Collections.emptyList())
                    .when(DocumentTemplateEntity.class)
                    .findAllIncludingInactive(anyBoolean());

            // When
            List<DocumentTemplateDTO> result = templateService.findAll(false);

            // Then
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // =========================================================================
    // FIND BY CATEGORY Tests
    // =========================================================================

    @Nested
    @DisplayName("findByCategory() Tests")
    class FindByCategoryTests {

        @Test
        @DisplayName("Valid category → returns filtered templates")
        void findByCategory_validCategory_returnsFiltered() {
            // Given
            DocumentTemplateEntity template = documentTemplateEntity()
                    .category(TemplateCategory.EMPLOYMENT)
                    .name("Employment Contract")
                    .build();

            PanacheMock.mock(DocumentTemplateEntity.class);
            PanacheMock.doReturn(Collections.singletonList(template))
                    .when(DocumentTemplateEntity.class)
                    .findByCategory(TemplateCategory.EMPLOYMENT);

            // When
            List<DocumentTemplateDTO> result = templateService.findByCategory(TemplateCategory.EMPLOYMENT);

            // Then
            assertEquals(1, result.size());
            assertEquals(TemplateCategory.EMPLOYMENT, result.get(0).getCategory());
        }

        @Test
        @DisplayName("No results → returns empty list")
        void findByCategory_noResults_returnsEmptyList() {
            // Given
            PanacheMock.mock(DocumentTemplateEntity.class);
            PanacheMock.doReturn(Collections.emptyList())
                    .when(DocumentTemplateEntity.class)
                    .findByCategory(any(TemplateCategory.class));

            // When
            List<DocumentTemplateDTO> result = templateService.findByCategory(TemplateCategory.NDA);

            // Then
            assertTrue(result.isEmpty());
        }
    }

    // =========================================================================
    // FIND BY UUID Tests
    // =========================================================================

    @Nested
    @DisplayName("findByUuid() Tests")
    class FindByUuidTests {

        @Test
        @DisplayName("Existing UUID → returns template with placeholders")
        void findByUuid_existingUuid_returnsWithPlaceholders() {
            // Given
            String uuid = "test-uuid";
            TemplatePlaceholderEntity placeholder = new TemplatePlaceholderEntity();
            placeholder.setUuid("placeholder-uuid");
            placeholder.setPlaceholderKey("key");
            placeholder.setLabel("Label");
            placeholder.setFieldType(FieldType.TEXT);
            placeholder.setSource(DataSource.MANUAL);

            DocumentTemplateEntity template = documentTemplateEntity()
                    .uuid(uuid)
                    .name("Test Template")
                    .addPlaceholder(placeholder)
                    .build();

            PanacheMock.mock(DocumentTemplateEntity.class);
            PanacheMock.doReturn(template)
                    .when(DocumentTemplateEntity.class)
                    .findByUuidWithPlaceholders(uuid);

            // When
            DocumentTemplateDTO result = templateService.findByUuid(uuid);

            // Then
            assertNotNull(result);
            assertEquals(uuid, result.getUuid());
            assertEquals(1, result.getPlaceholders().size());
        }

        @Test
        @DisplayName("Non-existent UUID → throws 404")
        void findByUuid_nonExistent_throws404() {
            // Given
            String uuid = "non-existent";
            PanacheMock.mock(DocumentTemplateEntity.class);
            PanacheMock.doReturn(null)
                    .when(DocumentTemplateEntity.class)
                    .findByUuidWithPlaceholders(uuid);

            // When / Then
            WebApplicationException exception = assertThrows(WebApplicationException.class,
                    () -> templateService.findByUuid(uuid));

            assertEquals(404, exception.getResponse().getStatus());
        }
    }

    // =========================================================================
    // CREATE Tests
    // =========================================================================

    @Nested
    @DisplayName("create() Tests")
    class CreateTests {

        @Test
        @DisplayName("Valid template → creates successfully")
        void create_validTemplate_createsSuccessfully() {
            // Given
            DocumentTemplateDTO dto = documentTemplateDTO()
                    .uuid(null)
                    .name("New Template")
                    .category(TemplateCategory.EMPLOYMENT)
                    .build();

            dto.getDocuments().add(TemplateDocumentDTO.builder()
                    .documentName("Contract.docx")
                    .fileUuid("file-uuid")
                    .build());

            PanacheMock.mock(DocumentTemplateEntity.class);

            // When
            DocumentTemplateDTO result = templateService.create(dto, "test-user");

            // Then
            assertNotNull(result);
            assertEquals("New Template", result.getName());
        }

        @Test
        @DisplayName("With placeholders → persists all")
        void create_withPlaceholders_persistsAll() {
            // Given
            DocumentTemplateDTO dto = documentTemplateDTO()
                    .name("Template")
                    .build();

            dto.getDocuments().add(TemplateDocumentDTO.builder()
                    .documentName("Doc.docx")
                    .fileUuid("file-uuid")
                    .build());

            dto.getPlaceholders().add(TemplatePlaceholderDTO.builder()
                    .placeholderKey("key1")
                    .label("Label 1")
                    .fieldType(FieldType.TEXT)
                    .source(DataSource.MANUAL)
                    .build());

            PanacheMock.mock(DocumentTemplateEntity.class);
            PanacheMock.mock(TemplateDocumentEntity.class);

            // When
            DocumentTemplateDTO result = templateService.create(dto, "test-user");

            // Then
            assertEquals(1, result.getPlaceholders().size());
        }

        @Test
        @DisplayName("Missing name → throws 400")
        void create_missingName_throws400() {
            // Given
            DocumentTemplateDTO dto = documentTemplateDTO()
                    .name(null)
                    .build();

            // When / Then
            WebApplicationException exception = assertThrows(WebApplicationException.class,
                    () -> templateService.create(dto, "test-user"));

            assertEquals(400, exception.getResponse().getStatus());
        }

        @Test
        @DisplayName("Missing category → throws 400")
        void create_missingCategory_throws400() {
            // Given
            DocumentTemplateDTO dto = documentTemplateDTO()
                    .category(null)
                    .build();

            // When / Then
            WebApplicationException exception = assertThrows(WebApplicationException.class,
                    () -> templateService.create(dto, "test-user"));

            assertEquals(400, exception.getResponse().getStatus());
        }

        @Test
        @DisplayName("Missing documents → throws 400")
        void create_missingDocuments_throws400() {
            // Given
            DocumentTemplateDTO dto = documentTemplateDTO()
                    .name("Template")
                    .build();
            dto.setDocuments(null);

            // When / Then
            WebApplicationException exception = assertThrows(WebApplicationException.class,
                    () -> templateService.create(dto, "test-user"));

            assertEquals(400, exception.getResponse().getStatus());
            assertTrue(exception.getMessage().contains("document"));
        }
    }

    // =========================================================================
    // UPDATE Tests
    // =========================================================================

    @Nested
    @DisplayName("update() Tests")
    class UpdateTests {

        @Test
        @DisplayName("Valid update → updates successfully")
        void update_validUpdate_updatesSuccessfully() {
            // Given
            String uuid = "existing-uuid";
            DocumentTemplateEntity existingEntity = documentTemplateEntity()
                    .uuid(uuid)
                    .name("Old Name")
                    .build();

            DocumentTemplateDTO updateDTO = documentTemplateDTO()
                    .name("New Name")
                    .category(TemplateCategory.NDA)
                    .build();

            updateDTO.getDocuments().add(TemplateDocumentDTO.builder()
                    .documentName("Doc.docx")
                    .fileUuid("file-uuid")
                    .build());

            PanacheMock.mock(DocumentTemplateEntity.class);
            PanacheMock.mock(TemplatePlaceholderEntity.class);
            PanacheMock.mock(TemplateDocumentEntity.class);

            PanacheMock.doReturn(existingEntity)
                    .when(DocumentTemplateEntity.class)
                    .findById(uuid);

            PanacheMock.doReturn(0L)
                    .when(TemplatePlaceholderEntity.class)
                    .delete(anyString(), (Object[]) any());

            PanacheMock.doReturn(0L)
                    .when(TemplateDocumentEntity.class)
                    .deleteByTemplateUuid(uuid);

            // When
            DocumentTemplateDTO result = templateService.update(uuid, updateDTO, "test-user");

            // Then
            assertNotNull(result);
            assertEquals("New Name", result.getName());
            assertEquals(TemplateCategory.NDA, result.getCategory());
        }

        @Test
        @DisplayName("Cascading delete → removes orphaned children")
        void update_cascadingDelete_removesOrphanedChildren() {
            // Given
            String uuid = "template-uuid";
            DocumentTemplateEntity existingEntity = documentTemplateEntity()
                    .uuid(uuid)
                    .build();

            DocumentTemplateDTO updateDTO = documentTemplateDTO()
                    .name("Template")
                    .build();

            updateDTO.getDocuments().add(TemplateDocumentDTO.builder()
                    .documentName("Doc.docx")
                    .fileUuid("file-uuid")
                    .build());

            PanacheMock.mock(DocumentTemplateEntity.class);
            PanacheMock.mock(TemplatePlaceholderEntity.class);
            PanacheMock.mock(TemplateDocumentEntity.class);

            PanacheMock.doReturn(existingEntity)
                    .when(DocumentTemplateEntity.class)
                    .findById(uuid);

            PanacheMock.doReturn(3L)  // 3 old placeholders deleted
                    .when(TemplatePlaceholderEntity.class)
                    .delete(anyString(), (Object[]) any());

            PanacheMock.doReturn(0L)
                    .when(TemplateDocumentEntity.class)
                    .deleteByTemplateUuid(uuid);

            // When
            DocumentTemplateDTO result = templateService.update(uuid, updateDTO, "test-user");

            // Then
            assertNotNull(result);
        }

        @Test
        @DisplayName("Non-existent UUID → throws 404")
        void update_nonExistent_throws404() {
            // Given
            String uuid = "non-existent";
            DocumentTemplateDTO updateDTO = documentTemplateDTO().build();

            PanacheMock.mock(DocumentTemplateEntity.class);
            PanacheMock.doReturn(null)
                    .when(DocumentTemplateEntity.class)
                    .findById(uuid);

            // When / Then
            WebApplicationException exception = assertThrows(WebApplicationException.class,
                    () -> templateService.update(uuid, updateDTO, "test-user"));

            assertEquals(404, exception.getResponse().getStatus());
        }
    }

    // =========================================================================
    // DELETE Tests
    // =========================================================================

    @Nested
    @DisplayName("delete() Tests")
    class DeleteTests {

        @Test
        @DisplayName("Existing UUID → soft deletes")
        void delete_existingUuid_softDeletes() {
            // Given
            String uuid = "deletable-uuid";
            DocumentTemplateEntity entity = documentTemplateEntity()
                    .uuid(uuid)
                    .active(true)
                    .build();

            PanacheMock.mock(DocumentTemplateEntity.class);
            PanacheMock.doReturn(entity)
                    .when(DocumentTemplateEntity.class)
                    .findById(uuid);

            // When
            templateService.delete(uuid);

            // Then
            assertFalse(entity.isActive());
        }

        @Test
        @DisplayName("Non-existent UUID → throws 404")
        void delete_nonExistent_throws404() {
            // Given
            String uuid = "non-existent";
            PanacheMock.mock(DocumentTemplateEntity.class);
            PanacheMock.doReturn(null)
                    .when(DocumentTemplateEntity.class)
                    .findById(uuid);

            // When / Then
            WebApplicationException exception = assertThrows(WebApplicationException.class,
                    () -> templateService.delete(uuid));

            assertEquals(404, exception.getResponse().getStatus());
        }
    }

    // =========================================================================
    // VALIDATION Tests
    // =========================================================================

    @Nested
    @DisplayName("validateTemplate() Tests")
    class ValidationTests {

        @Test
        @DisplayName("Valid template → passes")
        void validateTemplate_valid_passes() {
            // Given
            DocumentTemplateDTO dto = documentTemplateDTO()
                    .name("Valid Template")
                    .category(TemplateCategory.EMPLOYMENT)
                    .build();

            dto.getDocuments().add(TemplateDocumentDTO.builder()
                    .documentName("Doc.docx")
                    .fileUuid("file-uuid")
                    .build());

            // When / Then
            assertDoesNotThrow(() -> templateService.validateTemplate(dto));
        }

        @Test
        @DisplayName("Invalid placeholder → throws 400")
        void validateTemplate_invalidPlaceholder_throws400() {
            // Given
            DocumentTemplateDTO dto = documentTemplateDTO()
                    .name("Template")
                    .category(TemplateCategory.EMPLOYMENT)
                    .build();

            dto.getDocuments().add(TemplateDocumentDTO.builder()
                    .documentName("Doc.docx")
                    .fileUuid("file-uuid")
                    .build());

            dto.getPlaceholders().add(TemplatePlaceholderDTO.builder()
                    .placeholderKey("")  // Invalid: blank key
                    .label("Label")
                    .fieldType(FieldType.TEXT)
                    .source(DataSource.MANUAL)
                    .build());

            // When / Then
            WebApplicationException exception = assertThrows(WebApplicationException.class,
                    () -> templateService.validateTemplate(dto));

            assertEquals(400, exception.getResponse().getStatus());
        }
    }

    // =========================================================================
    // SIGNING STORES Tests
    // =========================================================================

    @Nested
    @DisplayName("findAllActiveSigningStores() Tests")
    class SigningStoresTests {

        @Test
        @DisplayName("Returns all active signing stores")
        void findAllActiveSigningStores_returnsActive() {
            // Given
            SharePointLocationEntity location = sharePointLocationEntity()
                    .uuid("location-uuid")
                    .name("SharePoint Location")
                    .build();

            TemplateSigningStoreEntity store1 = new TemplateSigningStoreEntity();
            store1.setUuid("store-1");
            store1.setLocation(location);
            store1.setIsActive(true);
            store1.setDisplayOrder(1);

            PanacheMock.mock(TemplateSigningStoreEntity.class);
            PanacheMock.doReturn(Collections.singletonList(store1))
                    .when(TemplateSigningStoreEntity.class)
                    .findAllActive();

            // When
            List<TemplateSigningStoreDTO> result = templateService.findAllActiveSigningStores();

            // Then
            assertEquals(1, result.size());
            assertEquals("store-1", result.get(0).getUuid());
        }
    }
}
