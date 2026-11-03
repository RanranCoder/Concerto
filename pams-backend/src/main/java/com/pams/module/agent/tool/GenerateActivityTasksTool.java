package com.pams.module.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.agent.core.*;
import com.pams.module.activity.dto.TaskRequest;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.service.TaskService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写类工具：从活动生成筹备任务清单（业务设计方案 §3.5）。
 *
 * <p>回答"帮这个活动拉一份筹备任务清单"。按活动类型套用一份<b>规则化的标准任务模板</b>
 * （策划、宣传、物资、场地、签到、总结六类），日期按活动开始日<b>倒排</b>。
 *
 * <p>为什么用规则模板而不是让 LLM 自由发挥：筹备任务是有行业惯例的，
 * 规则模板的产出<b>稳定且可解释</b>；LLM 自由生成的清单常常漏项或离谱。
 * 这里 LLM 的价值在于"理解用户想给哪个活动建清单"，不在于编任务名。
 *
 * <p>权限码 {@code task:manage}；数据级 normal。
 */
@Component
public class GenerateActivityTasksTool implements WriteAgentTool {
    @org.springframework.beans.factory.annotation.Autowired private com.pams.module.agent.service.AgentTaskPlanService planChanges;

    private static final String ARGS_MARK = "__args__:";

    /** 标准筹备任务模板：相对活动开始日的前置天数 + 任务名 + 负责方向 */
    private static final Object[][] TEMPLATE = {
            {21, "活动策划书定稿", "组织部"},
            {18, "场地预约与确认", "主任"},
            {14, "宣传物料设计与制作", "新媒体中心"},
            {10, "线上宣传推送发布", "新媒体中心"},
            {7, "物资清单确认与采购", "文秘部"},
            {5, "报名/签到名单收集", "文秘部"},
            {3, "志愿者分工与培训", "青年科技部"},
            {2, "现场布置方案确认", "组织部"},
            {1, "设备调试与彩排", "青年科技部"},
            {0, "活动现场执行与签到", "文秘部"},
    };

    private final TaskService taskService;
    private final ActivityResolver activityResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public GenerateActivityTasksTool(TaskService taskService, ActivityResolver activityResolver) {
        this.taskService = taskService;
        this.activityResolver = activityResolver;
    }

    @Override
    public String name() { return "generateActivityTasks"; }

    @Override
    public String label() { return "生成任务清单"; }

    @Override
    public String runningLabel() { return "正在生成筹备任务清单…"; }

