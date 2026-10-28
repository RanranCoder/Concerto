package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.dto.TaskPlan;
import com.pams.module.activity.dto.TaskRequest;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.entity.Task;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.repository.TaskRepository;
import com.pams.module.activity.service.TaskService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Actual Hibernate versions, tenant predicates and soft-delete restoration. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TaskPlanServiceTest {
    @Autowired TaskService service;
    @Autowired ActivityRepository activities;
    @Autowired TaskRepository tasks;
    @Autowired EntityManager entities;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.pams.repository.DepartmentRepository departments;

    Activity activity() {
        Activity a = new Activity();
        a.setName("任务计划事务验证");a.setType("OTHER");a.setStatus(ActivityStatus.PLANNING);a.setDeleted(0);
        return activities.saveAndFlush(a);
    }

    TaskRequest request(Long activityId, String name, String start, String end, Long dep) {
        TaskRequest r = new TaskRequest();r.setActivityId(activityId);r.setName(name);
        r.setStartDate(start == null ? null : LocalDate.parse(start));
        r.setEndDate(end == null ? null : LocalDate.parse(end));r.setDependsOn(dep);r.setProgress(0);
        return r;
    }

    TaskRequest edit(Task t, String end) {
        TaskRequest r = request(t.getActivityId(),t.getName(),t.getStartDate().toString(),end,t.getDependsOn());
        r.setExpectedRevision(t.getRevision());return r;
    }

    @Test void cascadePreviewDoesNotWrite_thenSavesAndUndoesWholeChain() {
        Long id = activity().getId();
        Task a = service.create(request(id,"设备确认","2026-10-20","2026-10-21",null),null);
        Task b = service.create(request(id,"会场布置","2026-10-22","2026-10-23",a.getId()),null);
        Task c = service.create(request(id,"活动节点","2026-10-24","2026-10-24",b.getId()),null);
        tasks.flush();
        TaskRequest proposal = edit(a,"2026-10-23");
        var preview = service.preview(new TaskPlan.Request(a.getId(),proposal,null,false));
        assertThat(preview.impacts()).hasSize(2);
        assertThat(b.getStartDate()).isEqualTo(LocalDate.parse("2026-10-22"));
        var saved = service.savePlan(new TaskPlan.Request(a.getId(),proposal,preview.token(),true),null);
        assertThat(b.getStartDate()).isEqualTo(LocalDate.parse("2026-10-24"));
        assertThat(b.getEndDate()).isEqualTo(LocalDate.parse("2026-10-25"));
        assertThat(c.getStartDate()).isEqualTo(LocalDate.parse("2026-10-26"));
        assertThat(saved.undo().entries()).hasSize(3);
        service.restore(saved.undo());entities.flush();entities.clear();
        assertThat(tasks.findById(a.getId()).orElseThrow().getEndDate()).isEqualTo(LocalDate.parse("2026-10-21"));
        assertThat(tasks.findById(b.getId()).orElseThrow().getStartDate()).isEqualTo(LocalDate.parse("2026-10-22"));
        assertThat(tasks.findById(c.getId()).orElseThrow().getStartDate()).isEqualTo(LocalDate.parse("2026-10-24"));
    }

    @Test void onlyCurrentSaveKeepsSuccessorDates() {
        Long id=activity().getId();Task a=service.create(request(id,"前置","2026-10-20","2026-10-21",null),null);
        Task b=service.create(request(id,"后续","2026-10-22","2026-10-23",a.getId()),null);tasks.flush();
        var proposal=edit(a,"2026-10-25");var p=service.preview(new TaskPlan.Request(a.getId(),proposal,null,false));
        service.savePlan(new TaskPlan.Request(a.getId(),proposal,p.token(),false),null);
        assertThat(b.getStartDate()).isEqualTo(LocalDate.parse("2026-10-22"));
    }

    @Test void changedSuccessorInvalidatesPreview_andUndoWillNotOverwriteLaterProgress() {
        Long id=activity().getId();Task a=service.create(request(id,"前置","2026-10-20","2026-10-21",null),null);
        Task b=service.create(request(id,"后续","2026-10-22","2026-10-23",a.getId()),null);tasks.flush();
        var proposal=edit(a,"2026-10-25");var p=service.preview(new TaskPlan.Request(a.getId(),proposal,null,false));
        service.updateProgress(b.getId(),50,b.getRevision());
        assertThatThrownBy(()->service.savePlan(new TaskPlan.Request(a.getId(),proposal,p.token(),true),null))
                .isInstanceOf(BizException.class).hasMessageContaining("重新预览");
        assertThat(a.getEndDate()).isEqualTo(LocalDate.parse("2026-10-21"));
    }

    @Test void undoRejectsLaterEditsBeforeChangingAnyRow() {
        Long id=activity().getId();Task a=service.create(request(id,"前置","2026-10-20","2026-10-21",null),null);
        Task b=service.create(request(id,"后续","2026-10-22","2026-10-23",a.getId()),null);tasks.flush();
        var proposal=edit(a,"2026-10-25");var p=service.preview(new TaskPlan.Request(a.getId(),proposal,null,false));
        var saved=service.savePlan(new TaskPlan.Request(a.getId(),proposal,p.token(),true),null);
        service.updateProgress(b.getId(),75,b.getRevision());
        assertThatThrownBy(()->service.restore(saved.undo())).isInstanceOf(BizException.class).hasMessageContaining("不会覆盖");
        assertThat(a.getEndDate()).isEqualTo(LocalDate.parse("2026-10-25"));assertThat(b.getProgress()).isEqualTo(75);
    }

    @Test void deletedTaskCanBeRestoredButNotAcrossTenants() {
        Long id=activity().getId();Task a=service.create(request(id,"可删除",null,null,null),null);tasks.flush();
        var saved=service.deleteWithUndo(a.getId(),a.getRevision());entities.clear();
        assertThat(tasks.findById(a.getId())).isEmpty();
        service.restore(saved.undo());entities.clear();assertThat(tasks.findById(a.getId())).isPresent();
        jdbc.update("UPDATE task SET org_id=999999 WHERE id=?",a.getId());entities.clear();
        assertThatThrownBy(()->service.restore(saved.undo())).isInstanceOf(BizException.class).hasMessageContaining("不属于");
    }

    @Test void dependenciesCannotCycleCrossActivityOrDangleAfterDeletion() {
        Long id=activity().getId();Task a=service.create(request(id,"前置","2026-10-20","2026-10-21",null),null);
        service.create(request(id,"后续","2026-10-22","2026-10-23",a.getId()),null);tasks.flush();
        assertThatThrownBy(()->service.delete(a.getId())).hasMessageContaining("先解除");
    }

    @Test void cycleAndForeignPredecessorsAreRejectedDuringPreview() {
        Long id=activity().getId();Task a=service.create(request(id,"前置","2026-10-20","2026-10-21",null),null);
        Task b=service.create(request(id,"后续","2026-10-22","2026-10-23",a.getId()),null);tasks.flush();
        var r=edit(a,"2026-10-21");r.setDependsOn(b.getId());
        assertThatThrownBy(()->service.preview(new TaskPlan.Request(a.getId(),r,null,false))).hasMessageContaining("循环");
    }

    @Test void foreignPredecessorIsRejected() {
        Long id=activity().getId();Task a=service.create(request(id,"其他活动",null,null,null),null);tasks.flush();
        Long other=activity().getId();
        assertThatThrownBy(()->service.create(request(other,"错误前置",null,null,a.getId()),null)).hasMessageContaining("不属于当前活动");
    }

    @Test void archivedActivityIsReadOnly_andProgressSynchronizesStatus() {
        Activity a=activity();Task t=service.create(request(a.getId(),"待办",null,null,null),null);tasks.flush();
        service.updateProgress(t.getId(),100,t.getRevision());assertThat(t.getStatus()).isEqualTo(Task.TaskStatus.DONE);
        a.setStatus(ActivityStatus.ARCHIVED);activities.saveAndFlush(a);
        assertThatThrownBy(()->service.updateProgress(t.getId(),0)).hasMessageContaining("只读");
    }

    @Test void unplannedAndMilestoneTasksHaveValidDates() {
        Long id=activity().getId();Task t=service.create(request(id,"未排期",null,null,null),null);assertThat(t.getStartDate()).isNull();
        var r=request(id,"里程碑","2026-10-20","2026-10-23",null);r.setIsMilestone(1);
        Task milestone=service.create(r,null);assertThat(milestone.getEndDate()).isEqualTo(milestone.getStartDate());
        assertThatThrownBy(()->service.create(request(id,"半个日期","2026-10-20",null,null),null)).hasMessageContaining("同时");
    }

    @Test void assignmentCannotReferenceAnotherOrganizationsDepartment() {
        var department = new com.pams.entity.Department();
        department.setOrgId(999999L);department.setName("其他组织的部门");
        Long departmentId = departments.saveAndFlush(department).getId();
        var request = request(activity().getId(),"越界分工",null,null,null);
        request.setDeptId(departmentId);
        assertThatThrownBy(()->service.create(request,null)).hasMessageContaining("不属于当前组织");
    }

    @Test void editingDescriptionDoesNotRescheduleExistingConflicts() {
        Long id=activity().getId();Task a=service.create(request(id,"前置","2026-10-20","2026-10-23",null),null);
        service.create(request(id,"待协调后续","2026-10-22","2026-10-23",a.getId()),null);tasks.flush();
        var req=edit(a,"2026-10-23");req.setDescription("只更新准备要求");
        var preview=service.preview(new TaskPlan.Request(a.getId(),req,null,false));
        assertThat(preview.impacts()).isEmpty();
    }

    @Test void legacyOrphanedDependenciesCanBeRepairedOneTaskAtATime() {
        Long id=activity().getId();Task parent=service.create(request(id,"历史前置","2026-10-20","2026-10-21",null),null);
        Task first=service.create(request(id,"待修复一","2026-10-22","2026-10-23",parent.getId()),null);
        Task second=service.create(request(id,"待修复二","2026-10-22","2026-10-23",parent.getId()),null);
        parent.setDeleted(1);tasks.saveAndFlush(parent);entities.clear();
        var repaired=request(id,"已修复一","2026-10-22","2026-10-23",null);
        service.update(first.getId(),repaired);
        assertThat(tasks.findById(first.getId()).orElseThrow().getDependsOn()).isNull();
        service.update(second.getId(),request(id,"已修复二","2026-10-22","2026-10-23",null));
        assertThat(tasks.findById(second.getId()).orElseThrow().getDependsOn()).isNull();
    }
}
