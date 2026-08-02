package com.pams.config;

import com.pams.entity.Department;
import com.pams.entity.Role;
import com.pams.entity.User;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityAgenda;
import com.pams.module.activity.entity.ActivityPlan;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.entity.Signin;
import com.pams.module.activity.entity.Task;
import com.pams.module.activity.repository.ActivityAgendaRepository;
import com.pams.module.activity.repository.ActivityPlanRepository;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.repository.SigninRepository;
import com.pams.module.activity.repository.TaskRepository;
import com.pams.module.archive.entity.Announcement;
import com.pams.module.archive.entity.CreditRecord;
import com.pams.module.archive.entity.Material;
import com.pams.module.archive.repository.AnnouncementRepository;
import com.pams.module.archive.repository.CreditRecordRepository;
import com.pams.module.archive.repository.MaterialRepository;
import com.pams.module.content.entity.Article;
import com.pams.module.content.entity.News;
import com.pams.module.content.repository.ArticleRepository;
import com.pams.module.content.repository.NewsRepository;
import com.pams.module.party.entity.PartyMember;
import com.pams.module.party.entity.PartyStage;
import com.pams.module.party.entity.PartyStageType;
import com.pams.module.party.repository.PartyMemberRepository;
import com.pams.module.party.repository.PartyStageRepository;
import com.pams.module.routine.entity.FreeSchedule;
import com.pams.module.routine.repository.FreeScheduleRepository;
import com.pams.repository.DepartmentRepository;
import com.pams.repository.RoleRepository;
import com.pams.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

@Component
public class DataSeeder implements ApplicationRunner {
    private final DepartmentRepository departmentRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    // 演示业务数据用仓库（仅在非 test profile 注入）
    private final ActivityRepository activityRepository;
    private final ActivityPlanRepository activityPlanRepository;
    private final ActivityAgendaRepository agendaRepository;
    private final TaskRepository taskRepository;
    private final SigninRepository signinRepository;
    private final ArticleRepository articleRepository;
    private final NewsRepository newsRepository;
    private final MaterialRepository materialRepository;
    private final AnnouncementRepository announcementRepository;
    private final PartyMemberRepository partyMemberRepository;
    private final PartyStageRepository partyStageRepository;
    private final FreeScheduleRepository freeScheduleRepository;
    private final CreditRecordRepository creditRecordRepository;

    public DataSeeder(DepartmentRepository d, RoleRepository r, UserRepository u, PasswordEncoder p,
                      Environment environment,
                      ActivityRepository activityRepository,
                      ActivityPlanRepository activityPlanRepository,
                      ActivityAgendaRepository agendaRepository,
                      TaskRepository taskRepository,
                      SigninRepository signinRepository,
                      ArticleRepository articleRepository,
                      NewsRepository newsRepository,
                      MaterialRepository materialRepository,
                      AnnouncementRepository announcementRepository,
                      PartyMemberRepository partyMemberRepository,
                      PartyStageRepository partyStageRepository,
                      FreeScheduleRepository freeScheduleRepository,
                      CreditRecordRepository creditRecordRepository) {
        this.departmentRepository = d;
        this.roleRepository = r;
        this.userRepository = u;
        this.passwordEncoder = p;
        this.environment = environment;
        this.activityRepository = activityRepository;
        this.activityPlanRepository = activityPlanRepository;
        this.agendaRepository = agendaRepository;
        this.taskRepository = taskRepository;
        this.signinRepository = signinRepository;
        this.articleRepository = articleRepository;
        this.newsRepository = newsRepository;
        this.materialRepository = materialRepository;
        this.announcementRepository = announcementRepository;
        this.partyMemberRepository = partyMemberRepository;
        this.partyStageRepository = partyStageRepository;
        this.freeScheduleRepository = freeScheduleRepository;
        this.creditRecordRepository = creditRecordRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedOrg();
        // 演示业务数据只在非测试环境注入，避免影响 H2 集成测试断言。
        if (isTestProfile()) return;
        seedDemoData();
    }

