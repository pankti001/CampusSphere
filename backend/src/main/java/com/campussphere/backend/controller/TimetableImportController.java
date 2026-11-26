package com.campussphere.backend.controller;

import java.io.IOException;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.TimetableImports;
import com.campussphere.backend.service.TimetableImports.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * College Admin: Excel template download, upload preview, confirmed import (drafts only) and copying a timetable.
 * Covered by the /api/college/timetables/** rule (COLLEGE_ADMIN); the college comes from the signed-in account.
 */
@RestController
@RequestMapping("/api/college/timetables")
public class TimetableImportController {
    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final TimetableImports imports;

    public TimetableImportController(TimetableImports imports) {
        this.imports = imports;
    }

    @GetMapping("/import/template")
    ResponseEntity<byte[]> template(@AuthenticationPrincipal Account actor, @RequestParam(required = false) Long semesterId) {
        var template = imports.template(actor, semesterId);
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(template.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(template.bytes());
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Preview preview(@AuthenticationPrincipal Account actor, @RequestParam long semesterId,
                    @RequestParam("file") MultipartFile file) throws IOException {
        return imports.preview(actor, semesterId, file.getOriginalFilename(), bytes(file));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Imported importFile(@AuthenticationPrincipal Account actor, @RequestParam long semesterId,
                        @RequestParam String sha256, @RequestParam("file") MultipartFile file) throws IOException {
        return imports.importFile(actor, semesterId, file.getOriginalFilename(), bytes(file), sha256);
    }

    @PostMapping("/copy")
    @ResponseStatus(HttpStatus.CREATED)
    CopyResult copy(@AuthenticationPrincipal Account actor, @RequestBody CopyRequest request) {
        return imports.copy(actor, request);
    }

    /** Refuses oversized files before reading them into memory. */
    private static byte[] bytes(MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > 2L * 1024 * 1024) throw ApiError.TIMETABLE_IMPORT_INVALID.exception();
        return file.getBytes();
    }
}
