package com.campussphere.backend.controller;

import com.campussphere.backend.service.PlatformOverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlatformOverviewController {
    private final PlatformOverviewService overview;

    public PlatformOverviewController(PlatformOverviewService overview) {
        this.overview = overview;
    }

    @GetMapping("/api/platform/overview")
    public PlatformOverviewService.Overview overview() {
        return overview.load();
    }
}
