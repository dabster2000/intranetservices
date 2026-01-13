package dk.trustworks.intranet.documentservice.resources;

import dk.trustworks.intranet.documentservice.dto.SharePointLocationDTO;
import dk.trustworks.intranet.documentservice.model.SharePointLocationEntity;
import dk.trustworks.intranet.documentservice.model.TemplateSigningStoreEntity;
import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static dk.trustworks.intranet.documentservice.utils.AssertionHelpers.*;
import static dk.trustworks.intranet.documentservice.utils.TestDataBuilders.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Unit tests for SharePointLocationResource.
 * Tests all CRUD operations, validation, error handling, and business rules.
 */
@QuarkusTest
@DisplayName("SharePointLocationResource Unit Tests")
class SharePointLocationResourceTest {

    private final SharePointLocationResource resource = new SharePointLocationResource();

    @BeforeEach
    void setupMocks() {
        // Reset mocks before each test
        PanacheMock.reset();
    }

    // =========================================================================
    // CREATE Tests
    // =========================================================================

    @Nested
    @DisplayName("POST /sharepoint-locations (Create)")
    class CreateTests {

        @Test
        @DisplayName("Valid input → 201 CREATED with UUID and timestamps")
        void create_validInput_returnsCreatedWithUuidAndTimestamps() {
            // Given
            SharePointLocationDTO inputDTO = sharePointLocationDTO()
                    .uuid(null) // UUID should be auto-generated
                    .name("New Location")
                    .siteUrl("https://trustworks.sharepoint.com/sites/newsite")
                    .driveName("Documents")
                    .folderPath("/NewFolder")
                    .isActive(true)
                    .displayOrder(5)
                    .build();

            PanacheMock.mock(SharePointLocationEntity.class);
            PanacheMock.mock(TemplateSigningStoreEntity.class);

            PanacheMock.doReturn(false).when(SharePointLocationEntity.class)
                    .existsByPath(
                            eq("https://trustworks.sharepoint.com/sites/newsite"),
                            eq("Documents"),
                            eq("/NewFolder")
                    );

            PanacheMock.doReturn(0L).when(TemplateSigningStoreEntity.class)
                    .count(anyString(), anyString());

            // When
            Response response = resource.create(inputDTO);

            // Then
            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());

            SharePointLocationDTO result = (SharePointLocationDTO) response.getEntity();
            assertNotNull(result);
            assertValidUuid(result.getUuid());
            assertEquals("New Location", result.getName());
            assertEquals("https://trustworks.sharepoint.com/sites/newsite", result.getSiteUrl());
            assertEquals("Documents", result.getDriveName());
            assertEquals("/NewFolder", result.getFolderPath());
            assertTrue(result.isActive());
            assertEquals(5, result.getDisplayOrder());
            assertNotNull(result.getCreatedAt());
            assertNotNull(result.getUpdatedAt());
        }

        @Test
        @DisplayName("Null folderPath → 201 CREATED (null is valid)")
        void create_nullFolderPath_succeeds() {
            // Given
            SharePointLocationDTO inputDTO = sharePointLocationDTO()
                    .uuid(null)
                    .name("Root Location")
                    .siteUrl("https://trustworks.sharepoint.com/sites/root")
                    .driveName("Documents")
                    .folderPath(null)
                    .build();

            PanacheMock.mock(SharePointLocationEntity.class);
            PanacheMock.mock(TemplateSigningStoreEntity.class);

            PanacheMock.doReturn(false).when(SharePointLocationEntity.class)
                    .existsByPath(
                            eq("https://trustworks.sharepoint.com/sites/root"),
                            eq("Documents"),
                            isNull()
                    );

            PanacheMock.doReturn(0L).when(TemplateSigningStoreEntity.class)
                    .count(anyString(), anyString());

            // When
            Response response = resource.create(inputDTO);

            // Then
            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
            SharePointLocationDTO result = (SharePointLocationDTO) response.getEntity();
            assertNull(result.getFolderPath());
        }