    /** Task 4 建的基础数据：部门 / 角色 / 账号（空库才建，幂等）。 */
    private void seedOrg() {
        if (departmentRepository.count() > 0) return;
        Department[] depts = {
            mkDept("文秘部", 1), mkDept("组织部", 2), mkDept("新媒体中心", 3), mkDept("青年科技部", 4)
        };
        for (Department d : depts) departmentRepository.save(d);
        Department org = depts[1];

        Role[] roles = {
            mkRole("TEACHER", "指导老师", 5, "ALL"),
            mkRole("DIRECTOR", "主任", 4, "ALL"),
            mkRole("ORG_LEADER", "组织部长", 3, "ALL"),
            mkRole("SECRETARY_LEADER", "文秘部长", 3, "ALL"),
            mkRole("MEDIA_LEADER", "新媒体部长", 3, "ALL"),
            mkRole("TECH_LEADER", "青年科技部长", 3, "ALL"),
            mkRole("STAFF", "干事", 1, "DEPT")
        };
        for (Role r : roles) roleRepository.save(r);

        saveUser("teacher", "指导老师", null, roleByCode(roles, "TEACHER"));
        saveUser("zhuren", "主任", null, roleByCode(roles, "DIRECTOR"));
        saveUser("orgleader", "组织部长", org, roleByCode(roles, "ORG_LEADER"));
        saveUser("secleader", "文秘部长", depts[0], roleByCode(roles, "SECRETARY_LEADER"));
        saveUser("medialeader", "新媒体部长", depts[2], roleByCode(roles, "MEDIA_LEADER"));
        saveUser("techleader", "青年科技部长", depts[3], roleByCode(roles, "TECH_LEADER"));
        saveUser("admin", "系统管理员", null, roleByCode(roles, "DIRECTOR"));
        saveUser("staff", "干事", depts[0], roleByCode(roles, "STAFF"));
    }

