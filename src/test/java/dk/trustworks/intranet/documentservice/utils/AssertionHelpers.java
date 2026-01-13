package dk.trustworks.intranet.documentservice.utils;

import dk.trustworks.intranet.documentservice.dto.SharePointLocationDTO;
import dk.trustworks.intranet.documentservice.model.SharePointLocationEntity;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Helper methods for common assertions in tests.
 * Provides reusable assertion patterns to keep tests clean and readable.
 */
public class AssertionHelpers {

    /**
     * Assert that a string is a valid UUID.
     *
     * @param value The string to validate
     */
    public static void assertValidUuid(String value) {
        assertNotNull(value, "UUID should not be null");
        assertDoesNotThrow(() -> UUID.fromString(value), "UUID should be valid: " + value);
    }

    /**
     * Assert that a timestamp is recent (within the last few seconds).
     * Useful for verifying auto-generated timestamps.
     *
     * @param timestamp The timestamp to validate
     * @param maxSecondsAgo Maximum age in seconds
     */
    public static void assertRecentTimestamp(LocalDateTime timestamp, long maxSecondsAgo) {
        assertNotNull(timestamp, "Timestamp should not be null");
        LocalDateTime now = LocalDateTime.now();
        long secondsAgo = ChronoUnit.SECONDS.between(timestamp, now);
        assertTrue(secondsAgo >= 0 && secondsAgo <= maxSecondsAgo,
                String.format("Timestamp should be recent (within %d seconds), but was %d seconds ago",
                        maxSecondsAgo, secondsAgo));
    }

    /**
     * Assert that a timestamp is recent (within the last 5 seconds).
     *
     * @param timestamp The timestamp to validate
     */
    public static void assertRecentTimestamp(LocalDateTime timestamp) {
        assertRecentTimestamp(timestamp, 5);
    }

    /**
     * Assert that two timestamps are approximately equal (within 1 second).
     * Useful for comparing timestamps that might have slight differences due to processing time.
     *
     * @param expected Expected timestamp
     * @param actual Actual timestamp
     */
    public static void assertTimestampsEqual(LocalDateTime expected, LocalDateTime actual) {
        assertNotNull(expected, "Expected timestamp should not be null");
        assertNotNull(actual, "Actual timestamp should not be null");
        long difference = Math.abs(ChronoUnit.SECONDS.between(expected, actual));
        assertTrue(difference <= 1,
                String.format("Timestamps should be within 1 second of each other (difference: %d seconds)",
                        difference));
    }

    /**
     * Assert that a DTO matches an entity's data.
     *
     * @param dto The DTO to check
     * @param entity The entity to compare against
     */
    public static void assertSharePointLocationMatches(SharePointLocationDTO dto, SharePointLocationEntity entity) {
        assertNotNull(dto, "DTO should not be null");
        assertNotNull(entity, "Entity should not be null");

        assertEquals(entity.getUuid(), dto.getUuid(), "UUID should match");
        assertEquals(entity.getName(), dto.getName(), "Name should match");
        assertEquals(entity.getSiteUrl(), dto.getSiteUrl(), "Site URL should match");
        assertEquals(entity.getDriveName(), dto.getDriveName(), "Drive name should match");
        assertEquals(entity.getFolderPath(), dto.getFolderPath(), "Folder path should match");
        assertEquals(entity.getIsActive(), dto.isActive(), "Active status should match");
        assertEquals(entity.getDisplayOrder(), dto.getDisplayOrder(), "Display order should match");
        assertTimestampsEqual(entity.getCreatedAt(), dto.getCreatedAt());
        assertTimestampsEqual(entity.getUpdatedAt(), dto.getUpdatedAt());
    }

    /**
     * Assert that an entity matches a DTO's input data.
     *
     * @param entity The entity to check
     * @param dto The DTO with input data
     */
    public static void assertSharePointLocationMatchesInput(SharePointLocationEntity entity, SharePointLocationDTO dto) {
        assertNotNull(entity, "Entity should not be null");
        assertNotNull(dto, "DTO should not be null");

        assertEquals(dto.getName(), entity.getName(), "Name should match input");
        assertEquals(dto.getSiteUrl(), entity.getSiteUrl(), "Site URL should match input");
        assertEquals(dto.getDriveName(), entity.getDriveName(), "Drive name should match input");
        assertEquals(dto.getFolderPath(), entity.getFolderPath(), "Folder path should match input");
        assertEquals(dto.isActive(), entity.getIsActive(), "Active status should match input");
        assertEquals(dto.getDisplayOrder(), entity.getDisplayOrder(), "Display order should match input");
    }

    /**
     * Assert that a Word document has a valid ZIP signature.
     *
     * @param bytes The file bytes to check
     */
    public static void assertValidWordDocumentSignature(byte[] bytes) {
        assertNotNull(bytes, "File bytes should not be null");
        assertTrue(bytes.length >= 4, "File should have at least 4 bytes for ZIP signature");
        assertEquals(0x50, bytes[0], "First byte should be 0x50 (ZIP signature)");
        assertEquals(0x4B, bytes[1], "Second byte should be 0x4B (ZIP signature)");
        assertEquals(0x03, bytes[2], "Third byte should be 0x03 (ZIP signature)");
        assertEquals(0x04, bytes[3], "Fourth byte should be 0x04 (ZIP signature)");
    }

    /**
     * Assert that two strings match, ignoring null vs empty string differences.
     *
     * @param expected Expected value (can be null or empty)
     * @param actual Actual value (can be null or empty)
     * @param message Assertion message
     */
    public static void assertStringMatchesIgnoringEmpty(String expected, String actual, String message) {
        String normalizedExpected = (expected == null || expected.isEmpty()) ? null : expected;
        String normalizedActual = (actual == null || actual.isEmpty()) ? null : actual;
        assertEquals(normalizedExpected, normalizedActual, message);
    }
}
