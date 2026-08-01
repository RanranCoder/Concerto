package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.module.activity.dto.TaskRequest;
import com.pams.module.activity.entity.Task;
import com.pams.module.activity.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TaskService {
    private final TaskRepository repository;
    public TaskService(TaskRepository repository) { this.repository = repository; }

    public List<Task> listByActivity(Long activityId) {
        return repository.findByActivityIdOrderByStartDateAsc(activityId);
    }

    @Transactional
    public Task create(TaskRequest req) {
        Task t = new Task();
        t.setActivityId(req.getActivityId());
        apply(t, req);
        t.setStatus(Task.TaskStatus.TODO);
        t.setDeleted(0);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        return repository.save(t);
    }

    @Transactional
    public void update(Long id, TaskRequest req) {
        Task t = getEntity(id);
        apply(t, req);
        repository.save(t);
    }

    @Transactional
    public void delete(Long id) {
        Task t = getEntity(id);
        t.setDeleted(1);
        t.setUpdatedAt(LocalDateTime.now());
        repository.save(t);
    }

    /**
     * 更新任务进度。progress 必须在 0-100，越界抛 BizException。
     */
    @Transactional
    public void updateProgress(Long id, Integer progress) {
        Task t = getEntity(id);
        if (progress == null || progress < 0 || progress > 100) {
            throw new BizException(2007, "进度需在0-100");
        }
        t.setProgress(progress);
        t.setUpdatedAt(LocalDateTime.now());
        repository.save(t);
    }

    public Task getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2004, "任务不存在"));
    }

    private void apply(Task t, TaskRequest req) {
        t.setName(req.getName());
        t.setDeptId(req.getDeptId());
        t.setAssignee(req.getAssignee());
        t.setStartDate(req.getStartDate());
        t.setEndDate(req.getEndDate());
        t.setDependsOn(req.getDependsOn());
        t.setIsMilestone(req.getIsMilestone() == null ? 0 : req.getIsMilestone());
        if (req.getProgress() == null) {
            if (t.getProgress() == null) t.setProgress(0);
        } else if (req.getProgress() < 0 || req.getProgress() > 100) {
            throw new BizException(2007, "进度需在0-100");
        } else {
            t.setProgress(req.getProgress());
        }
        if (req.getStatus() != null && !req.getStatus().isBlank()) {
            t.setStatus(Task.TaskStatus.valueOf(req.getStatus()));
        }
        t.setPriority(req.getPriority() == null ? 0 : req.getPriority());
        t.setDescription(req.getDescription());
        t.setUpdatedAt(LocalDateTime.now());
    }
}
