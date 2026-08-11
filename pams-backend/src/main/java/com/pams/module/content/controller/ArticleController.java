package com.pams.module.content.controller;

import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.content.dto.ArticleRequest;
import com.pams.module.content.dto.PublishRequest;
import com.pams.module.content.dto.ReviewRequest;
import com.pams.module.content.dto.StatsRequest;
import com.pams.module.content.entity.Article;
import com.pams.module.content.service.ArticleService;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/articles")
public class ArticleController {
    private final ArticleService service;
    public ArticleController(ArticleService service) { this.service = service; }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long activityId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(service.page(status, type, keyword, activityId, page, size));
    }

    @PreAuthorize("hasRole('MEDIA_LEADER') or hasAnyRole('TEACHER','DIRECTOR')")
    @PostMapping
    public Result<Article> create(@Valid @RequestBody ArticleRequest req,
                                  @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.create(current.getId(), req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ArticleRequest req,
                               @AuthenticationPrincipal LoginUser current) {
        service.update(id, req, current.getId(), ArticleService.isLeader(current.getRoleCode()));
        return Result.ok();
    }

    @PutMapping("/{id}/submit")
    public Result<Void> submit(@PathVariable Long id, @AuthenticationPrincipal LoginUser current) {
        service.submit(id, current.getId(), ArticleService.isLeader(current.getRoleCode()));
        return Result.ok();
    }

    @PreAuthorize("hasRole('MEDIA_LEADER') or hasAnyRole('TEACHER','DIRECTOR')")
    @PutMapping("/{id}/review")
    public Result<Void> review(@PathVariable Long id, @Valid @RequestBody ReviewRequest req,
                               @AuthenticationPrincipal LoginUser current) {
        boolean approved = req.getApproved() != null && req.getApproved();
        service.review(id, approved, req.getComment(), current.getId());
        return Result.ok();
    }

    @PutMapping("/{id}/publish")
    public Result<Void> publish(@PathVariable Long id, @Valid @RequestBody PublishRequest req,
                                @AuthenticationPrincipal LoginUser current) {
        service.publish(id, req, current.getId(), ArticleService.isLeader(current.getRoleCode()));
        return Result.ok();
    }

    @PutMapping("/{id}/stats")
    public Result<Void> updateStats(@PathVariable Long id, @Valid @RequestBody StatsRequest req,
                                    @AuthenticationPrincipal LoginUser current) {
        service.updateStats(id, req, current.getId(), ArticleService.isLeader(current.getRoleCode()));
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, @AuthenticationPrincipal LoginUser current) {
        service.delete(id, current.getId(), ArticleService.isLeader(current.getRoleCode()));
        return Result.ok();
    }
}
