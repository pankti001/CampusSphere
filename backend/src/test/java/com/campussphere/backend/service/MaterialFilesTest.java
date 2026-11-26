package com.campussphere.backend.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.campussphere.backend.security.ApiError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;

class MaterialFilesTest {
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    private static MaterialFiles.Upload inspect(String name, String type, byte[] bytes) throws Exception {
        return MaterialFiles.inspect(new MockMultipartFile("file", name, type, bytes));
    }

    private static void assertInvalid(String name, String type, byte[] bytes) {
        var failure = assertThrows(ApiError.Failure.class, () -> inspect(name, type, bytes));
        assertEquals(ApiError.MATERIAL_FILE_INVALID, failure.error());
    }

    private static byte[] zip(String... entries) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test void acceptsSupportedFormatsAndRecordsCanonicalTypeAndHash() throws Exception {
        var pdf = inspect("Unit 1 notes.pdf", "application/pdf", PDF);
        assertEquals("pdf", pdf.extension());
        assertEquals("application/pdf", pdf.mime());
        assertEquals(64, pdf.sha256().length());
        assertEquals("txt", inspect("outline.txt", "text/plain; charset=UTF-8", "Course outline\n".getBytes()).extension());
        assertEquals("docx", inspect("lab.docx", "application/octet-stream",
                zip("[Content_Types].xml", "word/document.xml")).extension());
        assertEquals("pptx", inspect("slides.pptx", null, zip("[Content_Types].xml", "ppt/presentation.xml")).extension());
        assertEquals("xlsx", inspect("marks.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                zip("[Content_Types].xml", "xl/workbook.xml")).extension());
    }

    @Test void rejectsMislabelledMacroAndUnsupportedContent() throws Exception {
        assertInvalid("file.pdf", "application/pdf", "plain text".getBytes());
        assertInvalid("file.txt", "text/plain", new byte[]{0, 1, 2});
        assertInvalid("file.exe", "application/octet-stream", "MZ".getBytes());
        assertInvalid("file.docx", null, zip("[Content_Types].xml", "ppt/presentation.xml"));
        assertInvalid("file.docx", null, zip("[Content_Types].xml", "word/document.xml", "word/vbaProject.bin"));
        assertInvalid("file.docx", null, "PK\u0003\u0004 not a real zip".getBytes(StandardCharsets.ISO_8859_1));
        assertInvalid("file.pdf", "text/html", PDF);
    }

    @Test void rejectsEmptyOversizedAndNamelessFiles() {
        assertInvalid("file.txt", "text/plain", new byte[0]);
        assertInvalid("file.txt", "text/plain", new byte[MaterialFiles.LIMIT + 1]);
        assertInvalid(".pdf", "application/pdf", PDF);
        assertInvalid("noextension", "application/pdf", PDF);
    }

    @Test void stripsPathsAndUnsafeCharactersFromFileNames() {
        assertEquals("notes.pdf", MaterialFiles.cleanName("..\\..\\windows/system32/notes.pdf"));
        assertEquals("evilname.pdf", MaterialFiles.cleanName("/etc/../evil<na>me.pdf"));
        assertEquals("hidden.pdf", MaterialFiles.cleanName("..hidden.pdf"));
        String longName = "a".repeat(300) + ".pdf";
        String cleaned = MaterialFiles.cleanName(longName);
        assertEquals(200, cleaned.length());
        assertTrue(cleaned.endsWith(".pdf"));
    }

    @Test void storesFilesOnlyUnderRandomKeysInsideTheRoot(@TempDir Path root) throws Exception {
        var files = new MaterialFiles(root.toString());
        UUID key = UUID.randomUUID();
        files.save(key, PDF);
        Path stored = files.path(key);
        assertEquals(root.toAbsolutePath().normalize(), stored.getParent());
        assertArrayEquals(PDF, Files.readAllBytes(stored));
        try (var listing = Files.list(root)) {
            assertEquals(1, listing.count(), "no temporary files remain");
        }
        files.delete(key);
        assertFalse(Files.exists(stored));
    }
}
