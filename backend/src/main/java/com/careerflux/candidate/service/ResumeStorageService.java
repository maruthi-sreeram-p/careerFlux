package com.careerflux.candidate.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.careerflux.common.error.BadRequestException;
import com.careerflux.config.CareerFluxProperties;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stores resume files on disk under generated names.
 *
 * <p>The uploaded filename is never used as a path component: it is recorded in
 * the database for display only. That closes the path-traversal hole and means a
 * leaked directory listing reveals nothing about who the files belong to.
 */
@Service
public class ResumeStorageService {

    private static final Logger log = LoggerFactory.getLogger(ResumeStorageService.class);

    private final Path rootDirectory;

    public ResumeStorageService(CareerFluxProperties properties) {
        this.rootDirectory = Paths.get(properties.storage().resumeDir()).toAbsolutePath().normalize();
    }

    @PostConstruct
    void ensureDirectory() {
        try {
            Files.createDirectories(rootDirectory);
            log.info("Resume storage directory: {}", rootDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the resume storage directory: " + rootDirectory, e);
        }
    }

    /** Writes the bytes under a generated name and returns the relative storage key. */
    public String store(UUID candidateId, byte[] content, String originalFilename) {
        String extension = extensionOf(originalFilename);
        String key = candidateId + "/" + UUID.randomUUID() + extension;
        Path target = rootDirectory.resolve(key).normalize();
        if (!target.startsWith(rootDirectory)) {
            throw new BadRequestException("Invalid storage location.");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return key;
        } catch (IOException e) {
            throw new IllegalStateException("Could not store the uploaded resume", e);
        }
    }

    public byte[] read(String storageKey) {
        Path source = resolve(storageKey);
        try {
            return Files.readAllBytes(source);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the stored resume", e);
        }
    }

    public boolean exists(String storageKey) {
        return Files.exists(resolve(storageKey));
    }

    /**
     * Removes the file. Used both when a candidate replaces a resume and when an
     * account is deleted, where leaving the raw document behind would defeat the
     * deletion entirely.
     */
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            log.warn("Could not delete stored resume {}: {}", storageKey, e.getMessage());
        }
    }

    /** Removes a candidate's whole resume directory. */
    public void deleteAllForCandidate(UUID candidateId) {
        Path directory = rootDirectory.resolve(candidateId.toString()).normalize();
        if (!directory.startsWith(rootDirectory) || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Could not delete {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not clear resume directory for candidate {}: {}", candidateId, e.getMessage());
        }
    }

    // ------------------------------------------------------------ quarantine
    //
    // Deleting a resume touches two stores that cannot share a transaction: the
    // row and the file. Removing the file first and failing on the row would
    // leave a row pointing at nothing; removing the row first and failing on the
    // file would leave a document nobody can see or reach to delete. So the file
    // is first moved out of reach into a quarantine directory, the row is then
    // deleted, and only after that commits is the quarantined copy destroyed.
    // A failed row deletion moves the file back. A quarantined copy that cannot be
    // destroyed is removed later by the retention sweep; one that cannot be moved
    // back is renamed so that no sweep ever removes it, and is reported.

    static final String QUARANTINE = ".quarantine";
    private static final String RESTORE_FAILED_PREFIX = "restore-failed-";

    /** A stored resume file, as the orphan sweep sees it. */
    public record StoredFile(String storageKey, UUID candidateId, Instant lastModified) {
    }

    /** Something waiting in quarantine to be destroyed. */
    public record QuarantinedEntry(String quarantineKey, Instant lastModified) {
    }

    /**
     * Moves one stored file out of reach.
     *
     * @return the quarantine key, or null when there was no file to move
     * @throws IllegalStateException when the file exists and could not be moved;
     *         nothing has changed in that case
     */
    public String quarantine(String storageKey) {
        return quarantinePath(resolve(storageKey));
    }

    /** Moves a candidate's whole resume directory out of reach. Null when there is none. */
    public String quarantineCandidateDirectory(UUID candidateId) {
        return quarantinePath(resolve(candidateId.toString()));
    }

    /** Puts a quarantined file back where it was. */
    public boolean restore(String quarantineKey, String storageKey) {
        return move(resolve(quarantineKey), resolve(storageKey));
    }

    /** Puts a quarantined candidate directory back where it was. */
    public boolean restoreCandidateDirectory(String quarantineKey, UUID candidateId) {
        return move(resolve(quarantineKey), resolve(candidateId.toString()));
    }

    /**
     * Keeps a quarantined entry that could not be restored out of every sweep. The
     * row it belongs to still exists, so destroying this copy would lose the only
     * one; an operator has to look at it.
     */
    public void markRestoreFailed(String quarantineKey) {
        Path source = resolve(quarantineKey);
        Path target = source.resolveSibling(RESTORE_FAILED_PREFIX + source.getFileName());
        if (!move(source, target)) {
            log.error("A quarantined resume entry could not be restored or set aside: {}", quarantineKey);
        }
    }

    /** Destroys a quarantined file or directory. True when nothing is left. */
    public boolean purge(String quarantineKey) {
        Path target = resolve(quarantineKey);
        deleteTree(target);
        return !Files.exists(target);
    }

    /** How many files a quarantined entry holds. */
    public int countFiles(String quarantineKey) {
        Path target = resolve(quarantineKey);
        if (!Files.exists(target)) {
            return 0;
        }
        try (var paths = Files.walk(target)) {
            return (int) paths.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Every file stored under a candidate directory. Anything else under the root
     * is ignored, so a file this service did not write is never swept.
     */
    public java.util.List<StoredFile> listCandidateFiles() {
        java.util.List<StoredFile> files = new java.util.ArrayList<>();
        try (var directories = Files.list(rootDirectory)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                UUID candidateId = uuidOrNull(directory.getFileName().toString());
                if (candidateId == null) {
                    continue;
                }
                try (var entries = Files.list(directory)) {
                    for (Path file : entries.filter(Files::isRegularFile).toList()) {
                        files.add(new StoredFile(candidateId + "/" + file.getFileName(), candidateId,
                                Files.getLastModifiedTime(file).toInstant()));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Could not list stored resumes ({})", e.getClass().getSimpleName());
        }
        return files;
    }

    /** Quarantined entries that are eligible to be destroyed. */
    public java.util.List<QuarantinedEntry> listQuarantined() {
        Path quarantine = rootDirectory.resolve(QUARANTINE);
        java.util.List<QuarantinedEntry> entries = new java.util.ArrayList<>();
        if (!Files.isDirectory(quarantine)) {
            return entries;
        }
        try (var paths = Files.list(quarantine)) {
            for (Path entry : paths.toList()) {
                String name = entry.getFileName().toString();
                if (name.startsWith(RESTORE_FAILED_PREFIX)) {
                    continue;
                }
                entries.add(new QuarantinedEntry(QUARANTINE + "/" + name,
                        Files.getLastModifiedTime(entry).toInstant()));
            }
        } catch (IOException e) {
            log.warn("Could not list quarantined resumes ({})", e.getClass().getSimpleName());
        }
        return entries;
    }

    private String quarantinePath(Path source) {
        if (!Files.exists(source)) {
            return null;
        }
        String key = QUARANTINE + "/" + UUID.randomUUID();
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                Files.move(source, target);
            }
            // The age the sweep measures is time spent in quarantine, not the
            // age of the upload.
            Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.from(Instant.now()));
            return key;
        } catch (IOException e) {
            throw new IllegalStateException("A stored resume could not be moved out of reach, so nothing was deleted.");
        }
    }

    private boolean move(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target);
            return true;
        } catch (IOException e) {
            log.warn("Could not move a quarantined resume entry ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    private void deleteTree(Path target) {
        if (!Files.exists(target)) {
            return;
        }
        try (var paths = Files.walk(target)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Could not destroy a quarantined resume entry ({})", e.getClass().getSimpleName());
                }
            });
        } catch (IOException e) {
            log.warn("Could not walk a quarantined resume entry ({})", e.getClass().getSimpleName());
        }
    }

    private static UUID uuidOrNull(String name) {
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException notACandidateDirectory) {
            return null;
        }
    }

    private Path resolve(String storageKey) {
        Path resolved = rootDirectory.resolve(storageKey).normalize();
        if (!resolved.startsWith(rootDirectory)) {
            throw new BadRequestException("Invalid storage location.");
        }
        return resolved;
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        String extension = filename.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,8}") ? extension : "";
    }
}