    /**
     * Task 30 演示数据：1 个示例活动全流程 + 若干党务成员 + 无课表 + 素拓加分。
     * 只对空库生效（activityRepository.count()>0 跳过），不污染已有 dev 库。
     */
    private void seedDemoData() {
        if (activityRepository.count() > 0) return;
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        // ---------- 1. 示例活动（"第四十期入党积极分子培训班"） ----------
        Activity a = new Activity();
        a.setName("第四十期入党积极分子培训班");
        a.setTheme("筑牢信仰之基 · 传承红色基因");
        a.setType("PARTY_LESSON");
        a.setStatus(ActivityStatus.EXECUTING);
        a.setStartDate(today.minusDays(1));
        a.setEndDate(today.plusDays(6));
        a.setLocation("学院东教学楼 A101");
        a.setOrganizer("信息与智能工程学院党建办公室");
        a.setTargetAudience("第四十期入党积极分子学员");
        a.setHost("陈书记");
        a.setLeader("组织部长");
        a.setDescription("面向第四十期入党积极分子的集中培训班，涵盖开班仪式、专题授课、分组研讨与结业考核，"
                + "同步开展日常考勤与结业评定，强化入党积极分子理论素养与党性锻炼。");
        a.setCreatedBy(userId("zhuren"));
        a.setCreatedAt(now.minusDays(6));
        a.setUpdatedAt(now.minusDays(6));
        a.setDeleted(0);
        activityRepository.save(a);
        Long aid = a.getId();

        // ---------- 2. 甘特图任务（含依赖链，甘特图可见） ----------
        Task t1 = task(aid, deptId("组织部"), "确定培训方案与课程安排", "组织部长",
                today.minusDays(7), today.minusDays(4), null, 0, 100, Task.TaskStatus.DONE, 1, "");
        Task t2 = task(aid, deptId("文秘部"), "发布培训通知并统计学员名单", "文秘部长",
                today.minusDays(3), today.minusDays(1), t1.getId(), 0, 100, Task.TaskStatus.DONE, 1, "");
        Task t3 = task(aid, deptId("文秘部"), "场地布置与会务准备", "文秘部长",
                today.minusDays(1), today, t2.getId(), 0, 60, Task.TaskStatus.DOING, 2, "");
        Task t4 = task(aid, deptId("新媒体中心"), "开班仪式", "新媒体部长",
                today, today, t3.getId(), 1, 100, Task.TaskStatus.DOING, 3, "里程碑：开班仪式");
        Task t5 = task(aid, deptId("组织部"), "课程培训与日常考勤", "组织部长",
                today.plusDays(1), today.plusDays(4), t4.getId(), 0, 0, Task.TaskStatus.TODO, 2, "");
        task(aid, deptId("青年科技部"), "结业考核与材料归档", "青年科技部长",
                today.plusDays(5), today.plusDays(6), t5.getId(), 0, 0, Task.TaskStatus.TODO, 1, "");

        // ---------- 3. 策划书（含流程/预算 JSON，前端可渲染） ----------
        ActivityPlan p = new ActivityPlan();
        p.setActivityId(aid);
        p.setVersion(1);
        p.setBackground("为深入学习贯彻习近平新时代中国特色社会主义思想，加强入党积极分子培养教育，"
                + "依据学院党建办年度工作计划举办本期培训班。");
        p.setPurpose("引导学员系统学习党章党规，端正入党动机，坚定理想信念，提升政治理论素养。");
        p.setContent("本期培训班安排专题授课 6 场、分组研讨 2 场、结业考核 1 场，同步开展日常考勤与结业评定。");
        p.setFlow(FLOW_JSON);
        p.setNotice("请各位学员提前 10 分钟入场签到并携带学员证；培训期间手机调至静音，保持会场秩序。");
        p.setEmergency("如遇突发情况请听从现场工作人员引导，医疗点设在东教学楼 1 层值班室。");
        p.setBudget(BUDGET_JSON);
        p.setStatus(ActivityPlan.PlanStatus.APPROVED);
        p.setSubmitterId(userId("secleader"));
        p.setReviewerId(userId("zhuren"));
        p.setReviewComment("方案完整可行，审核通过。");
        p.setCreatedAt(now.minusDays(6));
        p.setUpdatedAt(now.minusDays(5));
        activityPlanRepository.save(p);

        // ---------- 4. 议程 ----------
        String[][] agendas = {
            {"1", "奏唱国歌"},
            {"2", "领导致辞"},
            {"3", "专题授课：党章与入党基本知识"},
            {"4", "分组研讨：如何端正入党动机"},
            {"5", "结业考核（闭卷）"},
        };
        for (String[] ag : agendas) {
            ActivityAgenda aa = new ActivityAgenda();
            aa.setActivityId(aid);
            aa.setStepNo(Integer.parseInt(ag[0]));
            aa.setTitle(ag[1]);
            aa.setRemark(ag.length > 2 ? ag[2] : null);
            aa.setCreatedAt(now);
            agendaRepository.save(aa);
        }

        // ---------- 5. 签到 ----------
        signin(aid, "李想", "2435101020101", "24物联网班", "入党积极分子", now.minusHours(2));
        signin(aid, "王慧", "2435101020102", "24计算机网络技术3班", "重点发展对象", now.minusHours(2));
        signin(aid, "张伟", "2435101020103", "24软件技术1班", "预备党员", now.minusHours(2));

        // ---------- 6. 推文（Article）与新闻稿（News） ----------
        Article article = new Article();
        article.setTitle("第四十期入党积极分子培训班顺利开班");
        article.setSummary("学院党建办第四十期入党积极分子培训班于近日开班，六十余名学员参加。");
        article.setContent("为加强入党积极分子培养教育，信息与智能工程学院党建办公室举办第四十期入党积极分子培训班。"
                + "开班仪式上，学院领导作开班动员，勉励学员坚定理想信念、端正入党动机。");
        article.setActivityId(aid);
        article.setArticleType(Article.ArticleType.REPORT);
        article.setStatus(Article.ArticleStatus.PUBLISHED);
        article.setAuthorId(userId("medialeader"));
        article.setPublishTime(now.minusDays(1));
        article.setCreatedAt(now.minusDays(1));
        article.setUpdatedAt(now.minusDays(1));
        article.setDeleted(0);
        articleRepository.save(article);

        News news = new News();
        news.setTitle("四十期培训班开班仪式成功举行");
        news.setSubtitle("六十余名入党积极分子参加");
        news.setContent("学院党建办举行第四十期入党积极分子培训班开班仪式，仪式在庄严的国歌声中拉开帷幕。");
        news.setActivityId(aid);
        news.setAuthorId(userId("medialeader"));
        news.setPublishDate(today.minusDays(1));
        news.setStatus(News.NewsStatus.PUBLISHED);
        news.setCreatedAt(now.minusDays(1));
        news.setUpdatedAt(now.minusDays(1));
        news.setDeleted(0);
        newsRepository.save(news);

        // ---------- 7. 材料（归档一条） ----------
        Material m = new Material();
        m.setName("第四十期入党积极分子培训班签到表");
        m.setBizType("SIGNIN");
        m.setActivityId(aid);
        m.setDeptId(deptId("文秘部"));
        m.setUploaderId(userId("secleader"));
        m.setTag("培训班|签到");
        m.setDescription("学员签到情况记录");
        m.setCreatedAt(now);
        m.setDeleted(0);
        materialRepository.save(m);

        // ---------- 8. 公告 ----------
        Announcement an = new Announcement();
        an.setTitle("关于开展第四十期入党积极分子培训班的通知");
        an.setContent("各位入党积极分子：第四十期入党积极分子培训班定于近期开班，请全体学员按时参加培训并完成结业考核。");
        an.setPublisherId(userId("secleader"));
        an.setPublishTime(now.minusDays(2));
        an.setCreatedAt(now.minusDays(2));
        an.setDeleted(0);
        announcementRepository.save(an);

        // ---------- 9. 党务成员（不同发展阶段，各挂一条 stage） ----------
        partyMember("李想", "女", "24物联网班", "入党积极分子", "2435101020101", now);
        partyMember("王慧", "女", "24计算机网络技术3班", "重点发展对象", "2435101020102", now);
        partyMember("张伟", "男", "24软件技术1班", "预备党员", "2435101020103", now);
        partyMember("陈静", "女", "23电子信息工程班", "正式党员", "2435101020104", now);

        // ---------- 10. 无课表（一条） ----------
        // 李想为培训班学员（非系统账号），不挂接 sys_user 用户；deptId 指向其真实所在部门（文秘部）。
        FreeSchedule fs = new FreeSchedule();
        fs.setUserId(null);
        fs.setPersonName("李想");
        fs.setClassName("24物联网班");
        fs.setDeptId(deptId("文秘部"));
        fs.setFreeWeeks("[2,4,6]");
        fs.setNote("周二/周四/周六下午可值班");
        fs.setCreatedAt(now);
        freeScheduleRepository.save(fs);

        // ---------- 11. 素拓加分（一条） ----------
        // 李想为培训班学员（非系统账号），不挂接 sys_user 用户。
        CreditRecord cr = new CreditRecord();
        cr.setUserId(null);
        cr.setPersonName("李想");
        cr.setStudentNo("2435101020101");
        cr.setActivityId(aid);
        cr.setProject("参加第四十期入党积极分子培训班");
        cr.setCredit(new BigDecimal("2.00"));
        cr.setBasis("PARTICIPATE");
        cr.setRemark("培训全程考勤合格");
        cr.setRecordBy(userId("zhuren"));
        cr.setCreatedAt(now);
        creditRecordRepository.save(cr);
    }

