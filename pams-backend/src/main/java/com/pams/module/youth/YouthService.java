package com.pams.module.youth;

import static com.pams.module.org.service.PackWorkspace.changed;
import static com.pams.module.org.service.PackWorkspace.text;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.module.activity.dto.ActivityRequest;
import com.pams.module.activity.service.ActivityService;
import com.pams.module.org.service.DomainPacks;
import com.pams.module.org.service.PackWorkspace;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class YouthService {
    private static final Set<String> LIFE_KINDS = Set.of("ASSEMBLY", "COMMITTEE", "GROUP", "LESSON", "THEME_DAY");
    private static final Set<String> CASE_KINDS = Set.of("EVALUATION", "RECOMMENDATION");
    private static final String CASE_FROM = "FROM youth_case r JOIN youth_member y ON y.org_id=r.org_id AND y.id=r.youth_member_id "
            + "JOIN member m ON m.org_id=y.org_id AND m.id=y.member_id "
            + "LEFT JOIN sys_department d ON d.org_id=r.org_id AND d.id=r.branch_id WHERE r.org_id=?";
    private static final String CASE_FIELDS = "r.id,r.youth_member_id,r.year_no,r.kind,r.criteria,r.submission,r.status,r.decision,r.reviewed_at,"
            + "r.received_at,r.version,m.name AS member_name,d.name AS branch_name";
    private static final String PARTY_MATCHES = "FROM youth_case c JOIN youth_member y ON y.org_id=c.org_id AND y.id=c.youth_member_id "
            + "JOIN member m ON m.org_id=y.org_id AND m.id=y.member_id "
            + "JOIN party_member p ON p.org_id=c.org_id AND p.student_no=m.student_no AND p.deleted=0 "
            + "WHERE c.org_id=? AND c.id=? AND c.kind='RECOMMENDATION' AND c.status='APPROVED' AND m.student_no<>''";

    private final JdbcTemplate db;
    private final PackWorkspace workspace;
    private final ActivityService activities;

    public YouthService(JdbcTemplate db, PackWorkspace workspace, ActivityService activities) {
        this.db = db;
        this.workspace = workspace;
        this.activities = activities;
    }

    public record MemberInput(@NotNull @Positive Long memberId, @NotNull @Positive Long branchId,
                              String duty, LocalDate joinedOn, Integer version) {}
    public record LifeInput(Long activityId, @NotNull @Positive Long branchId, @NotNull String kind,
                            String name, LocalDate date, String agenda, String minutes, Integer version) {}
    public record CaseInput(@NotNull @Positive Long youthMemberId, @NotNull String kind, int year, String submission,
                            String criteria, Integer version) {}
    public record Review(@NotNull Integer version, @NotNull Boolean approved, String decision) {}
    public record Receipt(@NotNull Integer version, @NotNull @Positive Long partyMemberId) {}

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        long org = workspace.org();
        return Map.of(
                "branches", workspace.rows("SELECT d.id,d.name,COUNT(y.id) AS member_count FROM youth_member y "
                        + "JOIN sys_department d ON d.id=y.branch_id AND d.org_id=y.org_id "
                        + "WHERE y.org_id=? GROUP BY d.id,d.name ORDER BY d.name", org),
                "pendingReviews", count("SELECT COUNT(*) FROM youth_case WHERE org_id=? AND status='PENDING'", org),
                "pendingMinutes", count("SELECT COUNT(*) FROM youth_life WHERE org_id=? AND archived_at IS NULL", org),
                "recentLife", workspace.rows("SELECT r.id,r.activity_id,a.name,r.kind,a.start_date,r.archived_at "
                        + "FROM youth_life r JOIN activity a ON a.id=r.activity_id AND a.org_id=r.org_id "
                        + "WHERE r.org_id=? ORDER BY r.id DESC LIMIT 5", org));
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> members(int page) {
        return workspace.page("r.id,r.member_id,r.branch_id,r.duty,r.joined_on,r.version,m.name AS member_name,"
                + "m.student_no,d.name AS branch_name", "FROM youth_member r JOIN member m ON m.org_id=r.org_id AND m.id=r.member_id "
                + "LEFT JOIN sys_department d ON d.org_id=r.org_id AND d.id=r.branch_id WHERE r.org_id=?", page, workspace.org());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> memberChoices(String keyword) {
        String pattern = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        return workspace.rows("SELECT y.id,m.name,m.student_no,d.name AS branch_name FROM youth_member y "
                + "JOIN member m ON m.org_id=y.org_id AND m.id=y.member_id AND m.deleted=0 "
                + "JOIN sys_department d ON d.org_id=y.org_id AND d.id=y.branch_id "
                + "WHERE y.org_id=? AND (m.name LIKE ? OR m.student_no LIKE ?) ORDER BY y.id DESC LIMIT 50",
                workspace.org(), pattern, pattern);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(long id) {
        return workspace.rows("SELECT h.changed_at,old.name AS from_branch,new_branch.name AS to_branch,u.real_name AS actor "
                + "FROM youth_branch_change h LEFT JOIN sys_department old ON old.org_id=h.org_id AND old.id=h.from_branch_id "
                + "LEFT JOIN sys_department new_branch ON new_branch.org_id=h.org_id AND new_branch.id=h.to_branch_id "
                + "LEFT JOIN sys_user u ON u.org_id=h.org_id AND u.id=h.actor_id "
                + "WHERE h.org_id=? AND h.youth_member_id=? ORDER BY h.id DESC", workspace.org(), id);
    }

    @Transactional
    public void saveMember(Long id, MemberInput input, long actor) {
        workspace.lockEnabled(DomainPacks.YOUTH);
        long org = workspace.org();
        workspace.requireBranch(input.branchId());
        if (input.joinedOn() != null && input.joinedOn().isAfter(LocalDate.now())) throw new BizException(400, "入团日期不能晚于今天");
        String duty = text(input.duty(), 60, "团内职务", false);
        Long previous = null;
        if (id == null) {
            workspace.one("SELECT id FROM member WHERE org_id=? AND id=? AND deleted=0", "成员不存在", org, input.memberId());
            if (count("SELECT COUNT(*) FROM youth_member WHERE org_id=? AND member_id=?", org, input.memberId()) > 0) {
                throw new BizException(409, "该成员已有团员档案");
            }
            db.update("INSERT INTO youth_member(org_id,member_id,branch_id,duty,joined_on) VALUES(?,?,?,?,?)",
                    org, input.memberId(), input.branchId(), duty, input.joinedOn());
            id = db.queryForObject("SELECT id FROM youth_member WHERE org_id=? AND member_id=?", Long.class, org, input.memberId());
        } else {
            var row = workspace.one("SELECT member_id,branch_id FROM youth_member WHERE org_id=? AND id=?", "团员档案不存在", org, id);
            if (((Number) row.get("memberId")).longValue() != input.memberId()) throw new BizException(400, "不能更换档案关联成员");
            previous = ((Number) row.get("branchId")).longValue();
            changed(db.update("UPDATE youth_member SET branch_id=?,duty=?,joined_on=?,version=version+1 WHERE org_id=? AND id=? AND version=?",
                    input.branchId(), duty, input.joinedOn(), org, id, input.version()));
        }
        if (!input.branchId().equals(previous)) {
            db.update("INSERT INTO youth_branch_change(org_id,youth_member_id,from_branch_id,to_branch_id,actor_id) VALUES(?,?,?,?,?)",
                    org, id, previous, input.branchId(), actor);
        }
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> life(int page) {
        return workspace.page("r.id,r.activity_id,r.branch_id,r.kind,r.agenda,r.minutes,r.archived_at,r.version,"
                + "a.name AS activity_name,a.start_date,d.name AS branch_name,a.deleted AS activity_deleted", "FROM youth_life r JOIN activity a "
                + "ON a.org_id=r.org_id AND a.id=r.activity_id LEFT JOIN sys_department d ON d.org_id=r.org_id AND d.id=r.branch_id "
                + "WHERE r.org_id=?", page, workspace.org());
    }

    @Transactional
    public void saveLife(Long id, LifeInput input, boolean createActivityAllowed) {
        workspace.lockEnabled(DomainPacks.YOUTH);
        long org = workspace.org();
        workspace.requireBranch(input.branchId());
        if (!LIFE_KINDS.contains(input.kind())) throw new BizException(400, "请选择组织生活类型");
        String agenda = text(input.agenda(), 4000, "议题", true);
        String minutes = text(input.minutes(), 8000, "纪要", false);
        if (id != null) {
            changed(db.update("UPDATE youth_life SET branch_id=?,kind=?,agenda=?,minutes=?,version=version+1 "
                    + "WHERE org_id=? AND id=? AND version=? AND archived_at IS NULL",
                    input.branchId(), input.kind(), agenda, minutes, org, id, input.version()));
            return;
        }
        Long activityId = input.activityId();
        if (activityId == null) {
            if (!createActivityAllowed) throw new BizException(403, "当前账号没有创建活动权限");
            if (input.date() == null) throw new BizException(400, "请选择活动日期");
            var activity = new ActivityRequest();
            activity.setName(text(input.name(), 100, "活动名称", true));
            activity.setType(switch (input.kind()) {
                case "THEME_DAY" -> "DATE";
                case "LESSON" -> "YOUTH_LESSON";
                default -> "MEETING";
            });
            activity.setStartDate(input.date());
            activity.setEndDate(input.date());
            activityId = activities.create(activity);
        } else workspace.requireActivity(activityId);
        if (count("SELECT COUNT(*) FROM youth_life WHERE org_id=? AND activity_id=?", org, activityId) > 0) {
            throw new BizException(409, "该活动已关联组织生活记录");
        }
        db.update("INSERT INTO youth_life(org_id,activity_id,branch_id,kind,agenda,minutes) VALUES(?,?,?,?,?,?)",
                org, activityId, input.branchId(), input.kind(), agenda, minutes);
    }

    @Transactional
    public void archiveLife(long id, int version, long actor) {
        workspace.lockEnabled(DomainPacks.YOUTH);
        changed(db.update("UPDATE youth_life SET archived_by=?,archived_at=CURRENT_TIMESTAMP,version=version+1 "
                + "WHERE org_id=? AND id=? AND version=? AND archived_at IS NULL AND minutes IS NOT NULL",
                actor, workspace.org(), id, version));
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> cases(String kind, int page) {
        if (!CASE_KINDS.contains(kind)) throw new BizException(400, "记录类型无效");
        return workspace.page(CASE_FIELDS, CASE_FROM + " AND r.kind=?", page, workspace.org(), kind);
    }

    @Transactional
    public void submitCase(CaseInput input, long actor) {
        workspace.lockEnabled(DomainPacks.YOUTH);
        long org = workspace.org();
        if (!CASE_KINDS.contains(input.kind()) || input.year() < 2000 || input.year() > 2100) {
            throw new BizException(400, "请选择有效的记录类型和年度");
        }
        var member = workspace.one("SELECT y.branch_id FROM youth_member y JOIN member m ON m.org_id=y.org_id AND m.id=y.member_id "
                + "WHERE y.org_id=? AND y.id=? AND m.deleted=0", "团员档案不存在", org, input.youthMemberId());
        String submission = text(input.submission(), 8000, "提交材料", true);
        String criteria = text(input.criteria(), 4000, "评议依据", false);
        var existing = workspace.rows("SELECT id,status FROM youth_case WHERE org_id=? AND youth_member_id=? AND kind=? AND year_no=?",
                org, input.youthMemberId(), input.kind(), input.year());
        long caseId;
        if (existing.isEmpty()) {
            if (input.version() != null) throw new BizException(409, "原记录已变化，请刷新后重试");
            db.update("INSERT INTO youth_case(org_id,youth_member_id,branch_id,kind,year_no,criteria,submission,submitted_by) VALUES(?,?,?,?,?,?,?,?)",
                    org, input.youthMemberId(), member.get("branchId"), input.kind(), input.year(), criteria, submission, actor);
            caseId = db.queryForObject("SELECT id FROM youth_case WHERE org_id=? AND youth_member_id=? AND kind=? AND year_no=?",
                    Long.class, org, input.youthMemberId(), input.kind(), input.year());
        } else {
            if (!"RETURNED".equals(existing.getFirst().get("status"))) throw new BizException(409, "该团员已有同年度记录");
            caseId = ((Number) existing.getFirst().get("id")).longValue();
            changed(db.update("UPDATE youth_case SET branch_id=?,criteria=?,submission=?,submitted_by=?,submitted_at=CURRENT_TIMESTAMP,"
                    + "status='PENDING',version=version+1 WHERE org_id=? AND id=? AND status='RETURNED' AND version=?",
                    member.get("branchId"), criteria, submission, actor, org, caseId, input.version()));
        }
        event(caseId, "SUBMITTED", criteria == null ? submission : "评议依据\n" + criteria + "\n\n提交材料\n" + submission, actor);
    }

    @Transactional
    public void review(long id, Review input, long actor) {
        workspace.lockEnabled(DomainPacks.YOUTH);
        String decision = text(input.decision(), 4000, "审核意见", true);
        changed(db.update("UPDATE youth_case SET status=?,decision=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,version=version+1 "
                + "WHERE org_id=? AND id=? AND version=? AND status='PENDING'",
                input.approved() ? "APPROVED" : "RETURNED", decision, actor, workspace.org(), id, input.version()));
        event(id, input.approved() ? "APPROVED" : "RETURNED", decision, actor);
    }

    @Transactional
    public void archiveCase(long id, int version, long actor) {
        workspace.lockEnabled(DomainPacks.YOUTH);
        changed(db.update("UPDATE youth_case SET status='ARCHIVED',version=version+1 "
                + "WHERE org_id=? AND id=? AND version=? AND status='APPROVED' AND kind='EVALUATION'", workspace.org(), id, version));
        event(id, "ARCHIVED", null, actor);
    }

    @Transactional
    public void receive(long id, Receipt input, long actor) {
        workspace.lockEnabled(DomainPacks.PARTY);
        workspace.lockEnabled(DomainPacks.YOUTH);
        long org = workspace.org();
        workspace.one("SELECT p.id " + PARTY_MATCHES + " AND p.id=?", "未找到与推优团员学号匹配的党务档案", org, id, input.partyMemberId());
        changed(db.update("UPDATE youth_case SET status='RECEIVED',party_member_id=?,received_by=?,received_at=CURRENT_TIMESTAMP,version=version+1 "
                + "WHERE org_id=? AND id=? AND version=? AND status='APPROVED' AND kind='RECOMMENDATION'",
                input.partyMemberId(), actor, org, id, input.version()));
        event(id, "RECEIVED", null, actor);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> partyMatches(long id) {
        return workspace.rows("SELECT p.id,p.name,p.student_no " + PARTY_MATCHES + " ORDER BY p.id LIMIT 50", workspace.org(), id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> caseHistory(long id) {
        return workspace.rows("SELECT e.id,e.action,e.note,e.occurred_at,u.real_name AS actor FROM youth_case_event e "
                + "LEFT JOIN sys_user u ON u.org_id=e.org_id AND u.id=e.actor_id WHERE e.org_id=? AND e.case_id=? ORDER BY e.id DESC",
                workspace.org(), id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> partyReceipts(long id) {
        return workspace.rows("SELECT " + CASE_FIELDS + " " + CASE_FROM + " AND r.party_member_id=? AND r.status='RECEIVED' "
                + "ORDER BY r.id DESC", workspace.org(), id);
    }

    private void event(long id, String action, String note, long actor) {
        db.update("INSERT INTO youth_case_event(org_id,case_id,action,note,actor_id) VALUES(?,?,?,?,?)",
                workspace.org(), id, action, note, actor);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportCase(long id) {
        return workspace.one("SELECT " + CASE_FIELDS + " " + CASE_FROM + " AND r.id=? AND r.kind='RECOMMENDATION' "
                + "AND r.status IN ('APPROVED','RECEIVED')", "没有可导出的推优材料", workspace.org(), id);
    }

    private long count(String sql, Object... args) { return db.queryForObject(sql, Long.class, args); }
}
