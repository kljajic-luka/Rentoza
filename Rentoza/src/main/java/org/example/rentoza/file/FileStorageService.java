package org.example.rentoza.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class FileStorageService {

    @Value("${upload.dir:uploads}")
    private String uploadDir;

    public String saveFile(MultipartFile file) {
        try {
            File folder = new File(uploadDir);
            if (!folder.exists()) folder.mkdirs();

            String uniqueName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path filePath = Paths.get(uploadDir, uniqueName);
            Files.copy(file.getInputStream(), filePath);

            return "/uploads/" + uniqueName;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save file: " + e.getMessage());
        }
    }
}
