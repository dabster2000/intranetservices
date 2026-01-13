package dk.trustworks.intranet.documentservice.model;

import dk.trustworks.intranet.documentservice.dto.SharePointLocationDTO;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;

import static dk.trustworks.intranet.documentservice.utils.AssertionHelpers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SharePointLocationEntity.
 * Tests lifecycle hooks, null handling, and entity behavior.
 */
@QuarkusTest
@DisplayName("SharePointLocationEntity Unit Tests")
class SharePointLocationEntityTest {

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
            SharePointLocationEntity entity = new SharePointLocationEntity();
            entity.setName("Test Location");
            entity.setSiteUrl("https://test.sharepoint.com");
            entity.setDriveName("Documents");

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
            SharePointLocationEntity entity = new SharePointLocationEntity();
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
            String existingUuid = "existing-uuid-123";
            SharePointLocationEntity entity = new SharePointLocationEntity();
            entity.setUuid(existingUuid);
            entity.setName("Test Location");
            entity.setSiteUrl("https://test.sharepoint.com");
            entity.setDriveName("Documents");

            // When
            entity.onCreate();

            // Then
            assertEquals(existingUuid, entity.getUuid());
        }
    }

    // =========================================================================
    // NULL HANDLING Tests
    // =========================================================================

    @Nested
    @DisplayName("Null Handling")
    class NullHandlingTests {

        @Test
        @DisplayName("Null folderPath → allowed by schema")
        void nullFolderPath_allowedBySchema() {
            // Given
            SharePointLocationEntity entity = new SharePointLocationEntity();
            entity.setName("Root Location");
            entity.setSiteUrl("https://test.sharepoint.com");
            entity.setDriveName("Documents");
            entity.setFolderPath(null);

            entity.onCreate();

            // Then
            assertNull(entity.getFolderPath());
        }

        @Test
        @DisplayName("Default values → isActive=true, displayOrder=1")
        void defaultValues_appliedCorrectly() {
            // Given
            SharePointLocationEntity entity = new SharePointLocationEntity();

            // Then
            assertTrue(entity.getIsActive());
            assertEquals(1, entity.getDisplayOrder());
        }
    }

    // =========================================================================
    // DTO MAPPING Tests
    // =========================================================================

    @Nested
    @DisplayName("DTO Mapping")
    class DtoMappingTests {

        @Test
        @DisplayName("Entity → DTO → preserves all fields")
        void entityToDto_preservesAllFields() {
            // Given
            SharePointLocationEntity entity = new SharePointLocationEntity();
            entity.setUuid("test-uuid");
            entity.setName("Test Location");
            entity.setSiteUrl("https://test.sharepoint.com/sites/test");
            entity.setDriveName("Documents");
            entity.setFolderPath("/Contracts");
            entity.setIsActive(true);
            entity.setDisplayOrder(5);
            entity.onCreate();

            // When (simulating manual DTO mapping as done in SharePointLocationResource)
            SharePointLocationDTO dto = SharePointLocationDTO.builder()
                    .uuid(entity.getUuid())
                    .name(entity.getName())
                    .siteUrl(entity.getSiteUrl())
                    .driveName(entity.getDriveName())
                    .folderPath(entity.getFolderPath())
                    .isActive(entity.getIsActive())
                    .displayOrder(entity.getDisplayOrder())
                    .createdAt(entity.getCreatedAt())
                    .updatedAt(entity.getUpdatedAt())
                    .build();

            // Then
            assertEquals(entity.getUuid(), dto.getUuid());
            assertEquals(entity.getName(), dto.getName());
            assertEquals(entity.getSiteUrl(), dto.getSiteUrl());
            assertEquals(entity.getDriveName(), dto.getDriveName());
            assertEquals(entity.getFolderPath(), dto.getFolderPath());
            assertEquals(entity.getIsActive(), dto.isActive());
            assertEquals(entity.getDisplayOrder(), dto.getDisplayOrder());
            assertEquals(entity.getCreatedAt(), dto.getCreatedAt());
            assertEquals(entity.getUpdatedAt(), dto.getUpdatedAt());
        }

        @Test
        @DisplayName("DTO → Entity → preserves input data")
        void dtoToEntity_preservesInputData() {
            // Given
            SharePointLocationDTO dto = SharePointLocationDTO.builder()
                    .name("New Location")
                    .siteUrl("https://new.sharepoint.com")
                    .driveName("NewDrive")
                    .folderPath("/NewPath")
                    .isActive(false)
                    .displayOrder(10)
                    .build();

            // When (simulating manual entity creation from DTO as done in SharePointLocationResource)
            SharePointLocationEntity entity = new SharePointLocationEntity();
            entity.setName(dto.getName());
            entity.setSiteUrl(dto.getSiteUrl());
            entity.setDriveName(dto.getDriveName());
            entity.setFolderPath(dto.getFolderPath());
            entity.setIsActive(dto.isActive());
            entity.setDisplayOrder(dto.getDisplayOrder());
            entity.onCreate();

            // Then
            assertEquals(dto.getName(), entity.getName());
            assertEquals(dto.getSiteUrl(), entity.getSiteUrl());
            assertEquals(dto.getDriveName(), entity.getDriveName());
            assertEquals(dto.getFolderPath(), entity.getFolderPath());
            assertEquals(dto.isActive(), entity.getIsActive());
            assertEquals(dto.getDisplayOrder(), entity.getDisplayOrder());
            assertValidUuid(entity.getUuid());
        }
    }
}
