package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.controller.AgendaController;
import com.pams.module.activity.dto.AgendaRequest;
import com.pams.module.activity.dto.ScoreRecordRequest;
import com.pams.module.activity.dto.ScoreRuleRequest;
import com.pams.module.activity.dto.SeatRequest;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.service.ActivityService;
import com.pams.module.activity.service.ScoreService;
import com.pams.module.activity.service.SeatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercise the actual Hibernate queries, including tenant and soft-delete predicates. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ActivitySummaryRepositoryTest {
    @Autowired ActivityRepository repository;
    @Autowired ActivityService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgendaController agendas;
    @Autowired SeatService seats;
    @Autowired ScoreService scores;

    private Activity activity(String name, String date, String organizer) {
        Activity activity = new Activity();
        activity.setName(name);
        activity.setType("OTHER");
        activity.setStartDate(LocalDate.parse(date));
        activity.setEndDate(LocalDate.parse(date));
        activity.setOrganizer(organizer);
        activity.setLeader("V6查询负责人");
        activity.setStatus(ActivityStatus.PLANNING);
        activity.setDeleted(0);
        return repository.saveAndFlush(activity);
    }

    @Test
    void summariesExcludeDeletedAndOtherTenantRows() {
        Activity activity = activity("V6汇总测试", "2026-10-20", "V6组织单位");
        Long id = activity.getId();
        jdbc.update("INSERT INTO task(activity_id,name,start_date,end_date,progress,deleted,org_id) VALUES (?, '完成任务','2026-10-20','2026-10-20',100,0,1)", id);
        jdbc.update("INSERT INTO task(activity_id,name,start_date,end_date,progress,deleted,org_id) VALUES (?, '进行任务','2026-10-20','2026-10-20',50,0,1)", id);
        jdbc.update("INSERT INTO task(activity_id,name,start_date,end_date,progress,deleted,org_id) VALUES (?, '已删任务','2026-10-20','2026-10-20',100,1,1)", id);
        jdbc.update("INSERT INTO task(activity_id,name,start_date,end_date,progress,deleted,org_id) VALUES (?, '其他组织','2026-10-20','2026-10-20',100,0,999999)", id);
        jdbc.update("INSERT INTO material(activity_id,name,biz_type,deleted,org_id) VALUES (?, '有效材料','OTHER',0,1)", id);
        jdbc.update("INSERT INTO material(activity_id,name,biz_type,deleted,org_id) VALUES (?, '已删材料','OTHER',1,1)", id);
        jdbc.update("INSERT INTO material(activity_id,name,biz_type,deleted,org_id) VALUES (?, '其他组织','OTHER',0,999999)", id);

        List<Object[]> tasks = repository.taskCounts(List.of(id));
        assertThat(tasks).hasSize(1);
        assertThat(((Number) tasks.getFirst()[1]).longValue()).isEqualTo(2);
        assertThat(((Number) tasks.getFirst()[2]).longValue()).isEqualTo(1);
        assertThat(((Number) repository.materialCounts(List.of(id)).getFirst()[1]).longValue()).isEqualTo(1);

        var result = service.page("V6汇总测试", null, null, "V6组织单位", "asc", 1, 10);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords().getFirst()).containsEntry("taskCount", 2L)
                .containsEntry("completedTaskCount", 1L).containsEntry("materialCount", 1L);
    }

    @Test
    void organizerFilterLeaderSearchAndDateSortingUseTheDatabase() {
        Activity later = activity("V6日期较晚", "2026-10-22", "V6筛选单位");
        Activity earlier = activity("V6日期较早", "2026-10-20", "V6筛选单位");
        Activity deleted = activity("V6已删活动", "2026-10-19", "V6隐藏单位");
        deleted.setDeleted(1);
        repository.saveAndFlush(deleted);
        var ascending = service.page("V6查询负责人", null, null, "V6筛选单位", "asc", 1, 10);
        assertThat(ascending.getRecords()).extracting(row -> row.get("id")).containsExactly(earlier.getId(), later.getId());
        var descending = service.page("V6查询负责人", null, null, "V6筛选单位", "desc", 1, 10);
        assertThat(descending.getRecords()).extracting(row -> row.get("id")).containsExactly(later.getId(), earlier.getId());
        assertThat(repository.organizers()).contains("V6筛选单位").doesNotContain("V6隐藏单位");
    }

    @Test
    @WithMockUser(authorities = "agenda:manage")
    void childWritesRejectForeignAndDeletedParents() {
        var local = activity("本组织活动", "2026-11-10", "本组织");
        jdbc.update("INSERT INTO activity(name,type,status,deleted,org_id) VALUES ('外组织活动','OTHER','PLANNING',0,999999)");
        Long foreignId = jdbc.queryForObject("SELECT id FROM activity WHERE name='外组织活动'", Long.class);
        var deleted = activity("已删除活动", "2026-11-10", "本组织");
        service.delete(deleted.getId());
        repository.flush();

        var agenda = new AgendaRequest();
        agenda.setTitle("准备");
        agenda.setStepNo(1);
        var seat = new SeatRequest();
        seat.setZone("A");
        var rule = new ScoreRuleRequest();
        rule.setDimensionName("表现");
        rule.setFullMarks(10);
        var record = new ScoreRecordRequest();
        record.setTeamName("第一组");
        agenda.setActivityId(local.getId());
        seat.setActivityId(local.getId());
        rule.setActivityId(local.getId());
        record.setActivityId(local.getId());
        Long ruleId = scores.createRule(rule);
        record.setDimensionScores("{\"" + ruleId + "\":10}");
        assertThat(agendas.create(agenda).getData().getOrgId()).isEqualTo(local.getOrgId());
        assertThat(seats.create(seat).getOrgId()).isEqualTo(local.getOrgId());
        assertThat(scores.createRecord(record)).isNotNull();

        for (Long id : List.of(foreignId, deleted.getId())) {
            agenda.setActivityId(id);
            seat.setActivityId(id);
            rule.setActivityId(id);
            record.setActivityId(id);
            assertThatThrownBy(() -> agendas.create(agenda)).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> seats.create(seat)).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> scores.createRule(rule)).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> scores.createRecord(record)).isInstanceOf(BizException.class);
            for (String table : List.of("activity_agenda", "seat_map", "score_rule", "score_record")) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE activity_id = ?", Long.class, id))
                        .isZero();
            }
        }
    }
}
