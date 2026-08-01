package com.pams.module.activity.controller;

import com.pams.common.BizException;
import com.pams.common.Result;
import com.pams.module.activity.dto.AgendaRequest;
import com.pams.module.activity.entity.ActivityAgenda;
import com.pams.module.activity.repository.ActivityAgendaRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/agendas")
public class AgendaController {
    private final ActivityAgendaRepository repository;
    public AgendaController(ActivityAgendaRepository repository) { this.repository = repository; }

    @GetMapping
    public Result<List<ActivityAgenda>> list(@RequestParam Long activityId) {
        return Result.ok(repository.findByActivityIdOrderByStepNoAsc(activityId));
    }

    @PostMapping
    public Result<ActivityAgenda> create(@Valid @RequestBody AgendaRequest req) {
        ActivityAgenda a = new ActivityAgenda();
        a.setActivityId(req.getActivityId());
        a.setStepNo(req.getStepNo());
        a.setTitle(req.getTitle());
        a.setRemark(req.getRemark());
        a.setCreatedAt(LocalDateTime.now());
        return Result.ok(repository.save(a));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AgendaRequest req) {
        ActivityAgenda a = repository.findById(id)
                .orElseThrow(() -> new BizException(2101, "议程不存在"));
        a.setStepNo(req.getStepNo());
        a.setTitle(req.getTitle());
        a.setRemark(req.getRemark());
        repository.save(a);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        repository.findById(id).orElseThrow(() -> new BizException(2101, "议程不存在"));
        repository.deleteById(id);
        return Result.ok();
    }
}
