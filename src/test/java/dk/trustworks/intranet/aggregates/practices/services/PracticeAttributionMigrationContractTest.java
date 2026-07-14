package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PracticeAttributionMigrationContractTest {

    @Test
    void migrationCapturesAllUserWritersAndPreservesHistoryOnDelete() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V407__Create_user_practice_history.sql"));

        assertTrue(sql.contains("AFTER INSERT ON `user`"));
        assertTrue(sql.contains("AFTER UPDATE ON `user`"));
        assertTrue(sql.contains("AFTER DELETE ON `user`"));
        assertTrue(sql.contains("IF NOT (OLD.practice <=> NEW.practice)"));
        assertTrue(sql.contains("Earlier dates") || sql.contains("deployment day"));
        assertFalse(sql.contains("FOREIGN KEY (useruuid)"));
    }
}
