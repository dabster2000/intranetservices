package dk.trustworks.intranet.documentservice.migration.services;

import org.junit.jupiter.api.Test;

import static dk.trustworks.intranet.documentservice.migration.services.SharePointMigrationOwnershipRepairService.folderPathFromParentReference;
import static dk.trustworks.intranet.documentservice.migration.services.SharePointMigrationOwnershipRepairService.personalFolderUnder;
import static dk.trustworks.intranet.documentservice.migration.services.SharePointMigrationOwnershipRepairService.relativePathUnder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Ownership-repair path arithmetic — the part that decides which employee a
 * stranded file now belongs to. Fixtures are real production values: the
 * container the files were stranded on ({@code 2. XXX - Trustworkers}) and the
 * per-person folders they were moved into.
 */
class SharePointMigrationOwnershipRepairLogicTest {

    private static final String BASE = "General/Medarbejdere/Konsulent kontrakter";

    @Test
    void extractsTheDrivePathFromAParentReference() {
        assertEquals("General/Medarbejdere/Konsulent kontrakter/Amalie Obel",
                folderPathFromParentReference(
                        "/drive/root:/General/Medarbejdere/Konsulent kontrakter/Amalie Obel"));
    }

    @Test
    void handlesTheDrivesPrefixFormToo() {
        assertEquals("General/Medarbejdere/Konsulent kontrakter/Simon Gomez",
                folderPathFromParentReference(
                        "/drives/b!xY-z123/root:/General/Medarbejdere/Konsulent kontrakter/Simon Gomez"));
    }

    @Test
    void decodesPercentEncodingButKeepsALiteralPlus() {
        // Danish folder names arrive percent-encoded; a '+' in a folder name is
        // a plus, not a space — URLDecoder alone would corrupt it.
        assertEquals(BASE + "/Sofie Damkjær Østensgård",
                folderPathFromParentReference(
                        "/drive/root:/General/Medarbejdere/Konsulent%20kontrakter/"
                                + "Sofie%20Damkj%C3%A6r%20%C3%98stensg%C3%A5rd"));
        assertEquals(BASE + "/C+ konsulenter",
                folderPathFromParentReference(
                        "/drive/root:/General/Medarbejdere/Konsulent%20kontrakter/C+ konsulenter"));
    }

    @Test
    void refusesAPathWithNoRootMarker() {
        assertNull(folderPathFromParentReference(null));
        assertNull(folderPathFromParentReference(""));
        assertNull(folderPathFromParentReference("/sites/TW/Shared Documents/whatever"));
    }

    @Test
    void findsThePersonalFolderDirectlyUnderTheBasePath() {
        assertEquals("Emilie Duedahl", personalFolderUnder(BASE, BASE + "/Emilie Duedahl"));
    }

    @Test
    void findsThePersonalFolderFromDeeperInsideIt() {
        // A file in Emilie's Arkiv subfolder still belongs to Emilie.
        assertEquals("Emilie Duedahl",
                personalFolderUnder(BASE, BASE + "/Emilie Duedahl/Arkiv/2019"));
    }

    @Test
    void refusesAFileSittingDirectlyInTheBasePath() {
        // The crawler calls these "root files (not a personal folder)" and
        // skips them; the repair must not invent an owner either.
        assertNull(personalFolderUnder(BASE, BASE));
    }

    @Test
    void refusesAFileOutsideTheBasePath() {
        assertNull(personalFolderUnder(BASE, "General/Medarbejdere/Andet/Simon Gomez"));
        // A prefix that only *looks* like the base path must not match.
        assertNull(personalFolderUnder(BASE, "General/Medarbejdere/Konsulent kontrakter arkiv/X"));
    }

    @Test
    void computesTheRelativePathUnderThePersonalFolder() {
        String personal = BASE + "/Emilie Duedahl";
        assertEquals("", relativePathUnder(personal, personal));
        assertEquals("Arkiv", relativePathUnder(personal, personal + "/Arkiv"));
        assertEquals("Arkiv/2019", relativePathUnder(personal, personal + "/Arkiv/2019"));
    }

    @Test
    void resolvesTheRealStrandedCase() {
        // Emilie's contract as it stands in production: the item row points at
        // the SKIPPED container, the file itself is in her own folder.
        String parent = folderPathFromParentReference(
                "/drive/root:/General/Medarbejdere/Konsulent%20kontrakter/Emilie%20Duedahl");
        String owner = personalFolderUnder(BASE, parent);
        assertEquals("Emilie Duedahl", owner);
        assertEquals("", relativePathUnder(BASE + "/" + owner, parent));
    }
}
