package org.example.rentoza.car.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Local file system storage for documents.
 * 
 * <p>Active only in "dev" and "local" profiles.
 * 
 * <p><b>WARNING:</b> In Docker deployments, files will be lost on redeploy
 * unless a volume is mounted. Use S3DocumentStorageStrategy for production.
 */
@Service
@Profile({"dev", "local", "default"})
public class LocalDocumentStorageStrategy implements DocumentStorageStrategy {
    
    private static final Logger log = LoggerFactory.getLogger(LocalDocumentStorageStrategy.class);
    
    @Value("${document.storage.local.base-path:user-uploads}")
    private String basePath;
    
    private Path rootLocation;

    private String normalizeStorageKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Storage key cannot be blank");
        }
        String normalized = key.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private Path resolveToAbsolutePath(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            throw new IllegalArgumentException("Path cannot be blank");
        }

        String value = pathOrUrl.trim();
        Path rawPath;
        if (value.startsWith("file:")) {
            rawPath = Paths.get(URI.create(value));
        } else {
            rawPath = Paths.get(value);
        }

        Path resolved = rawPath.isAbsolute()
            ? rawPath.toAbsolutePath().normalize()
            : rootLocation.resolve(normalizeStorageKey(value)).normalize();

        if (!resolved.startsWith(rootLocation)) {
            throw new SecurityException("Cannot access file outside of upload directory");
        }

        return resolved;
    }
    
    @PostConstruct
    public void init() {
        this.rootLocation = Paths.get(basePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootLocation);
            log.info("Local document storage initialized at: {}", rootLocation);
        } catch (IOException e) {
            throw new RuntimeException("Could not create upload directory: " + rootLocation, e);
        }
    }
    
    @Override
    public String uploadFile(MultipartFile file, String path) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Cannot store empty file");
        }

        String storageKey = normalizeStorageKey(path);
        
        // Security: Prevent path traversal
        Path destinationPath = rootLocation.resolve(storageKey).normalize();
        if (!destinationPath.startsWith(rootLocation)) {
            throw new SecurityException("Cannot store file outside of upload directory");
        }
        
        // Create parent directories if needed
        Files.createDirectories(destinationPath.getParent());
        
        // Copy file
        Files.copy(file.getInputStream(), destinationPath, StandardCopyOption.REPLACE_EXISTING);
        
        log.info("Stored file: {} ({} bytes)", destinationPath, file.getSize());
        
        // IMPORTANT: never return absolute filesystem paths to callers.
        return storageKey;
    }
    
    @Override
    public byte[] getFile(String path) throws IOException {
        Path filePath = resolveToAbsolutePath(path);
        if (!Files.exists(filePath)) {
            throw new IOException("File not found");
        }
        return Files.readAllBytes(filePath);
    }
    
    @Override
    public void deleteFile(String path) throws IOException {
        Path filePath = resolveToAbsolutePath(path);
        if (Files.exists(filePath)) {
            Files.delete(filePath);
            log.info("Deleted file: {}", filePath);
        }
    }
    
    @Override
    public boolean exists(String path) {
        try {
            Path filePath = resolveToAbsolutePath(path);
            return Files.exists(filePath);
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public String getPublicUrl(String path) {
        // Local storage has no public URL; return a stable storage key only.
        try {
            Path absolute = resolveToAbsolutePath(path);
            return rootLocation.relativize(absolute).toString().replace('\\', '/');
        } catch (Exception e) {
            return normalizeStorageKey(path);
        }
    }
}
