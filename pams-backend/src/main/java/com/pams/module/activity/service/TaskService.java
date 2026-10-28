package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.common.OrgContext;
import com.pams.module.activity.dto.TaskPlan;
import com.pams.module.activity.dto.TaskRequest;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.entity.Task;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.repository.TaskRepository;
import com.pams.module.notification.event.TaskAssignedEvent;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TaskService {
    @Autowired
    private com.pams.module.notification.service.NotificationBusinessEvents notifications;
    private final TaskRepository repository;
    private final ActivityRepository activityRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public TaskService(TaskRepository repository, ActivityRepository activityRepository,
                       ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.activityRepository = activityRepository;
        this.eventPublisher = eventPublisher;
    }

    public TaskService(TaskRepository repository, ActivityRepository activityRepository) {
        this(repository, activityRepository, null);
    }

    public List<Task> listByActivity(Long activityId) {
        return repository.findByActivityIdOrderByStartDateAsc(activityId);
    }

    @Transactional
    public Task create(TaskRequest req, Long senderId) {
        writableActivity(req.getActivityId());
        Task task = new Task();
        task.setActivityId(req.getActivityId());
        task.setCreatedAt(LocalDateTime.now());
        task.setDeleted(0);
        apply(task, req);
        validateCandidate(task, listByActivity(req.getActivityId()));
        Task saved = repository.save(task);
        assigned(saved, senderId);
        return saved;
    }

    @Transactional
    public void update(Long id, TaskRequest req) {
        Task task = writableTask(id);
        TaskRequest notificationBefore = snapshot(task);
        if (!Objects.equals(task.getActivityId(), req.getActivityId()))
            throw new BizException(2007, "不能把任务移动到其他活动");
        checkRevision(task, req.getExpectedRevision());
        Task proposed = copy(task);
        apply(proposed, req);
        validateCandidate(proposed, listByActivity(task.getActivityId()));
        apply(task, req);
        repository.save(task);
        if (notifications != null) notifications.taskChanged(notificationBefore, task);
    }

    @Transactional
    public void delete(Long id) { deleteWithUndo(id, null); }

    @Transactional
    public TaskPlan.Result deleteWithUndo(Long id, Long expectedRevision) {
        Task task = writableTask(id);
        checkRevision(task, expectedRevision);
        if (listByActivity(task.getActivityId()).stream().anyMatch(t -> Objects.equals(t.getDependsOn(), id)))
            throw new BizException(2007, "仍有后续任务依赖此任务，请先解除前置关系");
        Map<Long, TaskRequest> before = new LinkedHashMap<>();
        before.put(id, snapshot(task));
        task.setDeleted(1);
        task.setUpdatedAt(LocalDateTime.now());
        repository.save(task);
        if (notifications != null) notifications.taskChanged(before.get(id), task);
        return result(task.getActivityId(), List.of(task), before);
    }

    @Transactional
    public Task updateProgress(Long id, Integer progress) { return updateProgress(id, progress, null); }

    @Transactional
    public Task updateProgress(Long id, Integer progress, Long expectedRevision) {
        if (progress == null || progress < 0 || progress > 100)
            throw new BizException(2007, "进度需在0-100");
        Task task = writableTask(id);
        checkRevision(task, expectedRevision);
        TaskRequest notificationBefore = snapshot(task);
        task.setProgress(progress);
        deriveStatus(task);
        task.setUpdatedAt(LocalDateTime.now());
        Task saved = repository.saveAndFlush(task);
        if (notifications != null) notifications.taskChanged(notificationBefore, saved);
        return saved;
    }

    /** The activity lock serializes task writes, including preview/save and restore. */
    private Activity writableActivity(Long id) {
        Activity activity = activityRepository.lockById(id)
                .orElseThrow(() -> new BizException(2001, "活动不存在"));
        if (activity.getStatus() == ActivityStatus.ARCHIVED)
            throw new BizException(2007, "活动已归档，任务计划只读");
        return activity;
    }

    private Task writableTask(Long id) {
        // Scalar lookup avoids loading a stale managed entity before waiting for the lock.
        Long activityId = repository.activityIdOf(id)
                .orElseThrow(() -> new BizException(2008, "任务不存在"));
        writableActivity(activityId);
        return getEntity(id);
    }

    public Task getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2008, "任务不存在"));
    }

    @Transactional
    public TaskPlan.Preview preview(TaskPlan.Request request) {
        writableActivity(request.task().getActivityId());
        return prepare(request, listByActivity(request.task().getActivityId()));
    }

    @Transactional
    public TaskPlan.Result savePlan(TaskPlan.Request request, Long senderId) {
        Long activityId = request.task().getActivityId();
        writableActivity(activityId);
        List<Task> tasks = listByActivity(activityId);
        TaskPlan.Preview preview = prepare(request, tasks);
        if (request.previewToken() == null || !request.previewToken().equals(preview.token()))
            throw new BizException(2007, "任务计划已发生变化，请重新预览后保存");
        Map<Long, TaskRequest> before = new LinkedHashMap<>();
        List<Task> changed = new ArrayList<>();
        Task target;
        if (request.id() == null) {
            target = new Task();
            target.setActivityId(activityId);
            target.setCreatedAt(LocalDateTime.now());
            target.setDeleted(0);
        } else {
            target = tasks.stream().filter(t -> t.getId().equals(request.id())).findFirst().orElseThrow();
            before.put(target.getId(), snapshot(target));
        }
        apply(target, request.task());
        target = repository.save(target);
        if (request.id() == null) {
            before.put(target.getId(), null);
            assigned(target, senderId);
        }
        changed.add(target);
        if (Boolean.TRUE.equals(request.cascade())) {
            for (TaskPlan.Impact impact : preview.impacts()) {
                Task task = tasks.stream().filter(t -> t.getId().equals(impact.after().getId())).findFirst().orElseThrow();
                before.put(task.getId(), snapshot(task));
                task.setStartDate(impact.after().getStartDate());
                task.setEndDate(impact.after().getEndDate());
                task.setUpdatedAt(LocalDateTime.now());
                deriveStatus(task);
                repository.save(task);
                changed.add(task);
            }
        }
        return result(activityId, changed, before);
    }

    private TaskPlan.Preview prepare(TaskPlan.Request request, List<Task> tasks) {
        Task candidate = request.id() == null ? new Task() : tasks.stream()
                .filter(t -> t.getId().equals(request.id())).findFirst().map(TaskService::copy)
                .orElseThrow(() -> new BizException(2008, "任务不存在或不属于当前活动"));
        if (request.id() != null) {
            if (request.task().getExpectedRevision() == null)
                throw new BizException(2007, "缺少任务版本，请刷新后重试");
            checkRevision(candidate, request.task().getExpectedRevision());
        }
        candidate.setActivityId(request.task().getActivityId());
        apply(candidate, request.task());
        validateCandidate(candidate, tasks);
        Task previous = tasks.stream().filter(t -> Objects.equals(t.getId(), request.id())).findFirst().orElse(null);
        if (previous == null || Objects.equals(previous.getStartDate(), candidate.getStartDate())
                && Objects.equals(previous.getEndDate(), candidate.getEndDate())
                && Objects.equals(previous.getDependsOn(), candidate.getDependsOn()))
            return new TaskPlan.Preview(candidate, List.of(), fingerprint(tasks));
        Map<Long, Task> graph = tasks.stream().collect(Collectors.toMap(Task::getId, TaskService::copy));
        Long key = candidate.getId() == null ? -1L : candidate.getId();
        graph.put(key, copy(candidate));
        List<TaskPlan.Impact> impacts = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(key);
        while (!queue.isEmpty()) {
            Long parentId = queue.removeFirst();
            if (!visited.add(parentId)) continue;
            Task parent = graph.get(parentId);
            for (Task child : graph.values()) {
                if (!Objects.equals(child.getDependsOn(), parentId)) continue;
                if (parent.getEndDate() != null && child.getStartDate() != null
                        && child.getEndDate() != null && !child.getStartDate().isAfter(parent.getEndDate())) {
                    Task before = copy(child);
                    long duration = ChronoUnit.DAYS.between(child.getStartDate(), child.getEndDate());
                    child.setStartDate(parent.getEndDate().plusDays(1));
                    child.setEndDate(child.getStartDate().plusDays(duration));
                    impacts.add(new TaskPlan.Impact(before, copy(child)));
                }
                queue.add(child.getId());
            }
        }
        return new TaskPlan.Preview(candidate, impacts, fingerprint(tasks));
    }

    /** Restore only touched rows, and reject any row with a newer revision. */
    @Transactional
    public TaskPlan.Result restore(TaskPlan.Undo request) {
        Activity activity = writableActivity(request.activityId());
        List<Task> active = listByActivity(request.activityId());
        Map<Long, Task> graph = active.stream().collect(Collectors.toMap(Task::getId, TaskService::copy));
        Map<Long, TaskRequest> before = new LinkedHashMap<>();
        Map<Long, Task> changed = new LinkedHashMap<>();
        for (TaskPlan.UndoEntry entry : request.entries()) {
            if (changed.containsKey(entry.id())) throw new BizException(2007, "撤销任务不能重复");
            Task task = repository.findForRestore(entry.id(), activity.getId(), activity.getOrgId())
                    .orElseThrow(() -> new BizException(2008, "任务不存在或不属于当前活动"));
            checkRevision(task, entry.expectedRevision());
            before.put(task.getId(), Objects.equals(task.getDeleted(), 1) ? null : snapshot(task));
            Task proposed = copy(task);
            if (entry.before() == null) {
                graph.remove(entry.id());
            } else {
                if (!Objects.equals(entry.before().getActivityId(), activity.getId()))
                    throw new BizException(2007, "撤销不能跨活动恢复任务");
                apply(proposed, entry.before());
                graph.put(entry.id(), proposed);
            }
            changed.put(entry.id(), task);
        }
        for (TaskPlan.UndoEntry entry : request.entries()) {
            if (entry.before() != null) validatePath(graph, entry.id());
            else if (graph.values().stream().anyMatch(t -> Objects.equals(t.getDependsOn(), entry.id())))
                throw new BizException(2007, "仍有后续任务依赖此任务，无法撤销创建");
        }
        for (TaskPlan.UndoEntry entry : request.entries()) {
            Task task = changed.get(entry.id());
            if (entry.before() != null) apply(task, entry.before());
            task.setDeleted(entry.before() == null ? 1 : 0);
            task.setUpdatedAt(LocalDateTime.now());
            repository.save(task);
        }
        return result(activity.getId(), new ArrayList<>(changed.values()), before);
    }

    private TaskPlan.Result result(Long activityId, List<Task> changed, Map<Long, TaskRequest> before) {
        repository.flush();
        if (notifications != null) for (Task task : changed) notifications.taskChanged(before.get(task.getId()), task);
        List<TaskPlan.UndoEntry> entries = changed.stream()
                .map(t -> new TaskPlan.UndoEntry(t.getId(), revision(t), before.get(t.getId()))).toList();
        return new TaskPlan.Result(listByActivity(activityId), new TaskPlan.Undo(activityId, entries));
    }

    private static Long revision(Task task) { return task.getRevision() == null ? 0L : task.getRevision(); }

    private void checkRevision(Task task, Long expected) {
        if (expected != null && !Objects.equals(revision(task), expected))
            throw new BizException(2007, "任务已被其他操作更新，请刷新后重试；撤销不会覆盖后续修改");
    }

    private String fingerprint(List<Task> tasks) {
        String versions = tasks.stream().sorted(Comparator.comparing(Task::getId))
                .map(t -> t.getId() + ":" + revision(t)).collect(Collectors.joining(","));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(versions.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void validateCandidate(Task candidate, List<Task> tasks) {
        Map<Long, Task> graph = tasks.stream().collect(Collectors.toMap(Task::getId, TaskService::copy));
        Long key = candidate.getId() == null ? -1L : candidate.getId();
        graph.put(key, candidate);
        validatePath(graph, key);
    }

    private void validatePath(Map<Long, Task> graph, Long id) {
        Set<Long> visited = new HashSet<>();
        while (id != null) {
            if (!visited.add(id)) throw new BizException(2007, "前置关系形成循环，请选择其他任务");
            Task task = graph.get(id);
            if (task == null) throw new BizException(2007, "前置任务不存在或不属于当前活动");
            id = task.getDependsOn();
        }
    }

    private void apply(Task task, TaskRequest req) {
        if (req.getDeptId() != null && !repository.departmentInOrg(req.getDeptId(), OrgContext.getOrDefault()))
            throw new BizException(2007, "负责部门不存在或不属于当前组织，请重新选择");
        if (req.getName() == null || req.getName().isBlank()) throw new BizException(2007, "任务名称不能为空");
        if ((req.getStartDate() == null) != (req.getEndDate() == null))
            throw new BizException(2007, "开始和结束日期需同时填写，或同时留空");
        if (req.getStartDate() != null && req.getEndDate().isBefore(req.getStartDate()))
            throw new BizException(2007, "结束日期不能早于开始日期");
        if (req.getStatus() != null && !req.getStatus().isBlank()) {
            try { Task.TaskStatus.valueOf(req.getStatus()); }
            catch (IllegalArgumentException invalid) { throw new BizException(2009, "无效的任务状态: " + req.getStatus()); }
        }
        task.setName(req.getName().trim());
        Long account = req.getAssigneeUserId();
        if (account == null && Objects.equals(task.getAssignee(), req.getAssignee()) && Objects.equals(task.getDeptId(), req.getDeptId())) account = task.getAssigneeUserId();
        task.setAssigneeUserId(account);
        task.setDeptId(req.getDeptId());
        task.setAssignee(account != null && notifications != null ? notifications.taskAssignee(account, req.getDeptId()) : req.getAssignee());
        task.setStartDate(req.getStartDate());
        task.setIsMilestone(Objects.equals(req.getIsMilestone(), 1) ? 1 : 0);
        task.setEndDate(task.getIsMilestone() == 1 ? req.getStartDate() : req.getEndDate());
        task.setDependsOn(req.getDependsOn());
        int progress = req.getProgress() == null ? Objects.requireNonNullElse(task.getProgress(), 0) : req.getProgress();
        if (progress < 0 || progress > 100) throw new BizException(2007, "进度需在0-100");
        task.setProgress(progress);
        task.setPriority(Objects.requireNonNullElse(req.getPriority(), 0));
        task.setDescription(req.getDescription());
        task.setUpdatedAt(LocalDateTime.now());
        deriveStatus(task);
    }

    private void deriveStatus(Task task) {
        task.setStatus(task.getProgress() >= 100 ? Task.TaskStatus.DONE
                : task.getEndDate() != null && task.getEndDate().isBefore(LocalDate.now()) ? Task.TaskStatus.DELAYED
                : task.getProgress() > 0 ? Task.TaskStatus.DOING : Task.TaskStatus.TODO);
    }

    private static Task copy(Task task) { Task result = new Task(); BeanUtils.copyProperties(task, result); return result; }

    private TaskRequest snapshot(Task task) {
        TaskRequest result = new TaskRequest();
        BeanUtils.copyProperties(task, result);
        result.setStatus(task.getStatus() == null ? null : task.getStatus().name());
        return result;
    }

    private void assigned(Task task, Long senderId) {
        if (notifications != null) { notifications.taskChanged(null, task); return; }
        if (eventPublisher != null && task.getDeptId() != null)
            eventPublisher.publishEvent(new TaskAssignedEvent(task.getId(), task.getActivityId(), task.getDeptId(), task.getName(), senderId));
    }
}
