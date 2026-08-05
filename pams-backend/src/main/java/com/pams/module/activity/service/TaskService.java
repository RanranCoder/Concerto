package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.module.activity.dto.TaskRequest;
import com.pams.module.activity.entity.Task;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.repository.TaskRepository;
import com.pams.module.notification.event.TaskAssignedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TaskService {
    private final TaskRepository repository;
    private final ActivityRepository activityRepository;
    private final ApplicationEventPublisher eventPublisher;

    public TaskService(TaskRepository repository, ActivityRepository activityRepository,
                       ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.activityRepository = activityRepository;
        this.eventPublisher = eventPublisher;
    }

    /** 测试友好构造器：eventPublisher 为 null，create 不发布事件。 */
    public TaskService(TaskRepository repository, ActivityRepository activityRepository) {
        this(repository, activityRepository, null);
    }

    public List<Task> listByActivity(Long activityId) {
        return repository.findByActivityIdOrderByStartDateAsc(activityId);
    }

    @Transactional
    public Task create(TaskRequest req, Long senderId) {
        // 校验活动是否存在
        activityRepository.findById(req.getActivityId())
                .orElseThrow(() -> new BizException(2001, "关联的活动不存在"));
        // 日期校验
        validateDates(req);
        Task t = new Task();
        t.setActivityId(req.getActivityId());
        apply(t, req);
        t.setStatus(Task.TaskStatus.TODO);
        t.setDeleted(0);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        Task saved = repository.save(t);
        // 发布任务指派事件
        if (eventPublisher != null && saved.getDeptId() != null) {
            eventPublisher.publishEvent(new TaskAssignedEvent(
                    saved.getId(), saved.getActivityId(), saved.getDeptId(),
                    saved.getName(), senderId));
        }
        return saved;
    }

    @Transactional
    public void update(Long id, TaskRequest req) {
        Task t = getEntity(id);
        validateDates(req);
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
        return repository.findById(id).orElseThrow(() -> new BizException(2008, "任务不存在"));
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
        // B6 fix: 捕获非法状态值
        if (req.getStatus() != null && !req.getStatus().isBlank()) {
            try {
                t.setStatus(Task.TaskStatus.valueOf(req.getStatus()));
            } catch (IllegalArgumentException e) {
                throw new BizException(2009, "无效的任务状态: " + req.getStatus());
            }
        }
        t.setPriority(req.getPriority() == null ? 0 : req.getPriority());
        t.setDescription(req.getDescription());
        t.setUpdatedAt(LocalDateTime.now());
    }

    private void validateDates(TaskRequest req) {
        if (req.getStartDate() != null && req.getEndDate() != null
                && req.getEndDate().isBefore(req.getStartDate())) {
            throw new BizException(2007, "结束日期不能早于开始日期");
        }
    }
}
