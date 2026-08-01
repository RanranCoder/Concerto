package com.pams.module.activity.controller;

import com.pams.common.Result;
import com.pams.module.activity.service.ActivityDetailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/activities")
public class ActivityDetailController {
    private final ActivityDetailService service;
    public ActivityDetailController(ActivityDetailService service) { this.service = service; }

    /** 活动详情聚合：策划书/议程/座位表/评分/签到/任务 */
    @GetMapping("/{id}/detail")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(service.aggregate(id));
    }
}
