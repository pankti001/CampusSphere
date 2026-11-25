package com.campussphere.backend.service;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import com.campussphere.backend.security.ApiError;

@Component
public class MaterialFiles {
    static final int LIMIT = 10 * 1024 * 1024;
    private final Path root;
    MaterialFiles(@Value("${app.materials.directory:${user.home}/.campussphere/private-materials}") String location) {
        root = Path.of(location).toAbsolutePath().normalize();
    }
    record Upload(String format, byte[] bytes) {}
    static Upload inspect(MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > LIMIT) throw ApiError.INVALID_REQUEST.exception();
        byte[] bytes;
        try (var stream = file.getInputStream()) { bytes = stream.readNBytes(LIMIT+1); }
        String name = Objects.toString(file.getOriginalFilename(),"").toLowerCase(Locale.ROOT);
        if (bytes.length == 0 || bytes.length > LIMIT) throw ApiError.INVALID_REQUEST.exception();
        if (name.endsWith(".pdf") && new String(bytes,0,Math.min(5,bytes.length),StandardCharsets.US_ASCII).equals("%PDF-")
            && new String(bytes,Math.max(0,bytes.length-1024),Math.min(1024,bytes.length),StandardCharsets.ISO_8859_1).stripTrailing().endsWith("%%EOF"))
            return new Upload("pdf",bytes);
        if (name.endsWith(".txt")) {
            try {
                var chars = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
                if (chars.chars().noneMatch(c -> Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t')) return new Upload("txt",bytes);
            } catch (CharacterCodingException invalid) { /* Reject non-UTF-8 text. */ }
        }
        throw ApiError.INVALID_REQUEST.exception();
    }
    Path path(UUID key) { return root.resolve(key.toString()); }
    void save(UUID key, byte[] bytes) throws IOException {
        Files.createDirectories(root);
        try (var out = Files.newOutputStream(path(key),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) { out.write(bytes); }
    }
}
