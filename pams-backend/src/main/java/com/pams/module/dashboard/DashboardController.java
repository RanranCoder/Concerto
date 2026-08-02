package com.pams.module.dashboard;

import com.pams.common.Result;
import com.pams.security.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardService service;

    public DashboardController(DashboardService service) { this.service = service; }

    @GetMapping
    public Result<Map<String, Object>> dashboard(@AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.dashboard(current));
    }
}
