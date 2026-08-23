package com.pams.module.content.controller;

import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.activity.controller.ActivityController;
import com.pams.module.content.dto.NewsRequest;
import com.pams.module.content.entity.News;
import com.pams.module.content.service.NewsService;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/news")
public class NewsController {
    private final NewsService service;
    public NewsController(NewsService service) { this.service = service; }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(service.page(keyword, page, size));
    }

    /** 新闻的增删改限干部，登录即可查询 */
    @PreAuthorize(ActivityController.LEADER)
    @PostMapping
    public Result<News> create(@Valid @RequestBody NewsRequest req,
                               @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.create(current == null ? null : current.getId(), req));
    }

    @PreAuthorize(ActivityController.LEADER)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody NewsRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    @PreAuthorize(ActivityController.LEADER)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