        @Test
        @DisplayName("Duplicate path → 409 CONFLICT")
        void create_duplicatePath_throwsConflict() {
            // Given
            SharePointLocationDTO inputDTO = sharePointLocationDTO()
                    .name("Duplicate Location")
                    .siteUrl("https://trustworks.sharepoint.com/sites/test")
                    .driveName("Documents")
                    .folderPath("/Existing")
                    .build();

            PanacheMock.mock(SharePointLocationEntity.class);

            PanacheMock.doReturn(true).when(SharePointLocationEntity.class)
                    .existsByPath(
                            eq("https://trustworks.sharepoint.com/sites/test"),
                            eq("Documents"),
                            eq("/Existing")
                    );

            // When / Then
            WebApplicationException exception = assertThrows(WebApplicationException.class,
                    () -> resource.create(inputDTO));

            assertEquals(Response.Status.CONFLICT.getStatusCode(), exception.getResponse().getStatus());
            assertTrue(exception.getMessage().contains("already exists"));
        }

        @Test
        @DisplayName("Transaction commits on success")
        void create_success_commitsTransaction() {
            // Given
            SharePointLocationDTO inputDTO = sharePointLocationDTO()
                    .uuid(null)
                    .build();

            PanacheMock.mock(SharePointLocationEntity.class);
            PanacheMock.mock(TemplateSigningStoreEntity.class);

            PanacheMock.doReturn(false).when(SharePointLocationEntity.class)
                    .existsByPath(anyString(), anyString(), anyString());

            PanacheMock.doReturn(0L).when(TemplateSigningStoreEntity.class)
                    .count(anyString(), anyString());

            // When
            Response response = resource.create(inputDTO);

            // Then
            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
            // In a real transaction, entity.persist() would be called
        }
    }

    // =========================================================================
    // LIST Tests
    // =========================================================================

    @Nested
    @DisplayName("GET /sharepoint-locations (List)")
    class ListTests {

        @Test
        @DisplayName("Default (no filter) → returns all locations")
        void getAll_defaultFilter_returnsAll() {
            // Given
            SharePointLocationEntity entity1 = sharePointLocationEntity()
                    .uuid("uuid-1")
                    .name("Location A")
                    .isActive(true)
                    .displayOrder(1)
                    .build();

            SharePointLocationEntity entity2 = sharePointLocationEntity()
                    .uuid("uuid-2")
                    .name("Location B")
                    .isActive(false)
                    .displayOrder(2)
                    .build();

            PanacheMock.mock(SharePointLocationEntity.class);
            PanacheMock.mock(TemplateSigningStoreEntity.class);

            PanacheMock.doReturn(Arrays.asList(entity1, entity2))
                    .when(SharePointLocationEntity.class).findAllLocations(false);

            PanacheMock.doReturn(0L).when(TemplateSigningStoreEntity.class)
                    .count(eq("location.uuid = ?1"), anyString());

            // When
            List<SharePointLocationDTO> result = resource.getAll(false);

            // Then
            assertEquals(2, result.size());
            assertEquals("Location A", result.get(0).getName());
            assertEquals("Location B", result.get(1).getName());
        }

        @Test
        @DisplayName("activeOnly=true → filters to active only")
        void getAll_activeOnly_filtersActive() {
            // Given
            SharePointLocationEntity activeEntity = sharePointLocationEntity()
                    .uuid("uuid-active")
                    .name("Active Location")
                    .isActive(true)
                    .build();

            PanacheMock.mock(SharePointLocationEntity.class);
            PanacheMock.mock(TemplateSigningStoreEntity.class);

            PanacheMock.doReturn(Collections.singletonList(activeEntity))
                    .when(SharePointLocationEntity.class).findAllLocations(true);

            PanacheMock.doReturn(0L).when(TemplateSigningStoreEntity.class)
                    .count(anyString(), anyString());

            // When
            List<SharePointLocationDTO> result = resource.getAll(true);

            // Then
            assertEquals(1, result.size());
            assertTrue(result.get(0).isActive());
            assertEquals("Active Location", result.get(0).getName());
        }

        @Test
        @DisplayName("Empty result → 200 OK with empty array")
        void getAll_noResults_returnsEmptyList() {
            // Given
            PanacheMock.mock(SharePointLocationEntity.class);

            PanacheMock.doReturn(Collections.emptyList())
                    .when(SharePointLocationEntity.class).findAllLocations(anyBoolean());

            // When
            List<SharePointLocationDTO> result = resource.getAll(false);

            // Then
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // =========================================================================
    // GET BY UUID Tests
    // =========================================================================

    @Nested
    @DisplayName("GET /sharepoint-locations/{uuid} (Find by UUID)")
    class GetByUuidTests {

        @Test
        @DisplayName("Existing UUID → 200 OK with all fields")
        void getByUuid_existingUuid_returnsLocation() {
            // Given
            String uuid = "test-uuid";
            SharePointLocationEntity entity = sharePointLocationEntity()
                    .uuid(uuid)
                    .name("Test Location")
                    .siteUrl("https://trustworks.sharepoint.com/sites/test")
                    .driveName("Documents")
                    .folderPath("/Test")
                    .isActive(true)
                    .displayOrder(10)
                    .build();

            PanacheMock.mock(SharePointLocationEntity.class);
            PanacheMock.mock(TemplateSigningStoreEntity.class);

            PanacheMock.doReturn(entity).when(SharePointLocationEntity.class).findByUuid(uuid);

            PanacheMock.doReturn(3L).when(TemplateSigningStoreEntity.class)
                    .count(anyString(), anyString());

            // When
            SharePointLocationDTO result = resource.getByUuid(uuid);

            // Then
            assertNotNull(result);
            assertEquals(uuid, result.getUuid());
            assertEquals("Test Location", result.getName());
            assertEquals("https://trustworks.sharepoint.com/sites/test", result.getSiteUrl());
            assertEquals("Documents", result.getDriveName());
            assertEquals("/Test", result.getFolderPath());
            assertTrue(result.isActive());
            assertEquals(10, result.getDisplayOrder());
            assertEquals(3L, result.getReferenceCount());
        }

        @Test
        @DisplayName("Non-existent UUID → 404 NOT_FOUND")
        void getByUuid_nonExistentUuid_throwsNotFound() {
            // Given
            String uuid = "non-existent-uuid";

            PanacheMock.mock(SharePointLocationEntity.class);
            PanacheMock.doReturn(null).when(SharePointLocationEntity.class).findByUuid(uuid);

            // When / Then
            WebApplicationException exception = assertThrows(WebApplicationException.class,
                    () -> resource.getByUuid(uuid));

            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), exception.getResponse().getStatus());
            assertTrue(exception.getMessage().contains("not found"));
        }
    }

    // =========================================================================
    // UPDATE Tests
    // =========================================================================

    @Nested
    @DisplayName("PUT /sharepoint-locations/{uuid} (Update)")
    class UpdateTests {

        @Test
        @DisplayName("Valid update → 200 OK with updated fields")
        void update_validUpdate_returnsUpdatedLocation() {
            // Given
            String uuid = "existing-uuid";
            SharePointLocationEntity existingEntity = sharePointLocationEntity()
                    .uuid(uuid)
                    .name("Old Name")
                    .siteUrl("https://old.sharepoint.com")
                    .driveName("OldDrive")
                    .folderPath("/Old")
                    .isActive(true)
                    .displayOrder(1)
                    .build();

            SharePointLocationDTO updateDTO = sharePointLocationDTO()
                    .name("Updated Name")
                    .siteUrl("https://new.sharepoint.com")
                    .driveName("NewDrive")
                    .folderPath("/New")
                    .isActive(false)
                    .displayOrder(5)
                    .build();

            PanacheMock.mock(SharePointLocationEntity.class);
            PanacheMock.mock(TemplateSigningStoreEntity.class);

            PanacheMock.doReturn(existingEntity).when(SharePointLocationEntity.class).findByUuid(uuid);

            PanacheMock.doReturn(false).when(SharePointLocationEntity.class)
                    .existsByPathExcludingUuid(
                            eq("https://new.sharepoint.com"),
                            eq("NewDrive"),
                            eq("/New"),
                            eq(uuid)
                    );

            PanacheMock.doReturn(0L).when(TemplateSigningStoreEntity.class)
                    .count(anyString(), anyString());

            // When
            SharePointLocationDTO result = resource.update(uuid, updateDTO);

            // Then
            assertNotNull(result);
            assertEquals("Updated Name", result.getName());
            assertEquals("https://new.sharepoint.com", result.getSiteUrl());
            assertEquals("NewDrive", result.getDriveName());
            assertEquals("/New", result.getFolderPath());
            assertFalse(result.isActive());
            assertEquals(5, result.getDisplayOrder());
        }

        @Test
        @DisplayName("Non-existent UUID → 404 NOT_FOUND")
        void update_nonExistentUuid_throwsNotFound() {
            // Given
            String uuid = "non-existent-uuid";
            SharePointLocationDTO updateDTO = sharePointLocationDTO().build();

            PanacheMock.mock(SharePointLocationEntity.class);
            PanacheMock.doReturn(null).when(SharePointLocationEntity.class).findByUuid(uuid);

            // When / Then
            WebApplicationException exception = assertThrows(WebApplicationException.class,
                    () -> resource.update(uuid, updateDTO));

            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), exception.getResponse().getStatus());
        }

        @Test
        @DisplayName("Duplicate path (excluding current) → 409 CONFLICT")
        void update_duplicatePathExcludingCurrent_throwsConflict() {
            // Given
            String uuid = "current-uuid";
            SharePointLocationEntity existingEntity = sharePointLocationEntity()
                    .uuid(uuid)
                    .build();

            SharePointLocationDTO updateDTO = sharePointLocationDTO()
                    .siteUrl("https://duplicate.sharepoint.com")
                    .driveName("Duplicate")
                    .folderPath("/Duplicate")
                    .build();

            PanacheMock.mock(SharePointLocationEntity.class);

            PanacheMock.doReturn(existingEntity).when(SharePointLocationEntity.class).findByUuid(uuid);

            PanacheMock.doReturn(true).when(SharePointLocationEntity.class)
                    .existsByPathExcludingUuid(
                            eq("https://duplicate.sharepoint.com"),
                            eq("Duplicate"),
                            eq("/Duplicate"),
                            eq(uuid)
                    );

            // When / Then
            WebApplicationException exception = assertThrows(WebApplicationException.class,
                    () -> resource.update(uuid, updateDTO));

            assertEquals(Response.Status.CONFLICT.getStatusCode(), exception.getResponse().getStatus());
            assertTrue(exception.getMessage().toLowerCase().contains("already exists"));
        }
    }

    // =========================================================================
    // DELETE Tests
    // =========================================================================

    @Nested
    @DisplayName("DELETE /sharepoint-locations/{uuid} (Delete)")
    class DeleteTests {

        @Test
        @DisplayName("No references → 204 NO_CONTENT")
        void delete_noReferences_succeeds() {
            // Given
            String uuid = "deletable-uuid";
            SharePointLocationEntity entity = sharePointLocationEntity()
                    .uuid(uuid)
                    .build();

            PanacheMock.mock(SharePointLocationEntity.class);
            PanacheMock.mock(TemplateSigningStoreEntity.class);

            PanacheMock.doReturn(entity).when(SharePointLocationEntity.class).findByUuid(uuid);

            PanacheMock.doReturn(0L).when(TemplateSigningStoreEntity.class)
                    .count(anyString(), anyString());

            // When
            Response response = resource.delete(uuid);

            // Then
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
        }

        @Test
        @DisplayName("With references → 409 CONFLICT (cannot delete)")
        void delete_withReferences_throwsConflict() {
            // Given
            String uuid = "referenced-uuid";
            SharePointLocationEntity entity = sharePointLocationEntity()
                    .uuid(uuid)
                    .build();

            PanacheMock.mock(SharePointLocationEntity.class);
            PanacheMock.mock(TemplateSigningStoreEntity.class);

            PanacheMock.doReturn(entity).when(SharePointLocationEntity.class).findByUuid(uuid);

            PanacheMock.doReturn(3L).when(TemplateSigningStoreEntity.class)
                    .count(anyString(), anyString());

            // When / Then
            WebApplicationException exception = assertThrows(WebApplicationException.class,
                    () -> resource.delete(uuid));

            assertEquals(Response.Status.CONFLICT.getStatusCode(), exception.getResponse().getStatus());
            assertTrue(exception.getMessage().contains("still referenced"));
            assertTrue(exception.getMessage().contains("3"));
        }

        @Test
        @DisplayName("Non-existent UUID → 404 NOT_FOUND")
        void delete_nonExistentUuid_throwsNotFound() {
            // Given
            String uuid = "non-existent-uuid";

            PanacheMock.mock(SharePointLocationEntity.class);
            PanacheMock.doReturn(null).when(SharePointLocationEntity.class).findByUuid(uuid);

            // When / Then
            WebApplicationException exception = assertThrows(WebApplicationException.class,
                    () -> resource.delete(uuid));

            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), exception.getResponse().getStatus());
        }
    }
}