    // ==================== 演示数据小工具 ====================

    private Task task(Long activityId, Long deptId, String name, String assignee,
                      LocalDate start, LocalDate end, Long dependsOn,
                      int milestone, int progress, Task.TaskStatus status, int priority, String description) {
        Task t = new Task();
        t.setActivityId(activityId);
        t.setName(name);
        t.setDeptId(deptId);
        t.setAssignee(assignee);
        t.setStartDate(start);
        t.setEndDate(end);
        t.setDependsOn(dependsOn);
        t.setIsMilestone(milestone);
        t.setProgress(progress);
        t.setStatus(status);
        t.setPriority(priority);
        t.setDescription(description);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        t.setDeleted(0);
        return taskRepository.save(t);
    }

    private void signin(Long activityId, String name, String studentNo, String className,
                        String identityType, LocalDateTime signTime) {
        Signin s = new Signin();
        s.setActivityId(activityId);
        s.setPersonId(null);
        s.setName(name);
        s.setStudentNo(studentNo);
        s.setClassName(className);
        s.setIdentityType(identityType);
        s.setSignType(Signin.SignType.MANUAL);
        s.setSignTime(signTime);
        s.setLocation("东教学楼 A101");
        s.setCreatedAt(signTime);
        signinRepository.save(s);
    }

