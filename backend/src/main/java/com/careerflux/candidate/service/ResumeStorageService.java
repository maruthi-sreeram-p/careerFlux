package com.careerflux.candidate.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
