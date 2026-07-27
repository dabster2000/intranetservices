package dk.trustworks.intranet.documentservice.migration.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Crawler pure logic (runbook 2a-2): pagination token parsing + exclusion rules. */
class SharePointMigrationCrawlerLogicTest {

    @Test
    void parsesSkipTokenFromNextLink() {
        assertEquals("s!AbC123",
                SharePointMigrationCrawlerService.parseSkipToken(
                        "https://graph.microsoft.com/v1.0/drives/d/items/i/children?$top=200&$skiptoken=s%21AbC123"));
        assertEquals("plain-token",
                SharePointMigrationCrawlerService.parseSkipToken(
                        "https://graph.microsoft.com/v1.0/x?$skiptoken=plain-token"));
    }

    @Test
    void missingOrGarbageNextLinkStopsPagination() {
        assertNull(SharePointMigrationCrawlerService.parseSkipToken(null));
        assertNull(SharePointMigrationCrawlerService.parseSkipToken(""));
        assertNull(SharePointMigrationCrawlerService.parseSkipToken(
                "https://graph.microsoft.com/v1.0/x?$top=200"));
    }

    @Test
    void systemFoldersAreExcluded() {
        assertTrue(SharePointMigrationCrawlerService.isExcludedFolder("Forms"));
        assertTrue(SharePointMigrationCrawlerService.isExcludedFolder("forms"));
        assertTrue(SharePointMigrationCrawlerService.isExcludedFolder(".hidden"));
        assertTrue(SharePointMigrationCrawlerService.isExcludedFolder("_system"));
        assertTrue(SharePointMigrationCrawlerService.isExcludedFolder(""));
        assertTrue(SharePointMigrationCrawlerService.isExcludedFolder(null));
        assertFalse(SharePointMigrationCrawlerService.isExcludedFolder("Birger Püschl"));
        assertFalse(SharePointMigrationCrawlerService.isExcludedFolder("christian.larsen"));
    }
}