    @Override
    public String description() {
        return "可用tasks传1–10项明确任务建议，并携带queryActivityTasks获得的planBaseline，走真实计划预览/整批确认/原子保存/回执；已有同名项默认跳过，不静默重复追加。"
                + "未提供tasks时兼容标准筹备任务模板。为活动生成标准筹备任务清单（策划、宣传、物资、场地、签到、总结等），"
                + "任务截止日期按活动开始日自动倒排。适用于'给这个活动拉一份筹备清单'"
                + "'帮我建一下活动任务'等指令。会先生成待确认清单，确认后写入。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "activityId", ToolArgs.prop("integer", "活动 ID，可省略；与 activityName 二选一"),
                "activityName", ToolArgs.prop("string", ActivityResolver.NAME_DESC),
                "planBaseline", ToolArgs.prop("string", "显式建议所需整体任务计划基线"),
                "tasks", Map.of("type","array","maxItems",10,"items",Map.of("type","object","properties",ToolArgs.map(
                        "name",ToolArgs.prop("string","任务名称，必填"),"deptId",ToolArgs.prop("integer","部门ID"),"assigneeUserId",ToolArgs.prop("integer","真实账号ID；不猜负责人"),
                        "startDate",ToolArgs.prop("string","ISO开始日期"),"endDate",ToolArgs.prop("string","ISO结束日期，与开始成对"),"dependsOn",ToolArgs.prop("integer","已有前置任务ID"),
                        "isMilestone",ToolArgs.prop("integer","0或1"),"priority",ToolArgs.prop("integer","优先级"),"description",ToolArgs.prop("string","任务说明")),"required",List.of("name"),"additionalProperties",false)),
                "onlyMissing", ToolArgs.prop("boolean", "跳过已存在的同名任务，默认 true")
        ), List.of());
    }

    @Override
    public List<String> requiredPermissions() { return List.of("task:manage"); }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        ActivityResolver.Resolution res =
                activityResolver.resolve(ToolArgs.lng(args, "activityId"), ToolArgs.str(args, "activityName"));
        if (res.failed()) return ToolResult.failed(res.message());
        Long activityId = res.activity().getId();
        if(args!=null && args.get("tasks")!=null) {
            if(!(args.get("tasks") instanceof List<?> list))return ToolResult.failed("tasks须为任务建议数组");
            try{return planChanges.prepareBatch(ctx,activityId,ToolArgs.str(args,"planBaseline"),list,!Boolean.FALSE.equals(args.get("onlyMissing")));}
            catch(Exception e){return ToolResult.failed(e.getMessage()==null?"任务建议无效":e.getMessage());}
        }
        boolean onlyMissing = args == null || !Boolean.FALSE.equals(args.get("onlyMissing"));

        Activity activity = res.activity();

        LocalDate base = activity.getStartDate() != null ? activity.getStartDate() : LocalDate.now().plusDays(14);

        // 已存在任务名（用于跳过）
        java.util.Set<String> existing = new java.util.HashSet<>();
        try {
            List<com.pams.module.activity.entity.Task> current = taskService.listByActivity(activityId);
            if (current != null) for (var t : current) {
                if (t.getName() != null) existing.add(t.getName().trim());
            }
        } catch (Exception ignored) {
            // 读不到就当没有，由用户确认时判断
        }

        List<AgentActionPlan.PlanItem> items = new ArrayList<>();
        List<AgentActionPlan.SkippedItem> skipped = new ArrayList<>();
        List<Map<String, Object>> toCreate = new ArrayList<>();

        for (Object[] row : TEMPLATE) {
            int daysBefore = (int) row[0];
            String name = (String) row[1];
            String dept = (String) row[2];
            LocalDate due = base.minusDays(daysBefore);

            if (onlyMissing && existing.contains(name)) {
                skipped.add(new AgentActionPlan.SkippedItem(name, due + " 截止", "已存在同名任务"));
                continue;
            }
            items.add(new AgentActionPlan.PlanItem(null, name, due + " 截止 · " + dept));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("due", due.toString());
            toCreate.add(m);
        }

        if (items.isEmpty()) {
            return ToolResult.ok("该活动已有完整的筹备任务清单，无需重复生成。");
        }
        if(planChanges!=null){
            try{var suggestions=new ArrayList<Map<String,Object>>();for(var entry:toCreate){LocalDate due=LocalDate.parse(entry.get("due").toString());suggestions.add(ToolArgs.map("name",entry.get("name"),"startDate",due.isBefore(LocalDate.now())?due.toString():LocalDate.now().toString(),"endDate",due.toString(),"priority",1,"description","由灵思按标准筹备流程建议"));}
                return planChanges.prepareBatch(ctx,activityId,com.pams.module.agent.service.AgentTaskPlanService.baseline(taskService.listByActivity(activityId)),suggestions,onlyMissing);
            }catch(Exception e){return ToolResult.failed(e.getMessage()==null?"任务计划预览失败":e.getMessage());}
        }

        List<String> detailLines = new ArrayList<>();
        detailLines.add("活动：" + activity.getName());
        detailLines.add("基准日：" + base + "（活动开始日），任务截止日按此倒排");
        detailLines.add("模板：标准筹备流程（" + TEMPLATE.length + " 项）");
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("activityId", activityId);
        exec.put("tasks", toCreate);
        detailLines.add(ARGS_MARK + writeJson(exec));

        String summary = activity.getName() + " · 筹备任务清单（" + items.size() + " 项）";
        AgentActionPlan plan = new AgentActionPlan(null, summary, items.size(), items, skipped,
                detailLines, true, null);

        return ToolResult.plan(
                "已按标准筹备流程整理出 " + items.size() + " 项任务"
                        + (skipped.isEmpty() ? "" : "，" + skipped.size() + " 项因已存在而跳过")
                        + "。确认后写入任务列表。",
                plan);
    }

    @Override
    public CommitResult commit(ToolContext ctx, AgentActionPlan plan) {
        Map<String, Object> exec = readArgs(plan);
        if (exec == null) return CommitResult.failed("无法还原任务参数，请重新发起。");
        if(exec.containsKey("operationId") && planChanges!=null) {
            try{return planChanges.commitGenerated(ctx,plan);}catch(com.pams.common.BizException e){return CommitResult.failed(e.getMessage());}
            catch(Exception e){return new CommitResult("RESULT_UNKNOWN",0,0,"任务建议提交结果待核对，不要重复执行。",Map.of(),List.of());}
        }

        Long activityId = lng(exec.get("activityId"));
        if (activityId == null) return CommitResult.failed("活动 ID 缺失，请重新发起。");

        Object raw = exec.get("tasks");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return CommitResult.failed("任务清单为空。");
        }

        int ok = 0, failed = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                failed++;
                continue;
            }
            try {
                TaskRequest req = new TaskRequest();
                req.setActivityId(activityId);
                req.setName(str(m.get("name")));
                req.setDeptId(null);
                req.setAssignee(null);
                req.setStartDate(LocalDate.now());
                if (m.get("due") != null) req.setEndDate(LocalDate.parse(String.valueOf(m.get("due"))));
                req.setIsMilestone(0);
                req.setProgress(0);
                req.setPriority(1);
                req.setStatus("TODO");
                req.setDescription("由灵思按标准筹备流程自动生成");
                taskService.create(req, ctx.getUserId());
                ok++;
            } catch (Exception e) {
                failed++;
            }
        }

        List<Map<String, Object>> refs = new ArrayList<>();
        refs.add(ToolArgs.map("type", "activity", "id", activityId, "label", "活动任务",
                "link", "/activity/" + activityId + "/tasks"));

        if (ok == 0) {
            return new CommitResult("FAILED", 0, failed, "任务创建失败（" + failed + " 项均未成功）。", Map.of(), refs);
        }
        if (failed > 0) {
            return new CommitResult("PARTIAL", ok, failed,
                    "已创建 " + ok + " 项任务，" + failed + " 项失败。",
                    ToolArgs.map("created", ok, "failed", failed), refs);
        }
        return new CommitResult("SUCCESS", ok, 0,
                "已创建 " + ok + " 项筹备任务，可在活动任务页查看与调整。",
                ToolArgs.map("created", ok), refs);
    }

    private String writeJson(Map<String, Object> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readArgs(AgentActionPlan plan) {
        if (plan == null || plan.detailLines() == null) return null;
        for (String line : plan.detailLines()) {
            if (line != null && line.startsWith(ARGS_MARK)) {
                try {
                    return mapper.readValue(line.substring(ARGS_MARK.length()), Map.class);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private Long lng(Object o) {
        if (o == null) return null;
        try {
            return Long.valueOf(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }
}
