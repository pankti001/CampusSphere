package com.campussphere.backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipFile;

import com.campussphere.backend.security.ApiError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Validates uploaded study material files and keeps them in private storage outside the web root.
 * Files are named only by a random UUID; client file names never reach the file system.
 */
@Component
public class MaterialFiles {
    static final int LIMIT = 10 * 1024 * 1024;
    private static final int MAX_NAME = 200;
    private static final int MAX_ZIP_ENTRIES = 5_000;

    /** Allowed formats, their canonical MIME type and the MIME types a client may declare for them. */
    enum Format {
        PDF("pdf", "application/pdf", Set.of("application/pdf", "application/x-pdf")),
        DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", Set.of()),
        PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", Set.of()),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Set.of()),
        TXT("txt", "text/plain", Set.of("text/plain"));

        final String extension;
        final String mime;
        private final Set<String> declared;

        Format(String extension, String mime, Set<String> declared) {
            this.extension = extension;
            this.mime = mime;
            this.declared = declared;
        }

        boolean accepts(String contentType) {
            if (contentType == null || contentType.isBlank()) return true;
            String type = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
            return type.equals(mime) || declared.contains(type) || type.equals("application/octet-stream");
        }

        static Optional<Format> of(String extension) {
            return Arrays.stream(values()).filter(f -> f.extension.equals(extension)).findFirst();
        }
    }

    public record Upload(Format format, String fileName, byte[] bytes, String sha256) {
        public String extension() { return format.extension; }
        public String mime() { return format.mime; }
    }

    private final Path root;

    MaterialFiles(@Value("${app.materials.directory:${user.home}/.campussphere/private-materials}") String location) {
        root = Path.of(location).toAbsolutePath().normalize();
    }

    /** Checks name, declared type, size and actual content. Throws MATERIAL_FILE_INVALID when anything is wrong. */
    static Upload inspect(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() > LIMIT) throw invalid();
        String name = cleanName(file.getOriginalFilename());
        int dot = name.lastIndexOf('.');
        Format format = Format.of(name.substring(dot + 1).toLowerCase(Locale.ROOT)).orElseThrow(MaterialFiles::invalid);
        if (!format.accepts(file.getContentType())) throw invalid();
        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readNBytes(LIMIT + 1);
        }
        if (bytes.length == 0 || bytes.length > LIMIT || !contentMatches(format, bytes)) throw invalid();
        return new Upload(format, name, bytes, sha256(bytes));
    }

    /** Keeps only the base name, removes control and reserved characters and limits the length. */
    static String cleanName(String original) {
        if (original == null) throw invalid();
        String base = original.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        StringBuilder out = new StringBuilder();
        base.codePoints().forEach(c -> {
            if (!Character.isISOControl(c) && "<>:\"|?*".indexOf(c) < 0) out.appendCodePoint(c);
        });
        String name = out.toString().strip();
        while (name.startsWith(".")) name = name.substring(1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) throw invalid();
        if (name.length() > MAX_NAME) {
            String extension = name.substring(dot);
            name = name.substring(0, MAX_NAME - extension.length()).strip() + extension;
        }
        return name;
    }

    static boolean contentMatches(Format format, byte[] bytes) throws IOException {
        return switch (format) {
            case PDF -> startsWith(bytes, "%PDF-") && new String(bytes, Math.max(0, bytes.length - 1024),
                    Math.min(1024, bytes.length), StandardCharsets.ISO_8859_1).contains("%%EOF");
            case TXT -> isPlainUtf8(bytes);
            case DOCX -> isOfficePackage(bytes, "word/document.xml");
            case PPTX -> isOfficePackage(bytes, "ppt/presentation.xml");
            case XLSX -> isOfficePackage(bytes, "xl/workbook.xml");
        };
    }

    private static boolean startsWith(byte[] bytes, String prefix) {
        byte[] head = prefix.getBytes(StandardCharsets.US_ASCII);
        return bytes.length >= head.length && Arrays.equals(Arrays.copyOf(bytes, head.length), head);
    }

    private static boolean isPlainUtf8(byte[] bytes) {
        try {
            var text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return text.chars().noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t');
        } catch (CharacterCodingException invalid) {
            return false;
        }
    }

    /** Reads only the ZIP directory (nothing is extracted). Rejects macros and oversized archives. */
    private static boolean isOfficePackage(byte[] bytes, String mainPart) throws IOException {
        if (!startsWith(bytes, "PK\u0003\u0004")) return false;
        Path temp = Files.createTempFile("campussphere-material-", ".zip");
        try {
            Files.write(temp, bytes);
            try (ZipFile zip = new ZipFile(temp.toFile())) {
                if (zip.size() > MAX_ZIP_ENTRIES) return false;
                boolean types = false, main = false;
                for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                    String entry = entries.nextElement().getName();
                    if (entry.equals("[Content_Types].xml")) types = true;
                    if (entry.equals(mainPart)) main = true;
                    if (entry.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin")) return false;
                }
                return types && main;
            } catch (java.util.zip.ZipException corrupt) {
                return false;
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    Path path(UUID key) {
        Path file = root.resolve(key.toString()).normalize();
        if (!file.getParent().equals(root)) throw new IllegalStateException("Material path escaped storage root.");
        return file;
    }

    /** Writes to a temporary file first, then moves it into place, so a partial file is never visible. */
    void save(UUID key, byte[] bytes) throws IOException {
        Files.createDirectories(root);
        Path target = path(key);
        Path temp = Files.createTempFile(root, "upload-", ".part");
        try {
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    void delete(UUID key) throws IOException {
        Files.deleteIfExists(path(key));
    }

    private static RuntimeException invalid() {
        return ApiError.MATERIAL_FILE_INVALID.exception();
    }
}
