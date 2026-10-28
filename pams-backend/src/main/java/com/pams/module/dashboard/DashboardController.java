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
        var ctx = com.pams.module.agent.core.ToolContext.current(null,null);
        Map<String,Object> result = new java.util.LinkedHashMap<>();
        result.put("activityStats",ctx.hasPermission("activity:view") ? service.activityStats() : Map.of());
        result.put("weekSchedules",ctx.hasPermission("schedule:view") ? service.weekScheduleCount() : 0);
        result.put("recentArticles",ctx.hasPermission("content:view") ? service.recentArticles() : java.util.List.of());
        result.put("recentMaterials",ctx.hasPermission("material:view") ? service.recentMaterials() : java.util.List.of());
        result.put("recentAnnouncements",ctx.hasPermission("notice:view") ? service.recentAnnouncements() : java.util.List.of());
        result.put("myTasks",ctx.hasPermission("task:view") ? service.myTasks(current) : java.util.List.of());
        return Result.ok(result);
    }
}
