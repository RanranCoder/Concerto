package com.pams.module.agent.service;

import com.pams.common.OrgContext;
import com.pams.module.agent.core.AgentActionPlan;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.AgentMessage;
import com.pams.module.agent.entity.AgentTask;
import com.pams.module.agent.orchestration.RuntimeIdentity;
import com.pams.module.agent.repository.AgentMessageRepository;
import com.pams.module.agent.repository.AgentRequestRepository;
import com.pams.module.agent.repository.AgentTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/** Authorizes one explicitly scoped, reversible write from a persisted current user message. */
@Service
public class ScopedWriteDelegation {
    public static final String ARTICLE = "draftArticleFromActivity";
    public static final String SCHEDULE = "generateActivitySchedule";
    private static final String ACTIVITY = "(?:请\\h*)?(?:为|给)活动\\h*[#＃]([1-9][0-9]{0,17})\\h*";
    private static final String DATE = "([0-9]{4}-[0-9]{2}-[0-9]{2})";
    private static final String TIME = "([0-9]{2}:[0-9]{2})";
    private static final String END = "[。！!]?";
    private static final Pattern PERSONAL_ARTICLE = Pattern.compile(ACTIVITY
            + "(?:保存|起草并保存|生成并保存)\\h*(?:一份\\h*)?(?:本人|我的|我自己的)\\h*(?:报道|新闻稿|推文)\\h*草稿" + END);
    private static final Pattern SCHEDULE_DRAFT = Pattern.compile(ACTIVITY
            + "(?:保存|生成并保存)\\h*(?:一份\\h*)?" + DATE + "\\h*(?:至|到)\\h*" + DATE
            + "\\h*每天\\h*" + TIME + "\\h*(?:至|到)\\h*" + TIME + "\\h*[，,]?\\h*部门\\h*[#＃]([1-9][0-9]{0,17})"
            + "\\h*[，,]?\\h*每(?:班|个时段)\\h*([1-9][0-9]?)\\h*人(?:的)?\\h*排班草稿" + END);

    private final AgentRequestRepository requests;
    private final AgentMessageRepository messages;
    private final AgentTaskRepository tasks;
    private final AgentRequestService requestService;
    private final DraftContent drafts;

    public ScopedWriteDelegation(AgentRequestRepository requests, AgentMessageRepository messages,
                                 AgentTaskRepository tasks, AgentRequestService requestService, DraftContent drafts) {
        this.requests = requests;
        this.messages = messages;
        this.tasks = tasks;
        this.requestService = requestService;
        this.drafts = drafts;
    }

    public static boolean supports(String toolName) {
        return ARTICLE.equals(toolName) || SCHEDULE.equals(toolName);
    }