    private void partyMember(String name, String gender, String className, String politicalStatus,
                             String studentNo, LocalDateTime now) {
        PartyMember pm = new PartyMember();
        pm.setName(name);
        pm.setGender(gender);
        pm.setNation("汉族");
        pm.setEducation("本科在读");
        pm.setClassName(className);
        pm.setCollege("信息与智能工程学院");
        pm.setBranchName("第一党支部");
        pm.setPoliticalStatus(politicalStatus);
        pm.setStudentNo(studentNo);
        pm.setCreatedAt(now);
        pm.setUpdatedAt(now);
        pm.setDeleted(0);
        partyMemberRepository.save(pm);

        PartyStageType stage = PartyStageType.valueOf(
            switch (politicalStatus) {
                case "入党积极分子" -> "ACTIVE";
                case "重点发展对象" -> "DEVELOPMENT";
                case "预备党员" -> "PROBATIONARY";
                case "正式党员" -> "FULL";
                default -> "APPLICANT";
            });
        String issueNo = switch (stage) {
            case ACTIVE, DEVELOPMENT -> "40";
            case PROBATIONARY -> "39";
            case FULL -> "38";
            default -> null;
        };
        PartyStage s = new PartyStage();
        s.setMemberId(pm.getId());
        s.setStage(stage);
        s.setIssueNo(issueNo);
        s.setStatus("CURRENT");
        s.setStartDate(LocalDate.now().minusMonths(3));
        s.setEndDate(stage == PartyStageType.FULL ? null : LocalDate.now().plusMonths(6));
        s.setRemark("演示数据");
        s.setCreatedAt(now);
        partyStageRepository.save(s);
    }

    private boolean isTestProfile() {
        return Arrays.asList(environment.getActiveProfiles()).contains("test");
    }

    private Long userId(String username) {
        return userRepository.findByUsername(username).map(User::getId).orElse(null);
    }

    private Long deptId(String name) {
        return departmentRepository.findAll().stream()
                .filter(d -> name.equals(d.getName()))
                .map(Department::getId)
                .findFirst().orElse(null);
    }

    // ==================== 原有基础数据工具 ====================

    private Department mkDept(String name, int sort) {
        Department d = new Department();
        d.setName(name); d.setSortOrder(sort);
        d.setCreatedAt(LocalDateTime.now()); d.setUpdatedAt(LocalDateTime.now());
        return d;
    }

    private Role mkRole(String code, String name, int level, String dataScope) {
        Role r = new Role();
        r.setCode(code); r.setName(name); r.setLevel(level); r.setDataScope(dataScope);
        r.setCreatedAt(LocalDateTime.now());
        return r;
    }

    private Role roleByCode(Role[] roles, String code) {
        for (Role r : roles) if (r.getCode().equals(code)) return r;
        throw new IllegalStateException("role not found: " + code);
    }

    private void saveUser(String username, String realName, Department dept, Role role) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setRealName(realName);
        u.setDept(dept);
        u.setRole(role);
        u.setStatus(1);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        u.setDeleted(0);
        userRepository.save(u);
    }

    private static final String FLOW_JSON = """
        [{"step":"报到签到","detail":"学员入场签到，领取培训资料"},
         {"step":"开班仪式","detail":"奏唱国歌、学院领导致辞"},
         {"step":"集中授课","detail":"专题辅导：党章与入党基本知识"},
         {"step":"分组研讨","detail":"各支部围绕如何端正入党动机展开研讨"},
         {"step":"结业考核","detail":"闭卷考核并评定结业"}]
        """.strip();

    private static final String BUDGET_JSON = """
        [{"item":"横幅与宣传物料","quantity":2,"unitPrice":80.0,"totalPrice":160.0},
         {"item":"培训资料打印","quantity":200,"unitPrice":1.5,"totalPrice":300.0},
         {"item":"结业证书","quantity":60,"unitPrice":5.0,"totalPrice":300.0}]
        """.strip();
}
