package dk.trustworks.intranet.documentservice.model;

import dk.trustworks.intranet.documentservice.model.enums.FieldType;
import dk.trustworks.intranet.documentservice.model.enums.DataSource;
import dk.trustworks.intranet.documentservice.model.enums.TemplateCategory;
import dk.trustworks.intranet.documentservice.model.enums.SigningSchemaType;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;

import static dk.trustworks.intranet.documentservice.utils.AssertionHelpers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DocumentTemplateEntity.
 * Tests lifecycle hooks, relationships, cascades, soft delete, and business logic.
 */
@QuarkusTest
@DisplayName("DocumentTemplateEntity Unit Tests")
class DocumentTemplateEntityTest {

    // =========================================================================
    // LIFECYCLE HOOKS Tests
    // =========================================================================

    @Nested
    @DisplayName("Lifecycle Hooks")
    class LifecycleHooksTests {

        @Test
        @DisplayName("@PrePersist → auto-generates UUID and timestamps")
        void prePersist_autoGeneratesUuidAndTimestamps() {
            // Given
            DocumentTemplateEntity entity = new DocumentTemplateEntity();
            entity.setName("Test Template");
            entity.setCategory(TemplateCategory.EMPLOYMENT);

            // When
            entity.onCreate();

            // Then
            assertValidUuid(entity.getUuid());
            assertNotNull(entity.getCreatedAt());
            assertNotNull(entity.getUpdatedAt());
            assertRecentTimestamp(entity.getCreatedAt());
            assertRecentTimestamp(entity.getUpdatedAt());
        }

        @Test
        @DisplayName("@PreUpdate → updates updatedAt timestamp")
        void preUpdate_updatesTimestamp() {
            // Given
            DocumentTemplateEntity entity = new DocumentTemplateEntity();
            entity.onCreate();

            LocalDateTime originalUpdatedAt = entity.getUpdatedAt();

            // Simulate a small delay
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // When
            entity.onUpdate();

            // Then
            assertNotNull(entity.getUpdatedAt());
            assertTrue(entity.getUpdatedAt().isAfter(originalUpdatedAt));
        }

        @Test
        @DisplayName("@PrePersist with existing UUID → preserves UUID")
        void prePersist_withExistingUuid_preservesUuid() {
            // Given
            String existingUuid = "existing-template-uuid";
            DocumentTemplateEntity entity = new DocumentTemplateEntity();
            entity.setUuid(existingUuid);
            entity.setName("Template");
            entity.setCategory(TemplateCategory.NDA);

            // When
            entity.onCreate();

            // Then
            assertEquals(existingUuid, entity.getUuid());
        }
    }

    // =========================================================================
    // RELATIONSHIPS Tests
    // =========================================================================

    @Nested
    @DisplayName("Relationships")
    class RelationshipsTests {

        @Test
        @DisplayName("addPlaceholder() → establishes bidirectional relationship")
        void addPlaceholder_establishesBidirectionalRelationship() {
            // Given
            DocumentTemplateEntity template = new DocumentTemplateEntity();
            template.onCreate();

            TemplatePlaceholderEntity placeholder = new TemplatePlaceholderEntity();
            placeholder.setPlaceholderKey("employee_name");
            placeholder.setLabel("Employee Name");
            placeholder.setFieldType(FieldType.TEXT);
            placeholder.setSource(DataSource.MANUAL);

            // When
            template.addPlaceholder(placeholder);

            // Then
            assertEquals(1, template.getPlaceholders().size());
            assertEquals(template, placeholder.getTemplate());
            assertTrue(template.getPlaceholders().contains(placeholder));
        }

        @Test
        @DisplayName("addDefaultSigner() → establishes bidirectional relationship")
        void addDefaultSigner_establishesBidirectionalRelationship() {
            // Given
            DocumentTemplateEntity template = new DocumentTemplateEntity();
            template.onCreate();

            TemplateDefaultSignerEntity signer = new TemplateDefaultSignerEntity();
            signer.setName("John Doe");
            signer.setEmail("john.doe@example.com");
            signer.setSigning(true);

            // When
            template.addDefaultSigner(signer);

            // Then
            assertEquals(1, template.getDefaultSigners().size());
            assertEquals(template, signer.getTemplate());
            assertTrue(template.getDefaultSigners().contains(signer));
        }

        @Test
        @DisplayName("addSigningSchema() → establishes bidirectional relationship")
        void addSigningSchema_establishesBidirectionalRelationship() {
            // Given
            DocumentTemplateEntity template = new DocumentTemplateEntity();
            template.onCreate();

            TemplateSigningSchemaEntity schema = new TemplateSigningSchemaEntity();
            schema.setSchemaType(SigningSchemaType.MITID_SUBSTANTIAL);
            schema.setDisplayOrder(1);

            // When
            template.addSigningSchema(schema);

            // Then
            assertEquals(1, template.getSigningSchemas().size());
            assertEquals(template, schema.getTemplate());
            assertTrue(template.getSigningSchemas().contains(schema));
        }