    /**
     * Lock the same request row used for steering and cancellation, then claim the prepared task
     * in that transaction. A grant is consumed even if the claimed write later returns an error.
     */
    @Transactional
    public OptionalLong claim(AgentTask task, AgentActionPlan plan, ToolContext ctx) {
        if (!supports(task.getToolName()) || ctx == null || ctx.getLoginUser() == null
                || ctx.isReadonly() || ctx.isKb() || ctx.getRunId() != null || ctx.getWorkItemId() != null
                || task.getRunId() != null || task.getWorkItemId() != null
                || ctx.getRequestId() == null || ctx.getStreamId() == null || ctx.getConversationId() == null
                || !Objects.equals(task.getId(), plan.planId())
                || !Objects.equals(task.getUserId(), ctx.getUserId())
                || !Objects.equals(task.getOrgId(), ctx.getLoginUser().getOrgId())
                || !Objects.equals(task.getStreamId(), ctx.getStreamId())
                || !Objects.equals(task.getConversationId(), ctx.getConversationId())) {
            return OptionalLong.empty();
        }
        var request = requests.lockById(ctx.getRequestId()).orElse(null);
        if (request == null || request.isCancelled() || !"RUNNING".equals(request.getStatus())
                || request.getRunId() != null || request.getDelegatedTaskId() != null
                || request.getSteeringJson() != null
                || !Objects.equals(request.getUserId(), ctx.getUserId())
                || !Objects.equals(request.getOrgId(), OrgContext.get())
                || !Objects.equals(request.getOrgId(), task.getOrgId())
                || !Objects.equals(request.getStreamId(), ctx.getStreamId())
                || !Objects.equals(request.getConversationId(), ctx.getConversationId())
                || !requestService.live(request)) {
            return OptionalLong.empty();
        }
        var source = messages.findByStreamIdAndRole(ctx.getStreamId(), AgentMessage.ROLE_USER);
        if (source.size() != 1) return OptionalLong.empty();
        var userMessage = source.getFirst();
        if (!Objects.equals(userMessage.getConversationId(), ctx.getConversationId())
                || !Objects.equals(userMessage.getOrgId(), request.getOrgId())
                || userMessage.getId() == null
                || !matches(task.getToolName(), userMessage.getContent(), ctx.getUserId(), plan, drafts.arguments(plan))) {
            return OptionalLong.empty();
        }
        if (tasks.claimOwned(task.getId(), AgentTask.PENDING_CONFIRM, AgentTask.RUNNING,
                LocalDateTime.now(), RuntimeIdentity.ID) == 0) {
            return OptionalLong.empty();
        }
        // claimOwned clears the persistence context. The request lock is held until commit.
        request.setDelegatedTaskId(task.getId());
        requests.save(request);
        return OptionalLong.of(userMessage.getId());
    }

    static boolean matches(String tool, String text, Long userId, AgentActionPlan plan, Map<String, Object> prepared) {
        if (text == null || text.length() > 300 || userId == null || !plan.reversible()
                || plan.total() < 1 || (plan.skipped() != null && !plan.skipped().isEmpty())) return false;
        if (ARTICLE.equals(tool)) {
            var article = PERSONAL_ARTICLE.matcher(text.strip());
            return article.matches() && plan.total() == 1
                    && same(prepared.get("activityId"), article.group(1))
                    && same(prepared.get("authorId"), userId.toString())
                    && "REPORT".equals(prepared.get("articleType"));
        }
        if (!SCHEDULE.equals(tool)) return false;
        var schedule = SCHEDULE_DRAFT.matcher(text.strip());
        if (!schedule.matches()) return false;
        try {
            var startDate = LocalDate.parse(schedule.group(2));
            var endDate = LocalDate.parse(schedule.group(3));
            var startTime = LocalTime.parse(schedule.group(4));
            var endTime = LocalTime.parse(schedule.group(5));
            int headcount = Integer.parseInt(schedule.group(7));
            return !endDate.isBefore(startDate) && ChronoUnit.DAYS.between(startDate, endDate) < 7
                    && endTime.isAfter(startTime) && headcount <= 10
                    && same(prepared.get("activityId"), schedule.group(1))
                    && startDate.equals(LocalDate.parse(Objects.toString(prepared.get("startDate"), "")))
                    && endDate.equals(LocalDate.parse(Objects.toString(prepared.get("endDate"), "")))
                    && startTime.equals(LocalTime.parse(Objects.toString(prepared.get("startTime"), "")))
                    && endTime.equals(LocalTime.parse(Objects.toString(prepared.get("endTime"), "")))
                    && prepared.get("deptIds") instanceof List<?> departments && departments.size() == 1
                    && same(departments.getFirst(), schedule.group(6))
                    && same(prepared.get("peoplePerSlot"), schedule.group(7))
                    && prepared.get("semester") == null
                    && prepared.get("previewFingerprint") instanceof String fingerprint
                    && fingerprint.matches("[a-f0-9]{64}");
        } catch (java.time.DateTimeException invalid) {
            return false;
        }
    }

    private static boolean same(Object stored, String stated) {
        return stated.equals(Objects.toString(stored, ""));
    }
}
