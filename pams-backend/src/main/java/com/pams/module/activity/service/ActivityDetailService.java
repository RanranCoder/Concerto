package com.pams.module.activity.service;

import com.pams.module.activity.entity.ActivityPlan;
import com.pams.module.activity.entity.SeatMap;
import com.pams.module.activity.repository.ActivityAgendaRepository;
import com.pams.module.activity.repository.ScoreRecordRepository;
import com.pams.module.activity.repository.ScoreRuleRepository;
import com.pams.module.activity.repository.SeatMapRepository;
import com.pams.module.activity.repository.SigninRepository;
import com.pams.module.activity.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 活动详情聚合：把策划书 / 议程 / 座位表 / 评分 / 签到 / 任务聚合为一份 JSON。
 * 供 Task 14 活动详情页使用。
 */
@Service
public class ActivityDetailService {
    private final ActivityService activityService;
    private final PlanService planService;
    private final ActivityAgendaRepository agendaRepository;
    private final SeatMapRepository seatMapRepository;
    private final ScoreRuleRepository scoreRuleRepository;
    private final ScoreRecordRepository scoreRecordRepository;
    private final SigninRepository signinRepository;
    private final TaskRepository taskRepository;

    public ActivityDetailService(ActivityService activityService,
                                 PlanService planService,
                                 ActivityAgendaRepository agendaRepository,
                                 SeatMapRepository seatMapRepository,
                                 ScoreRuleRepository scoreRuleRepository,
                                 ScoreRecordRepository scoreRecordRepository,
                                 SigninRepository signinRepository,
                                 TaskRepository taskRepository) {
        this.activityService = activityService;
        this.planService = planService;
        this.agendaRepository = agendaRepository;
        this.seatMapRepository = seatMapRepository;
        this.scoreRuleRepository = scoreRuleRepository;
        this.scoreRecordRepository = scoreRecordRepository;
        this.signinRepository = signinRepository;
        this.taskRepository = taskRepository;
    }

    public Map<String, Object> aggregate(Long activityId) {
        // plan：latest + status
        ActivityPlan latest = planService.latest(activityId);
        Map<String, Object> planVo = new LinkedHashMap<>();
        planVo.put("latest", latest);
        planVo.put("status", latest == null ? null : latest.getStatus().name());

        // score：rules + records
        Map<String, Object> scoreVo = new LinkedHashMap<>();
        scoreVo.put("rules", scoreRuleRepository.findByActivityIdOrderBySortOrderAsc(activityId));
        scoreVo.put("records", scoreRecordRepository.findByActivityId(activityId));

        // seatZones：按 zone 分组（与 GET /api/seats 一致），组内按 rowNo/colNo 升序
        Map<String, List<SeatMap>> seatZones = seatMapRepository
                .findByActivityIdOrderByZoneAscRowNoAscColNoAsc(activityId)
                .stream()
                .collect(Collectors.groupingBy(SeatMap::getZone, LinkedHashMap::new, Collectors.toList()));

        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("activity", activityService.detail(activityId));
        vo.put("plan", planVo);
        vo.put("agendas", agendaRepository.findByActivityIdOrderByStepNoAsc(activityId));
        vo.put("seatZones", seatZones);
        vo.put("score", scoreVo);
        vo.put("signinCount", signinRepository.countByActivityId(activityId));
        // Task 12 甘特图任务接入详情聚合（Task 27 补查询，替代硬编码 List.of()）
        vo.put("tasks", taskRepository.findByActivityIdOrderByStartDateAsc(activityId));
        return vo;
    }
}
