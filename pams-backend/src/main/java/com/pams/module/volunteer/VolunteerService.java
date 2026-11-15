package com.pams.module.volunteer;

import static com.pams.module.org.service.PackWorkspace.changed;
import static com.pams.module.org.service.PackWorkspace.text;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.module.activity.dto.ActivityRequest;
import com.pams.module.activity.service.ActivityService;
import com.pams.module.org.service.DomainPacks;
import com.pams.module.org.service.PackWorkspace;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VolunteerService {
    private static final String RECORD_FROM = "FROM volunteer_record r JOIN volunteer_activity v "
            + "ON v.org_id=r.org_id AND v.id=r.volunteer_activity_id JOIN member m ON m.org_id=r.org_id AND m.id=r.member_id "
            + "WHERE r.org_id=? AND r.volunteer_activity_id=?";
    private static final String RECORD_FIELDS = "r.id,r.member_id,m.name AS member_name,r.student_no,"
            + "r.signin_at,r.service_minutes,r.status,r.decision,r.reviewed_at,r.version";

    private final JdbcTemplate db;
    private final PackWorkspace workspace;
    private final ActivityService activities;

    public VolunteerService(JdbcTemplate db, PackWorkspace workspace, ActivityService activities) {
        this.db = db;
        this.workspace = workspace;
        this.activities = activities;
    }

    public record ActivityInput(Long activityId, String name, LocalDate date, @Min(1) @Max(10080) int maxMinutes) {}
    public record Registration(@NotNull @Positive Long memberId) {}
    public record Submission(@NotNull Integer version, @Min(1) @Max(10080) int minutes) {}
    public record Review(@NotNull Integer version, @NotNull Boolean approved, String decision) {}

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> activities(int page) {
        return workspace.page("r.id,r.activity_id,r.max_minutes,a.name,a.start_date,a.status,a.deleted AS activity_deleted,"
                + "(SELECT COUNT(*) FROM volunteer_record v WHERE v.org_id=r.org_id AND v.volunteer_activity_id=r.id) AS registered,"
                + "(SELECT COUNT(*) FROM volunteer_record v WHERE v.org_id=r.org_id AND v.volunteer_activity_id=r.id AND v.status='PENDING') AS pending,"
                + "(SELECT COALESCE(SUM(v.service_minutes),0) FROM volunteer_record v WHERE v.org_id=r.org_id "
                + "AND v.volunteer_activity_id=r.id AND v.status='APPROVED') AS approved_minutes",
                "FROM volunteer_activity r JOIN activity a ON a.org_id=r.org_id AND a.id=r.activity_id WHERE r.org_id=?", page, workspace.org());
    }

    @Transactional
    public void addActivity(ActivityInput input, boolean createAllowed) {
        workspace.lockEnabled(DomainPacks.VOLUNTEER);
        long org = workspace.org();
        Long activityId = input.activityId();
        if (activityId == null) {
            if (!createAllowed) throw new BizException(403, "当前账号没有创建活动权限");
            if (input.date() == null) throw new BizException(400, "请选择活动日期");
            var activity = new ActivityRequest();
            activity.setName(text(input.name(), 100, "活动名称", true));
            activity.setType("VOLUNTEER");
            activity.setStartDate(input.date());
            activity.setEndDate(input.date());
            activityId = activities.create(activity);
        } else workspace.requireActivity(activityId);
        if (db.queryForObject("SELECT COUNT(*) FROM volunteer_activity WHERE org_id=? AND activity_id=?", Integer.class, org, activityId) > 0) {
            throw new BizException(409, "该活动已关联志愿服务");
        }
        db.update("INSERT INTO volunteer_activity(org_id,activity_id,max_minutes) VALUES(?,?,?)", org, activityId, input.maxMinutes());
    }

    private Map<String, Object> activity(long id) {
        return workspace.one("SELECT v.activity_id,v.max_minutes,a.deleted AS activity_deleted,a.name "
                + "FROM volunteer_activity v JOIN activity a ON a.org_id=v.org_id AND a.id=v.activity_id "
                + "WHERE v.org_id=? AND v.id=?", "志愿活动不存在", workspace.org(), id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> details(long id, int page, String status) {
        var activity = activity(id);
        if (status != null && !List.of("REGISTERED", "PENDING", "RETURNED", "APPROVED").contains(status)) {
            throw new BizException(400, "服务记录状态无效");
        }
        String select = RECORD_FIELDS + ",CASE WHEN EXISTS (SELECT 1 FROM signin s WHERE s.org_id=r.org_id "
                + "AND s.activity_id=v.activity_id AND s.student_no=r.student_no AND s.sign_time IS NOT NULL) THEN 1 ELSE 0 END AS has_signin";
        PageResult<Map<String, Object>> records = status == null ? workspace.page(select, RECORD_FROM, page, workspace.org(), id)
                : workspace.page(select, RECORD_FROM + " AND r.status=?", page, workspace.org(), id, status);
        return Map.of("activity", activity, "records", records);
    }

    @Transactional
    public void register(long id, Registration input) {
        workspace.lockEnabled(DomainPacks.VOLUNTEER);
        requireActiveActivity(activity(id));
        long org = workspace.org();
        var member = workspace.one("SELECT student_no FROM member WHERE org_id=? AND id=? AND deleted=0 AND status='ACTIVE'",
                "未找到在册成员", org, input.memberId());
        String studentNo = (String) member.get("studentNo");
        if (studentNo == null || studentNo.isBlank()) throw new BizException(400, "请先在成员资料中补齐学号，以便核对签到");
        studentNo = studentNo.trim();
        if (db.queryForObject("SELECT COUNT(*) FROM volunteer_record WHERE org_id=? AND volunteer_activity_id=? "
                + "AND (member_id=? OR student_no=?)", Integer.class, org, id, input.memberId(), studentNo) > 0) {
            throw new BizException(409, "该成员或学号已报名此活动");
        }
        db.update("INSERT INTO volunteer_record(org_id,volunteer_activity_id,member_id,student_no) VALUES(?,?,?,?)",
                org, id, input.memberId(), studentNo);
    }

    @Transactional
    public void submit(long id, Submission input, long actor) {
        workspace.lockEnabled(DomainPacks.VOLUNTEER);
        long org = workspace.org();
        var row = workspace.one("SELECT r.student_no,v.activity_id,v.max_minutes,a.deleted AS activity_deleted "
                + "FROM volunteer_record r JOIN volunteer_activity v ON v.org_id=r.org_id AND v.id=r.volunteer_activity_id "
                + "JOIN activity a ON a.org_id=v.org_id AND a.id=v.activity_id WHERE r.org_id=? AND r.id=?",
                "服务记录不存在", org, id);
        requireActiveActivity(row);
        if (input.minutes() > ((Number) row.get("maxMinutes")).intValue()) throw new BizException(400, "服务时长超过该活动的核定上限");
        var signs = workspace.rows("SELECT id,sign_time FROM signin WHERE org_id=? AND activity_id=? AND student_no=? "
                + "AND sign_time IS NOT NULL ORDER BY sign_time,id LIMIT 1 FOR UPDATE", org, row.get("activityId"), row.get("studentNo"));
        if (signs.isEmpty()) throw new BizException(400, "未找到与报名学号匹配的签到记录");
        var sign = signs.getFirst();
        changed(db.update("UPDATE volunteer_record SET signin_id=?,signin_at=?,service_minutes=?,submitted_by=?,"
                + "submitted_at=CURRENT_TIMESTAMP,status='PENDING',version=version+1 WHERE org_id=? AND id=? "
                + "AND version=? AND status IN ('REGISTERED','RETURNED')", sign.get("id"), sign.get("signTime"),
                input.minutes(), actor, org, id, input.version()));
    }

    @Transactional
    public void review(long id, Review input, long actor) {
        workspace.lockEnabled(DomainPacks.VOLUNTEER);
        long org = workspace.org();
        String decision = text(input.decision(), 4000, "审核意见", !input.approved());
        if (input.approved()) {
            workspace.one("SELECT r.id FROM volunteer_record r JOIN volunteer_activity v "
                    + "ON v.org_id=r.org_id AND v.id=r.volunteer_activity_id JOIN signin s ON s.org_id=r.org_id "
                    + "AND s.id=r.signin_id AND s.activity_id=v.activity_id AND s.student_no=r.student_no AND s.sign_time=r.signin_at "
                    + "WHERE r.org_id=? AND r.id=? FOR UPDATE", "核定所依据的签到已变化，请退回后重新核定", org, id);
        }
        changed(db.update("UPDATE volunteer_record SET status=?,decision=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,version=version+1 "
                + "WHERE org_id=? AND id=? AND version=? AND status='PENDING'",
                input.approved() ? "APPROVED" : "RETURNED", decision, actor, org, id, input.version()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> approvedRecords(long id) {
        activity(id);
        return workspace.rows("SELECT " + RECORD_FIELDS + " " + RECORD_FROM + " AND r.status='APPROVED' ORDER BY r.id",
                workspace.org(), id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        return workspace.one("SELECT COUNT(DISTINCT a.id) AS activities,COUNT(r.id) AS registrations,"
                + "COALESCE(SUM(CASE WHEN r.status='PENDING' THEN 1 ELSE 0 END),0) AS pending,"
                + "COALESCE(SUM(CASE WHEN r.status='APPROVED' THEN r.service_minutes ELSE 0 END),0) AS approved_minutes "
                + "FROM volunteer_activity a LEFT JOIN volunteer_record r ON r.org_id=a.org_id AND r.volunteer_activity_id=a.id "
                + "WHERE a.org_id=?", "没有志愿服务数据", workspace.org());
    }

    private void requireActiveActivity(Map<String, Object> activity) {
        if (((Number) activity.get("activityDeleted")).intValue() != 0) throw new BizException(409, "关联活动已删除");
    }
}
