package com.careerflux;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Database dumps must not be committable by accident.
 *
 * <p>Nine were committed and pushed: account emails, password hashes and
 * resumes, copied out of the database the moment it was dumped. The ignore rule
 * stops the next one; it cannot remove what is already in history.
 */
class RepositoryHygieneTest {

    @Test
    @DisplayName("database dumps under backups/ are ignored by git")
    void backupsAreIgnored() throws Exception {
        // The build runs from backend/, so the repository root is one level up.
        List<String> rules = Files.readAllLines(Path.of("../.gitignore")).stream()
                .map(String::strip)
                .toList();
        assertThat(rules).containsAnyOf("/backups/", "backups/");
    }
}