        @Test
        @DisplayName("addSigningStore() → establishes bidirectional relationship")
        void addSigningStore_establishesBidirectionalRelationship() {
            // Given
            DocumentTemplateEntity template = new DocumentTemplateEntity();
            template.onCreate();

            TemplateSigningStoreEntity store = new TemplateSigningStoreEntity();
            store.setDisplayNameOverride("Custom Store");
            store.setIsActive(true);

            // When
            template.addSigningStore(store);

            // Then
            assertEquals(1, template.getSigningStores().size());
            assertEquals(template, store.getTemplate());
            assertTrue(template.getSigningStores().contains(store));
        }
    }

    // =========================================================================
    // CASCADE BEHAVIOR Tests
    // =========================================================================

    @Nested
    @DisplayName("Cascade Operations")
    class CascadeTests {

        @Test
        @DisplayName("clearPlaceholders() → removes all placeholders")
        void clearPlaceholders_removesAll() {
            // Given
            DocumentTemplateEntity template = new DocumentTemplateEntity();
            template.onCreate();

            TemplatePlaceholderEntity placeholder1 = new TemplatePlaceholderEntity();
            placeholder1.setPlaceholderKey("key1");
            placeholder1.setLabel("Label 1");
            placeholder1.setFieldType(FieldType.TEXT);
            placeholder1.setSource(DataSource.MANUAL);

            TemplatePlaceholderEntity placeholder2 = new TemplatePlaceholderEntity();
            placeholder2.setPlaceholderKey("key2");
            placeholder2.setLabel("Label 2");
            placeholder2.setFieldType(FieldType.TEXT);
            placeholder2.setSource(DataSource.MANUAL);

            template.addPlaceholder(placeholder1);
            template.addPlaceholder(placeholder2);

            // When
            template.clearPlaceholders();

            // Then
            assertTrue(template.getPlaceholders().isEmpty());
        }

        @Test
        @DisplayName("clearDefaultSigners() → removes all default signers")
        void clearDefaultSigners_removesAll() {
            // Given
            DocumentTemplateEntity template = new DocumentTemplateEntity();
            template.onCreate();

            TemplateDefaultSignerEntity signer1 = new TemplateDefaultSignerEntity();
            signer1.setName("Signer 1");
            signer1.setEmail("signer1@example.com");

            TemplateDefaultSignerEntity signer2 = new TemplateDefaultSignerEntity();
            signer2.setName("Signer 2");
            signer2.setEmail("signer2@example.com");

            template.addDefaultSigner(signer1);
            template.addDefaultSigner(signer2);

            // When
            template.clearDefaultSigners();

            // Then
            assertTrue(template.getDefaultSigners().isEmpty());
        }

        @Test
        @DisplayName("clearSigningSchemas() → removes all signing schemas")
        void clearSigningSchemas_removesAll() {
            // Given
            DocumentTemplateEntity template = new DocumentTemplateEntity();
            template.onCreate();

            TemplateSigningSchemaEntity schema1 = new TemplateSigningSchemaEntity();
            schema1.setSchemaType(SigningSchemaType.MITID_LOW);

            TemplateSigningSchemaEntity schema2 = new TemplateSigningSchemaEntity();
            schema2.setSchemaType(SigningSchemaType.MITID_SUBSTANTIAL);

            template.addSigningSchema(schema1);
            template.addSigningSchema(schema2);

            // When
            template.clearSigningSchemas();

            // Then
            assertTrue(template.getSigningSchemas().isEmpty());
        }

        @Test
        @DisplayName("clearSigningStores() → removes all signing stores")
        void clearSigningStores_removesAll() {
            // Given
            DocumentTemplateEntity template = new DocumentTemplateEntity();
            template.onCreate();

            TemplateSigningStoreEntity store1 = new TemplateSigningStoreEntity();
            store1.setDisplayNameOverride("Store 1");

            TemplateSigningStoreEntity store2 = new TemplateSigningStoreEntity();
            store2.setDisplayNameOverride("Store 2");

            template.addSigningStore(store1);
            template.addSigningStore(store2);

            // When
            template.clearSigningStores();

            // Then
            assertTrue(template.getSigningStores().isEmpty());
        }
    }

    // =========================================================================
    // SOFT DELETE Tests
    // =========================================================================

    @Nested
    @DisplayName("Soft Delete")
    class SoftDeleteTests {

        @Test
        @DisplayName("softDelete() → sets active to false")
        void softDelete_setsActiveToFalse() {
            // Given
            DocumentTemplateEntity template = new DocumentTemplateEntity();
            template.setName("Template to Delete");
            template.setCategory(TemplateCategory.EMPLOYMENT);
            template.onCreate();
            template.setActive(true);

            // When
            template.softDelete();

            // Then
            assertFalse(template.isActive());
        }

        @Test
        @DisplayName("activate() → sets active to true")
        void activate_setsActiveToTrue() {
            // Given
            DocumentTemplateEntity template = new DocumentTemplateEntity();
            template.setName("Inactive Template");
            template.setCategory(TemplateCategory.EMPLOYMENT);
            template.onCreate();
            template.setActive(false);

            // When
            template.activate();

            // Then
            assertTrue(template.isActive());
        }
    }
}