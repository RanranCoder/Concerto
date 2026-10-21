package com.pams.module.activity;

import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.service.ActivityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercise the actual Hibernate queries, including tenant and soft-delete predicates. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ActivitySummaryRepositoryTest {
    @Autowired ActivityRepository repository;
    @Autowired ActivityService service;
    @Autowired JdbcTemplate jdbc;

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
}
