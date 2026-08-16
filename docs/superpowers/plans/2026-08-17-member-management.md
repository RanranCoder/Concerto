# 成员管理模块（成员花名册）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增「成员管理」模块 —— 独立成员花名册（member + member_session 两张表），支持多届别、正式状态（在职/往届/退部/开除/离职）、Excel 导入导出、换届批量归档、人数统计、完整成员详情页，以及用户管理「从花名册一键导入注册账号」。

**Architecture:** 后端新建 `com.pams.module.member` 模块（entity/repository/service/controller/dto），复用现有 `Result`/`PageResult`/`BizException`、`@PreAuthorize` 干部白名单、POI 导入模式（RosterImportService）。前端新建 `src/api/member.ts` + `src/pages/member/{MemberList,MemberDetail}.tsx`，路由用 `RequireRole(LEADER_ROLES)` 包裹，菜单按 `roleLevel >= 3` 显示。成员表与 sys_user 无强关联，靠「一键导入账号」衔接。

**Tech Stack:** Spring Boot 4 + JPA + Flyway（V13）+ MySQL/H2(MySQL 模式) + Apache POI 5.4.1；React 18 + Vite + TS + AntD 5 + Zustand + react-router。

## Global Constraints

- 数据库迁移放 `pams-backend/src/main/resources/db/migration/V13__member.sql`，命名 `V{n}__snake_case.sql`。
- **迁移 SQL 必须 H2(MySQL 模式) 兼容**（测试库同脚本执行）：不要用 `ON UPDATE CURRENT_TIMESTAMP`（H2 不支持），`updated_at` 由 Service 层显式设置 `LocalDateTime.now()`；允许 `COMMENT '中文'`、`TINYINT`、`UNIQUE KEY`、`CONSTRAINT fk_* FOREIGN KEY`、`BIGINT AUTO_INCREMENT PRIMARY KEY`（现有 V1/V3 均已验证）。
- 成员/届别实体用 `@SQLRestriction("deleted = 0")` 软删除（同 `User`），删除 = 置 `deleted=1`。
- 后端权限常量复用模式：`private static final String LEADER = "hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')"`（同 CourseScheduleController），一键导入账号两个端点用 `ADMIN = "hasAnyRole('TEACHER','DIRECTOR')"`。
- 前端 API 封装在 `src/api/*.ts`，薄函数 + `http.ts` 的 `get/post/put/del`；blob 下载用 `http.get(url, { params, responseType: 'blob' })`（拦截器对 blob 原样返回 AxiosResponse）。
- 页面复用 `GlassCard/GlassTable/GlassModal/PageHeader`；编辑回填用 Form `initialValues`（GlassModal `destroyOnHidden` 卸载字段）。
- 枚举中文标签映射集中一处（后端 `MemberEnums` 常量类，前端 `api/member.ts` 导出的映射），不散落硬编码。
- 全量验证：后端 `mvn test`、前端 `npm run build` 必须全绿。
- 工作树当前有未提交的前端改动（`pams-web/...`）属他处会话，**只提交本模块新增/修改的文件**，不 `git add -A`。

---

### Task 1: 数据层 — V13 迁移 + 实体 + 仓库

**Files:**
- Create: `pams-backend/src/main/resources/db/migration/V13__member.sql`
- Create: `pams-backend/src/main/java/com/pams/module/member/entity/Member.java`
- Create: `pams-backend/src/main/java/com/pams/module/member/entity/MemberSession.java`
- Create: `pams-backend/src/main/java/com/pams/module/member/repository/MemberRepository.java`
- Create: `pams-backend/src/main/java/com/pams/module/member/repository/MemberSessionRepository.java`
- Create: `pams-backend/src/main/java/com/pams/module/member/dto/MemberEnums.java`
- Test: `pams-backend/src/test/java/com/pams/module/member/MemberRepositoryTest.java`

**Interfaces:**
- Consumes: 现有 `com.pams.entity.Department`（表 `sys_department`，已有 4 个部门种子：文秘部/组织部/新媒体中心/青年科技部）。
- Produces: 实体 `Member`（字段 id/sessionId/deptId/position/name/gender/studentNo/className/phone/politicalStatus/status/remark/createdBy/createdAt/updatedAt/deleted）、`MemberSession`（id/name/isCurrent/sortOrder/remark/createdAt/updatedAt/deleted）；仓库 `MemberRepository`（JpaRepository + JpaSpecificationExecutor + 派生方法）、`MemberSessionRepository`；枚举常量类 `MemberEnums.POSITION_LABELS`/`STATUS_LABELS` + 反查 `positionOf(String label)`/`statusOf(String label)`。

- [ ] **Step 1: 写迁移 SQL**

`V13__member.sql`（不要 `ON UPDATE CURRENT_TIMESTAMP`，updated_at 由 Service 设置）：
```sql
CREATE TABLE IF NOT EXISTS member_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(50) NOT NULL UNIQUE COMMENT '届名，如"第九届"',
  is_current TINYINT DEFAULT 0 COMMENT '是否当前届',
  sort_order INT DEFAULT 0,
  remark VARCHAR(255),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS member (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL COMMENT '所属届别',
  dept_id BIGINT COMMENT '部门，主任/副主任为空',
  position VARCHAR(20) NOT NULL COMMENT 'DIRECTOR/SUB_DIRECTOR/DEPT_HEAD/SUB_DEPT_HEAD/STAFF',
  name VARCHAR(50) NOT NULL COMMENT '姓名',
  gender VARCHAR(2) COMMENT '男/女',
  student_no VARCHAR(30) COMMENT '学号',
  class_name VARCHAR(100) COMMENT '班级',
  phone VARCHAR(20) COMMENT '联系方式',
  political_status VARCHAR(20) COMMENT '政治面貌(中文)',
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ALUMNI/RESIGNED/EXPELLED/LEFT',
  remark VARCHAR(255),
  created_by BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  CONSTRAINT fk_member_session FOREIGN KEY (session_id) REFERENCES member_session(id),
  CONSTRAINT fk_member_dept FOREIGN KEY (dept_id) REFERENCES sys_department(id),
  UNIQUE KEY uk_member_session_student (session_id, student_no)
);
```

- [ ] **Step 2: 写实体 + 仓库**

`Member.java`：
```java
package com.pams.module.member.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "member")
@SQLRestriction("deleted = 0")
public class Member {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "session_id", nullable = false)
    private Long sessionId;
    @Column(name = "dept_id")
    private Long deptId;
    @Column(nullable = false, length = 20)
    private String position;
    @Column(nullable = false, length = 50)
    private String name;
    @Column(length = 2)
    private String gender;
    @Column(name = "student_no", length = 30)
    private String studentNo;
    @Column(name = "class_name", length = 100)
    private String className;
    @Column(length = 20)
    private String phone;
    @Column(name = "political_status", length = 20)
    private String politicalStatus;
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";
    @Column(length = 255)
    private String remark;
    @Column(name = "created_by")
    private Long createdBy;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    private Integer deleted = 0;
}
```

`MemberSession.java`（同样 `@Data @Entity @Table(name="member_session") @SQLRestriction("deleted = 0")`）：
```java
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 50)
    private String name;
    @Column(name = "is_current")
    private Integer isCurrent = 0;
    @Column(name = "sort_order")
    private Integer sortOrder = 0;
    @Column(length = 255)
    private String remark;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    private Integer deleted = 0;
```

`MemberRepository.java`：
```java
package com.pams.module.member.repository;

import com.pams.module.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MemberRepository extends JpaRepository<Member, Long>, JpaSpecificationExecutor<Member> {
    List<Member> findBySessionId(Long sessionId);
    boolean existsBySessionIdAndStudentNo(Long sessionId, String studentNo);
    long countBySessionId(Long sessionId);

    @Modifying
    @Query("update Member m set m.status = 'ALUMNI', m.updatedAt = :now " +
           "where m.sessionId = :sessionId and m.status = 'ACTIVE' and m.deleted = 0")
    int archiveSession(@Param("sessionId") Long sessionId, @Param("now") LocalDateTime now);
}
```

`MemberSessionRepository.java`：
```java
package com.pams.module.member.repository;

import com.pams.module.member.entity.MemberSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MemberSessionRepository extends JpaRepository<MemberSession, Long> {
    List<MemberSession> findAllByOrderByIsCurrentDescSortOrderAscIdAsc();
    boolean existsByName(String name);

    @Modifying
    @Query("update MemberSession s set s.isCurrent = 0")
    int clearCurrentFlag();
}
```

`MemberEnums.java`（`com.pams.module.member.dto`）：
```java
package com.pams.module.member.dto;

import java.util.Map;

/** 成员职位/状态枚举：中文标签 <-> 枚举码。集中一处，导入/校验/前端共用。 */
public final class MemberEnums {
    private MemberEnums() {}

    public static final Map<String, String> POSITION_LABELS = Map.of(
        "DIRECTOR", "主任", "SUB_DIRECTOR", "副主任", "DEPT_HEAD", "部长",
        "SUB_DEPT_HEAD", "副部长", "STAFF", "干事");

    public static final Map<String, String> STATUS_LABELS = Map.of(
        "ACTIVE", "在职", "ALUMNI", "往届", "RESIGNED", "退部",
        "EXPELLED", "开除", "LEFT", "离职");

    public static boolean isPosition(String code) { return POSITION_LABELS.containsKey(code); }
    public static boolean isStatus(String code) { return STATUS_LABELS.containsKey(code); }

    /** 中文 -> 码；未知返回 null */
    public static String positionOf(String label) {
        for (var e : POSITION_LABELS.entrySet()) if (e.getValue().equals(label)) return e.getKey();
        return null;
    }
    public static String statusOf(String label) {
        for (var e : STATUS_LABELS.entrySet()) if (e.getValue().equals(label)) return e.getKey();
        return null;
    }
}
```

- [ ] **Step 3: 写仓库冒烟测试**

`MemberRepositoryTest.java`（`@SpringBootTest @ActiveProfiles("test")`，随 Spring 上下文在 H2 上执行 V13，验证迁移可跑 + 实体可存取 + 唯一键生效）：
```java
package com.pams.module.member;

import com.pams.module.member.entity.Member;
import com.pams.module.member.entity.MemberSession;
import com.pams.module.member.repository.MemberRepository;
import com.pams.module.member.repository.MemberSessionRepository;
import com.pams.repository.DepartmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class MemberRepositoryTest {

    @Autowired MemberRepository memberRepo;
    @Autowired MemberSessionRepository sessionRepo;
    @Autowired DepartmentRepository departmentRepo;

    @Test
    void sessionAndMember_persist_and_query() {
        MemberSession s = new MemberSession();
        s.setName("第九届"); s.setIsCurrent(1); s.setSortOrder(1);
        s.setCreatedAt(LocalDateTime.now()); s.setUpdatedAt(LocalDateTime.now());
        sessionRepo.save(s);

        Member m = new Member();
        m.setSessionId(s.getId());
        m.setDeptId(departmentRepo.findAll().get(0).getId());
        m.setPosition("STAFF"); m.setName("张三"); m.setGender("男");
        m.setStudentNo("20250101"); m.setStatus("ACTIVE");
        m.setCreatedAt(LocalDateTime.now()); m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);

        assertThat(memberRepo.findBySessionId(s.getId())).hasSize(1);
        assertThat(memberRepo.existsBySessionIdAndStudentNo(s.getId(), "20250101")).isTrue();
        assertThat(memberRepo.countBySessionId(s.getId())).isEqualTo(1);
    }

    @Test
    void duplicateStudentNoInSameSession_rejected() {
        MemberSession s = new MemberSession();
        s.setName("第十届"); s.setCreatedAt(LocalDateTime.now()); s.setUpdatedAt(LocalDateTime.now());
        sessionRepo.save(s);

        Member a = new Member();
        a.setSessionId(s.getId()); a.setPosition("STAFF"); a.setName("张三");
        a.setStudentNo("20250202"); a.setStatus("ACTIVE");
        a.setCreatedAt(LocalDateTime.now()); a.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(a);

        Member b = new Member();
        b.setSessionId(s.getId()); b.setPosition("STAFF"); b.setName("李四");
        b.setStudentNo("20250202"); b.setStatus("ACTIVE");
        b.setCreatedAt(LocalDateTime.now()); b.setUpdatedAt(LocalDateTime.now());
        assertThatThrownBy(() -> memberRepo.saveAndFlush(b))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void archiveSession_flipsActiveToAlumni() {
        MemberSession s = new MemberSession();
        s.setName("第八届"); s.setCreatedAt(LocalDateTime.now()); s.setUpdatedAt(LocalDateTime.now());
        sessionRepo.save(s);

        Member act = new Member();
        act.setSessionId(s.getId()); act.setPosition("STAFF"); act.setName("在职A");
        act.setStatus("ACTIVE"); act.setCreatedAt(LocalDateTime.now()); act.setUpdatedAt(LocalDateTime.now());
        Member left = new Member();
        left.setSessionId(s.getId()); left.setPosition("STAFF"); left.setName("已退B");
        left.setStatus("RESIGNED"); left.setCreatedAt(LocalDateTime.now()); left.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(act); memberRepo.save(left);

        int n = memberRepo.archiveSession(s.getId(), LocalDateTime.now());

        assertThat(n).isEqualTo(1);
        assertThat(memberRepo.findBySessionId(s.getId()))
            .filteredOn(m -> "在职A".equals(m.getName()))
            .allSatisfy(m -> assertThat(m.getStatus()).isEqualTo("ALUMNI"));
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd pams-backend && mvn test -Dtest=MemberRepositoryTest`
Expected: PASS（说明 V13 迁移在 H2 上执行成功、实体映射正确、唯一键生效、archiveSession 翻转在职→往届）。

- [ ] **Step 5: 提交**

```bash
git add pams-backend/src/main/resources/db/migration/V13__member.sql \
        pams-backend/src/main/java/com/pams/module/member/ \
        pams-backend/src/test/java/com/pams/module/member/MemberRepositoryTest.java
git commit -m "feat(member): 成员/届别实体+仓库+V13迁移（含唯一键/归档查询）"
```

---

### Task 2: 届别管理后端（MemberSessionService + MemberSessionController）

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/member/dto/MemberSessionVO.java`
- Create: `pams-backend/src/main/java/com/pams/module/member/dto/MemberSessionRequest.java`
- Create: `pams-backend/src/main/java/com/pams/module/member/service/MemberSessionService.java`
- Create: `pams-backend/src/main/java/com/pams/module/member/controller/MemberSessionController.java`
- Test: `pams-backend/src/test/java/com/pams/module/member/MemberSessionServiceTest.java`

**Interfaces:**
- Consumes: `MemberSessionRepository`、`MemberRepository`（Task 1）、`BizException`/`Result`。
- Produces: 端点 `GET/POST /api/member-sessions`、`PUT/DELETE /api/member-sessions/{id}`、`POST /api/member-sessions/{id}/set-current`；`MemberSessionVO(Long id, String name, Integer isCurrent, Integer sortOrder, String remark)`；请求 `MemberSessionRequest(String name, Integer isCurrent, Integer sortOrder, String remark)`。

- [ ] **Step 1: 写失败测试**

`MemberSessionServiceTest.java`（Mockito 单测，风格同 RosterImportServiceTest —— `mock` 仓库、构造注入）：
```java
package com.pams.module.member;

import com.pams.common.BizException;
import com.pams.module.member.entity.MemberSession;
import com.pams.module.member.repository.MemberRepository;
import com.pams.module.member.repository.MemberSessionRepository;
import com.pams.module.member.service.MemberSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MemberSessionServiceTest {

    MemberSessionRepository sessionRepo;
    MemberRepository memberRepo;
    MemberSessionService service;

    @BeforeEach
    void setup() {
        sessionRepo = mock(MemberSessionRepository.class);
        memberRepo = mock(MemberRepository.class);
        service = new MemberSessionService(sessionRepo, memberRepo);
    }

    @Test
    void create_rejectsDuplicateName() {
        when(sessionRepo.existsByName("第九届")).thenReturn(true);
        assertThatThrownBy(() -> service.create("第九届", 1, 1, null))
            .isInstanceOf(BizException.class).hasMessageContaining("届别");
    }

    @Test
    void delete_blocksWhenMembersExist() {
        MemberSession s = new MemberSession(); s.setId(1L);
        when(sessionRepo.findById(1L)).thenReturn(Optional.of(s));
        when(memberRepo.countBySessionId(1L)).thenReturn(5L);
        assertThatThrownBy(() -> service.delete(1L))
            .isInstanceOf(BizException.class).hasMessageContaining("成员");
        verify(sessionRepo, never()).delete(s);
    }

    @Test
    void setCurrent_clearsOthersThenSetsTarget() {
        when(sessionRepo.findById(2L)).thenReturn(Optional.of(new MemberSession()));
        service.setCurrent(2L);
        verify(sessionRepo).clearCurrentFlag();
        verify(sessionRepo).save(any(MemberSession.class));
    }

    @Test
    void list_ordersCurrentFirst() {
        MemberSession old = new MemberSession(); old.setName("第八届"); old.setIsCurrent(0);
        MemberSession cur = new MemberSession(); cur.setName("第九届"); cur.setIsCurrent(1);
        when(sessionRepo.findAllByOrderByIsCurrentDescSortOrderAscIdAsc()).thenReturn(List.of(cur, old));
        var list = service.list();
        assertThat(list.get(0).getName()).isEqualTo("第九届");
        assertThat(list.get(1).getName()).isEqualTo("第八届");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd pams-backend && mvn test -Dtest=MemberSessionServiceTest`
Expected: FAIL（`MemberSessionService` 不存在，编译错误）。

- [ ] **Step 3: 实现 Service + DTO**

`MemberSessionVO.java` / `MemberSessionRequest.java`（record，字段见 Interfaces）。

`MemberSessionService.java`：
```java
package com.pams.module.member.service;

import com.pams.common.BizException;
import com.pams.module.member.dto.MemberSessionRequest;
import com.pams.module.member.dto.MemberSessionVO;
import com.pams.module.member.entity.MemberSession;
import com.pams.module.member.repository.MemberRepository;
import com.pams.module.member.repository.MemberSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MemberSessionService {
    private final MemberSessionRepository sessionRepo;
    private final MemberRepository memberRepo;

    public MemberSessionService(MemberSessionRepository sessionRepo, MemberRepository memberRepo) {
        this.sessionRepo = sessionRepo;
        this.memberRepo = memberRepo;
    }

    public List<MemberSessionVO> list() {
        return sessionRepo.findAllByOrderByIsCurrentDescSortOrderAscIdAsc().stream()
                .map(this::toVO).toList();
    }

    @Transactional
    public Long create(MemberSessionRequest req) {
        if (sessionRepo.existsByName(req.name().trim())) {
            throw new BizException(2801, "届别名称已存在");
        }
        MemberSession s = new MemberSession();
        apply(s, req);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        return sessionRepo.save(s).getId();
    }

    @Transactional
    public void update(Long id, MemberSessionRequest req) {
        MemberSession s = sessionRepo.findById(id)
                .orElseThrow(() -> new BizException(2802, "届别不存在"));
        if (!s.getName().equals(req.name().trim()) && sessionRepo.existsByName(req.name().trim())) {
            throw new BizException(2801, "届别名称已存在");
        }
        apply(s, req);
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepo.save(s);
    }

    @Transactional
    public void delete(Long id) {
        MemberSession s = sessionRepo.findById(id)
                .orElseThrow(() -> new BizException(2802, "届别不存在"));
        if (memberRepo.countBySessionId(id) > 0) {
            throw new BizException(2803, "该届别下已有成员，不能删除");
        }
        s.setDeleted(1);
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepo.save(s);
    }

    @Transactional
    public void setCurrent(Long id) {
        MemberSession s = sessionRepo.findById(id)
                .orElseThrow(() -> new BizException(2802, "届别不存在"));
        sessionRepo.clearCurrentFlag();
        s.setIsCurrent(1);
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepo.save(s);
    }

    private void apply(MemberSession s, MemberSessionRequest req) {
        s.setName(req.name().trim());
        s.setIsCurrent(req.isCurrent() == null ? 0 : req.isCurrent());
        s.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        s.setRemark(req.remark());
    }

    private MemberSessionVO toVO(MemberSession s) {
        return new MemberSessionVO(s.getId(), s.getName(), s.getIsCurrent(),
                s.getSortOrder(), s.getRemark());
    }
}
```

- [ ] **Step 4: 实现 Controller**

`MemberSessionController.java`（类级 `@PreAuthorize(LEADER)`）：
```java
package com.pams.module.member.controller;

import com.pams.common.Result;
import com.pams.module.member.dto.MemberSessionRequest;
import com.pams.module.member.dto.MemberSessionVO;
import com.pams.module.member.service.MemberSessionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/member-sessions")
@PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
public class MemberSessionController {
    private final MemberSessionService service;
    public MemberSessionController(MemberSessionService service) { this.service = service; }

    @GetMapping
    public Result<List<MemberSessionVO>> list() { return Result.ok(service.list()); }

    @PostMapping
    public Result<Long> create(@RequestBody MemberSessionRequest req) { return Result.ok(service.create(req)); }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody MemberSessionRequest req) {
        service.update(id, req); return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) { service.delete(id); return Result.ok(); }

    @PostMapping("/{id}/set-current")
    public Result<Void> setCurrent(@PathVariable Long id) { service.setCurrent(id); return Result.ok(); }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd pams-backend && mvn test -Dtest=MemberSessionServiceTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add pams-backend/src/main/java/com/pams/module/member/
git commit -m "feat(member): 届别管理（列表/增改删/设为当前届，删除保护）"
```

---

### Task 3: 成员 CRUD + 分页 + 统计 + 换届归档（MemberService + MemberController）

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/member/dto/MemberVO.java`
- Create: `pams-backend/src/main/java/com/pams/module/member/dto/MemberRequest.java`
- Create: `pams-backend/src/main/java/com/pams/module/member/dto/MemberDetailVO.java`
- Create: `pams-backend/src/main/java/com/pams/module/member/dto/MemberStatsVO.java`
- Create: `pams-backend/src/main/java/com/pams/module/member/service/MemberService.java`
- Create: `pams-backend/src/main/java/com/pams/module/member/controller/MemberController.java`
- Test: `pams-backend/src/test/java/com/pams/module/member/MemberServiceTest.java`

**Interfaces:**
- Consumes: `MemberRepository`（含 `archiveSession`）、`MemberSessionRepository`、`DepartmentRepository`、`CreditRecordRepository`/`AttendanceRepository`/`SchedulePersonRepository`（详情聚合）、`MemberEnums`。
- Produces:
  - `MemberVO(Long id, Long sessionId, String sessionName, Long deptId, String deptName, String position, String positionLabel, String name, String gender, String studentNo, String className, String phone, String politicalStatus, String status, String statusLabel, String remark, LocalDateTime createdAt, LocalDateTime updatedAt)`
  - `MemberRequest(Long sessionId, Long deptId, String position, String name, String gender, String studentNo, String className, String phone, String politicalStatus, String status, String remark)`
  - `MemberDetailVO(MemberVO member, long scheduleCount, long attendanceCount, BigDecimal totalCredit, List<MemberCreditVO> credits)`；`MemberCreditVO(Long id, String project, BigDecimal credit, String basis, String remark, LocalDateTime createdAt)`
  - `MemberStatsVO(long total, List<NameCount> byDept, List<NameCount> byPosition, List<NameCount> byStatus)`；嵌套 `record NameCount(String name, long count)`
  - 端点（类级 `@PreAuthorize(LEADER)`，全部 `Result<...>`）：`GET /api/members`（分页）、`GET /api/members/stats`、`GET /api/members/{id}`、`POST /api/members`、`PUT /api/members/{id}`、`DELETE /api/members/{id}`、`POST /api/members/batch-delete`、`POST /api/members/{sessionId}/archive`。
- 仓库派生方法需新增（在 Task 1 的 MemberRepository 里没有）：
  - `CreditRecordRepository`：`List<CreditRecord> findByStudentNoOrderByCreatedAtDesc(String studentNo)`；`AttendanceRepository`：`long countByPersonName(String personName)`；`SchedulePersonRepository`：`long countByPersonName(String personName)`。
  - `UserRepository`（Task 5 用，一并加）：`List<User> findByStudentNo(String studentNo)`。

- [ ] **Step 1: 写失败测试**

`MemberServiceTest.java`（Mockito）：
```java
package com.pams.module.member;

import com.pams.common.BizException;
import com.pams.entity.Department;
import com.pams.module.archive.entity.CreditRecord;
import com.pams.module.archive.repository.CreditRecordRepository;
import com.pams.module.member.dto.MemberRequest;
import com.pams.module.member.entity.Member;
import com.pams.module.member.entity.MemberSession;
import com.pams.module.member.repository.MemberRepository;
import com.pams.module.member.repository.MemberSessionRepository;
import com.pams.module.member.service.MemberService;
import com.pams.module.routine.repository.AttendanceRepository;
import com.pams.module.routine.repository.SchedulePersonRepository;
import com.pams.repository.DepartmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemberServiceTest {

    MemberRepository memberRepo;
    MemberSessionRepository sessionRepo;
    DepartmentRepository deptRepo;
    CreditRecordRepository creditRepo;
    AttendanceRepository attRepo;
    SchedulePersonRepository spRepo;
    MemberService service;

    @BeforeEach
    void setup() {
        memberRepo = mock(MemberRepository.class);
        sessionRepo = mock(MemberSessionRepository.class);
        deptRepo = mock(DepartmentRepository.class);
        creditRepo = mock(CreditRecordRepository.class);
        attRepo = mock(AttendanceRepository.class);
        spRepo = mock(SchedulePersonRepository.class);
        service = new MemberService(memberRepo, sessionRepo, deptRepo, creditRepo, attRepo, spRepo);
    }

    @Test
    void create_validatesPositionAndStatusAndDuplicate() {
        MemberSession s = new MemberSession(); s.setId(1L); s.setName("第九届");
        when(sessionRepo.findById(1L)).thenReturn(Optional.of(s));
        when(memberRepo.existsBySessionIdAndStudentNo(1L, "20250101")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new MemberRequest(1L, null, "BAD_POS",
                "张三", "男", "20250101", "班", "123", "共青团员", "ACTIVE", null), 1L))
                .isInstanceOf(BizException.class).hasMessageContaining("职位");
        assertThatThrownBy(() -> service.create(new MemberRequest(1L, null, "STAFF",
                "张三", "男", "20250101", "班", "123", "共青团员", "ACTIVE", null), 1L))
                .isInstanceOf(BizException.class).hasMessageContaining("学号");
    }

    @Test
    void archive_returnsCount() {
        when(memberRepo.archiveSession(1L, any(LocalDateTime.class))).thenReturn(12);
        assertThat(service.archive(1L)).isEqualTo(12);
    }

    @Test
    void stats_groupsByDeptPositionStatus() {
        Department d = new Department(); d.setId(2L); d.setName("文秘部");
        when(deptRepo.findAll()).thenReturn(List.of(d));

        Member m1 = new Member(); m1.setDeptId(2L); m1.setPosition("DEPT_HEAD"); m1.setStatus("ACTIVE");
        Member m2 = new Member(); m2.setDeptId(null); m2.setPosition("DIRECTOR"); m2.setStatus("ACTIVE");
        Member m3 = new Member(); m3.setDeptId(2L); m3.setPosition("STAFF"); m3.setStatus("RESIGNED");
        when(memberRepo.findBySessionId(1L)).thenReturn(List.of(m1, m2, m3));

        var stats = service.stats(1L);

        assertThat(stats.total()).isEqualTo(3);
        assertThat(stats.byDept()).anyMatch(nc -> nc.name().equals("文秘部") && nc.count() == 2);
        assertThat(stats.byDept()).anyMatch(nc -> nc.name().equals("主任室") && nc.count() == 1);
        assertThat(stats.byPosition()).anyMatch(nc -> nc.name().equals("部长") && nc.count() == 1);
        assertThat(stats.byStatus()).anyMatch(nc -> nc.name().equals("在职") && nc.count() == 2);
    }

    @Test
    void detail_aggregatesCreditByNameAndStudentNo() {
        Member m = new Member(); m.setId(9L); m.setSessionId(1L); m.setPosition("STAFF");
        m.setName("李想"); m.setStudentNo("2435101020101"); m.setStatus("ACTIVE");
        when(memberRepo.findById(9L)).thenReturn(Optional.of(m));
        when(spRepo.countByPersonName("李想")).thenReturn(3L);
        when(attRepo.countByPersonName("李想")).thenReturn(2L);
        CreditRecord c = new CreditRecord();
        c.setProject("参加培训班"); c.setCredit(new BigDecimal("2.00"));
        c.setBasis("PARTICIPATE"); c.setRemark("合格"); c.setCreatedAt(LocalDateTime.now());
        when(creditRepo.findByStudentNoOrderByCreatedAtDesc("2435101020101")).thenReturn(List.of(c));

        var detail = service.detail(9L);

        assertThat(detail.scheduleCount()).isEqualTo(3);
        assertThat(detail.attendanceCount()).isEqualTo(2);
        assertThat(detail.totalCredit()).isEqualByComparingTo("2.00");
        assertThat(detail.credits()).hasSize(1);
    }

    @Test
    void delete_softDeletes() {
        Member m = new Member(); m.setId(5L); m.setDeleted(0);
        when(memberRepo.findById(5L)).thenReturn(Optional.of(m));
        service.delete(5L);
        assertThat(m.getDeleted()).isEqualTo(1);
        verify(memberRepo).save(m);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd pams-backend && mvn test -Dtest=MemberServiceTest`
Expected: FAIL（`MemberService` 不存在）。

- [ ] **Step 3: 加仓库派生方法**

在 `CreditRecordRepository`（`module/archive/repository/`）加：
```java
List<CreditRecord> findByStudentNoOrderByCreatedAtDesc(String studentNo);
```
在 `AttendanceRepository`（`module/routine/repository/`）加：
```java
long countByPersonName(String personName);
```
在 `SchedulePersonRepository`（`module/routine/repository/`）加：
```java
long countByPersonName(String personName);
```

- [ ] **Step 4: 实现 MemberService**

`MemberService.java`（要点：Specification 分页、枚举校验、软删、统计分组、详情聚合）：
```java
package com.pams.module.member.service;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.entity.Department;
import com.pams.module.archive.entity.CreditRecord;
import com.pams.module.archive.repository.CreditRecordRepository;
import com.pams.module.member.dto.*;
import com.pams.module.member.entity.Member;
import com.pams.module.member.entity.MemberSession;
import com.pams.module.member.repository.MemberRepository;
import com.pams.module.member.repository.MemberSessionRepository;
import com.pams.module.routine.repository.AttendanceRepository;
import com.pams.module.routine.repository.SchedulePersonRepository;
import com.pams.repository.DepartmentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MemberService {
    private final MemberRepository memberRepo;
    private final MemberSessionRepository sessionRepo;
    private final DepartmentRepository deptRepo;
    private final CreditRecordRepository creditRepo;
    private final AttendanceRepository attRepo;
    private final SchedulePersonRepository spRepo;

    public MemberService(MemberRepository memberRepo, MemberSessionRepository sessionRepo,
                         DepartmentRepository deptRepo, CreditRecordRepository creditRepo,
                         AttendanceRepository attRepo, SchedulePersonRepository spRepo) {
        this.memberRepo = memberRepo; this.sessionRepo = sessionRepo;
        this.deptRepo = deptRepo; this.creditRepo = creditRepo;
        this.attRepo = attRepo; this.spRepo = spRepo;
    }

    public PageResult<MemberVO> page(Long sessionId, Long deptId, String position, String status,
                                     String keyword, int page, int size) {
        Specification<Member> spec = (root, q, cb) -> {
            var preds = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (sessionId != null) preds.add(cb.equal(root.get("sessionId"), sessionId));
            if (deptId != null) preds.add(cb.equal(root.get("deptId"), deptId));
            if (position != null && !position.isBlank()) preds.add(cb.equal(root.get("position"), position));
            if (status != null && !status.isBlank()) preds.add(cb.equal(root.get("status"), status));
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                preds.add(cb.or(cb.like(root.get("name"), like),
                                cb.like(root.get("studentNo"), like),
                                cb.like(root.get("phone"), like)));
            }
            return cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        var p = memberRepo.findAll(spec, PageRequest.of(page - 1, size, Sort.by(Sort.Direction.ASC, "id")));
        PageResult<MemberVO> r = new PageResult<>();
        r.setRecords(p.getContent().stream().map(this::toVO).toList());
        r.setTotal(p.getTotalElements()); r.setCurrent(page); r.setSize(size);
        return r;
    }

    @Transactional
    public Long create(MemberRequest req, Long currentUserId) {
        validate(req);
        if (req.studentNo() != null && !req.studentNo().isBlank()
                && memberRepo.existsBySessionIdAndStudentNo(req.sessionId(), req.studentNo().trim())) {
            throw new BizException(2804, "该届别下学号已存在");
        }
        Member m = new Member();
        apply(m, req);
        m.setCreatedBy(currentUserId);
        m.setCreatedAt(LocalDateTime.now());
        m.setUpdatedAt(LocalDateTime.now());
        return memberRepo.save(m).getId();
    }

    @Transactional
    public void update(Long id, MemberRequest req) {
        Member m = memberRepo.findById(id).orElseThrow(() -> new BizException(2805, "成员不存在"));
        validate(req);
        if (req.studentNo() != null && !req.studentNo().isBlank()
                && !m.getStudentNo().equals(req.studentNo().trim())
                && memberRepo.existsBySessionIdAndStudentNo(req.sessionId(), req.studentNo().trim())) {
            throw new BizException(2804, "该届别下学号已存在");
        }
        apply(m, req);
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);
    }

    @Transactional
    public void delete(Long id) {
        Member m = memberRepo.findById(id).orElseThrow(() -> new BizException(2805, "成员不存在"));
        m.setDeleted(1);
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);
    }

    @Transactional
    public void batchDelete(List<Long> ids) {
        if (ids == null) return;
        for (Long id : ids) { memberRepo.findById(id).ifPresent(m -> { m.setDeleted(1); memberRepo.save(m); }); }
    }

    @Transactional
    public int archive(Long sessionId) {
        return memberRepo.archiveSession(sessionId, LocalDateTime.now());
    }

    public MemberStatsVO stats(Long sessionId) {
        List<Member> members = memberRepo.findBySessionId(sessionId);
        Map<Long, String> deptNames = deptRepo.findAll().stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        String deptLabel = m -> m.getDeptId() == null ? "主任室" : deptNames.getOrDefault(m.getDeptId(), "未知");
        String posLabel = m -> MemberEnums.POSITION_LABELS.getOrDefault(m.getPosition(), m.getPosition());
        String stLabel = m -> MemberEnums.STATUS_LABELS.getOrDefault(m.getStatus(), m.getStatus());
        return new MemberStatsVO(
                members.size(),
                group(members, deptLabel), group(members, posLabel), group(members, stLabel));
    }

    public MemberDetailVO detail(Long id) {
        Member m = memberRepo.findById(id).orElseThrow(() -> new BizException(2805, "成员不存在"));
        String name = m.getName();
        long scheduleCount = spRepo.countByPersonName(name);
        long attendanceCount = attRepo.countByPersonName(name);
        List<CreditRecord> credits = (m.getStudentNo() == null || m.getStudentNo().isBlank())
                ? List.of() : creditRepo.findByStudentNoOrderByCreatedAtDesc(m.getStudentNo());
        BigDecimal total = credits.stream().map(c -> c.getCredit() == null ? BigDecimal.ZERO : c.getCredit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new MemberDetailVO(toVO(m), scheduleCount, attendanceCount, total,
                credits.stream().map(c -> new MemberCreditVO(c.getId(), c.getProject(), c.getCredit(),
                        c.getBasis(), c.getRemark(), c.getCreatedAt())).toList());
    }

    // ===== helpers =====

    private List<MemberStatsVO.NameCount> group(List<Member> members, Function<Member, String> key) {
        return members.stream().collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().map(e -> new MemberStatsVO.NameCount(e.getKey(), e.getValue())).toList();
    }

    private void validate(MemberRequest req) {
        if (req.sessionId() == null || sessionRepo.findById(req.sessionId()).isEmpty()) {
            throw new BizException(2806, "届别不存在");
        }
        if (!MemberEnums.isPosition(req.position())) throw new BizException(2807, "职位无效");
        if (req.name() == null || req.name().isBlank()) throw new BizException(2808, "姓名必填");
        if (req.status() != null && !MemberEnums.isStatus(req.status())) throw new BizException(2809, "状态无效");
    }

    private void apply(Member m, MemberRequest req) {
        m.setSessionId(req.sessionId());
        m.setDeptId(req.deptId());
        m.setPosition(req.position());
        m.setName(req.name().trim());
        m.setGender(req.gender());
        m.setStudentNo(req.studentNo() == null ? null : req.studentNo().trim());
        m.setClassName(req.className());
        m.setPhone(req.phone());
        m.setPoliticalStatus(req.politicalStatus());
        m.setStatus(req.status() == null ? "ACTIVE" : req.status());
        m.setRemark(req.remark());
    }

    public MemberVO toVO(Member m) {
        String sessionName = sessionRepo.findById(m.getSessionId()).map(MemberSession::getName).orElse(null);
        String deptName = m.getDeptId() == null ? null
                : deptRepo.findById(m.getDeptId()).map(Department::getName).orElse(null);
        return new MemberVO(m.getId(), m.getSessionId(), sessionName, m.getDeptId(), deptName,
                m.getPosition(), MemberEnums.POSITION_LABELS.getOrDefault(m.getPosition(), m.getPosition()),
                m.getName(), m.getGender(), m.getStudentNo(), m.getClassName(), m.getPhone(),
                m.getPoliticalStatus(), m.getStatus(),
                MemberEnums.STATUS_LABELS.getOrDefault(m.getStatus(), m.getStatus()),
                m.getRemark(), m.getCreatedAt(), m.getUpdatedAt());
    }
}
```

- [ ] **Step 5: 实现 Controller**

`MemberController.java`（含 stats/detail/CRUD/batch/archive；import/export/template/账号导入端点 Task 4/5 再加，本任务先不写）：
```java
package com.pams.module.member.controller;

import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.member.dto.MemberDetailVO;
import com.pams.module.member.dto.MemberRequest;
import com.pams.module.member.dto.MemberStatsVO;
import com.pams.module.member.dto.MemberVO;
import com.pams.module.member.service.MemberService;
import com.pams.security.LoginUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/members")
@PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
public class MemberController {
    private final MemberService service;
    public MemberController(MemberService service) { this.service = service; }

    @GetMapping
    public Result<PageResult<MemberVO>> page(
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(service.page(sessionId, deptId, position, status, keyword, page, size));
    }

    @GetMapping("/stats")
    public Result<MemberStatsVO> stats(@RequestParam(required = false) Long sessionId) {
        return Result.ok(service.stats(sessionId));
    }

    @GetMapping("/{id}")
    public Result<MemberDetailVO> detail(@PathVariable Long id) {
        return Result.ok(service.detail(id));
    }

    @PostMapping
    public Result<Long> create(@RequestBody MemberRequest req, @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.create(req, current == null ? null : current.getId()));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody MemberRequest req) {
        service.update(id, req); return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) { service.delete(id); return Result.ok(); }

    @PostMapping("/batch-delete")
    public Result<Void> batchDelete(@RequestBody Map<String, List<Long>> body) {
        service.batchDelete(body.get("ids")); return Result.ok();
    }

    @PostMapping("/{sessionId}/archive")
    public Result<Map<String, Integer>> archive(@PathVariable Long sessionId) {
        return Result.ok(Map.of("count", service.archive(sessionId)));
    }
}
```

DTO 文件（record，字段见 Interfaces）：
```java
// MemberVO.java
public record MemberVO(Long id, Long sessionId, String sessionName, Long deptId, String deptName,
  String position, String positionLabel, String name, String gender, String studentNo,
  String className, String phone, String politicalStatus, String status, String statusLabel,
  String remark, LocalDateTime createdAt, LocalDateTime updatedAt) {}

// MemberRequest.java
public record MemberRequest(Long sessionId, Long deptId, String position, String name, String gender,
  String studentNo, String className, String phone, String politicalStatus, String status, String remark) {}

// MemberDetailVO.java
public record MemberDetailVO(MemberVO member, long scheduleCount, long attendanceCount,
  BigDecimal totalCredit, List<MemberCreditVO> credits) {
  public record MemberCreditVO(Long id, String project, BigDecimal credit, String basis,
    String remark, LocalDateTime createdAt) {}
}

// MemberStatsVO.java
public record MemberStatsVO(long total, List<NameCount> byDept, List<NameCount> byPosition,
  List<NameCount> byStatus) {
  public record NameCount(String name, long count) {}
}
```

- [ ] **Step 6: 跑测试确认通过**

Run: `cd pams-backend && mvn test -Dtest=MemberServiceTest`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add pams-backend/src/main/java/com/pams/module/member/ \
        pams-backend/src/main/java/com/pams/module/archive/repository/CreditRecordRepository.java \
        pams-backend/src/main/java/com/pams/module/routine/repository/AttendanceRepository.java \
        pams-backend/src/main/java/com/pams/module/routine/repository/SchedulePersonRepository.java
git commit -m "feat(member): 成员CRUD+分页+统计+详情聚合+换届归档"
```

---

### Task 4: 成员 Excel 导入 / 模板下载 / 导出（MemberImportService）

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/member/dto/MemberImportResultVO.java`
- Create: `pams-backend/src/main/java/com/pams/module/member/service/MemberImportService.java`
- Modify: `pams-backend/src/main/java/com/pams/module/member/controller/MemberController.java`（加 import/template/export 端点）
- Test: `pams-backend/src/test/java/com/pams/module/member/MemberImportServiceTest.java`

**Interfaces:**
- Consumes: `MemberRepository`（`findBySessionId`/`existsBySessionIdAndStudentNo`）、`DepartmentRepository`、`MemberEnums`；POI `WorkbookFactory`。
- Produces:
  - `MemberImportResultVO(int total, int success, int skipped, List<MemberImportFailureVO> failed)`；`MemberImportFailureVO(int row, String name, String reason)`
  - `MemberImportService.importFromXlsx(InputStream in, Long sessionId)` → `MemberImportResultVO`
  - `MemberImportService.buildTemplate()` → `byte[]`（xlsx，9 列表头）
  - `MemberImportService.exportXlsx(Long sessionId, Long deptId, String position, String status, String keyword)` → `byte[]`
  - 端点：`POST /api/members/import`（multipart `sessionId`+`file`）、`GET /api/members/import/template`、`GET /api/members/export`（blob）。
  - 复用 `MemberService.page`（导出用）与 `MemberService.toVO`。

- [ ] **Step 1: 写失败测试**

`MemberImportServiceTest.java`（Mockito + POI 造 xlsx，风格同 RosterImportServiceTest 的 `buildXlsx`；注意**部门列合并单元格前向填充**用真实合并单元格模拟）：
```java
package com.pams.module.member;

import com.pams.entity.Department;
import com.pams.module.member.dto.MemberImportResultVO;
import com.pams.module.member.repository.MemberRepository;
import com.pams.module.member.service.MemberImportService;
import com.pams.repository.DepartmentRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemberImportServiceTest {

    MemberRepository memberRepo;
    DepartmentRepository deptRepo;
    MemberImportService service;

    @BeforeEach
    void setup() {
        memberRepo = mock(MemberRepository.class);
        deptRepo = mock(DepartmentRepository.class);
        service = new MemberImportService(memberRepo, deptRepo);
        Department d = new Department(); d.setId(2L); d.setName("文秘部");
        when(deptRepo.findAll()).thenReturn(List.of(d));
        when(memberRepo.findBySessionId(1L)).thenReturn(List.of());
        when(memberRepo.existsBySessionIdAndStudentNo(1L, "20250101")).thenReturn(true);
    }

    /** 构造：标题行 + 表头 + 数据（部门列用合并单元格：第2行部门=文秘部，第3行部门留空） */
    private byte[] rosterXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("Sheet1");
            String[][] rows = {
                {"第九届信息工程学院党建办公室干部干事信息登记表"},
                {"序号", "部门", "职位", "姓名", "性别", "学号", "班级", "联系方式", "政治面貌"},
                {"1", "文秘部", "部长", "吴苑", "女", "2435101020120", "24物联网班", "15907536461", "共青团员"},
                {"2", "", "干事", "谢文杰", "男", "2535102010537", "25计应5班", "13556493207", "群众"},
                {"3", "组织部", "干事", "蔡键泽", "男", "2535102030201", "25软件技术2班", "15219326575", "共青团员"},
            };
            for (int i = 0; i < rows.length; i++) {
                Row r = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) r.createCell(j).setCellValue(rows[i][j]);
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { wb.write(out); return out.toByteArray(); }
        }
    }

    @Test
    void import_parsesAndForwardFillsDept() throws Exception {
        var r = service.importFromXlsx(new ByteArrayInputStream(rosterXlsx()), 1L);
        assertThat(r.success()).isEqualTo(2);   // 吴苑重复(学号已存在)、谢文杰、蔡键泽 → 3 行中 1 重复
        assertThat(r.skipped()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(memberRepo).saveAll(captor.capture());
        var saved = new java.util.ArrayList<com.pams.module.member.entity.Member>();
        captor.getValue().forEach(e -> saved.add((com.pams.module.member.entity.Member) e));
        // 谢文杰部门为空，前向填充为 文秘部
        var xie = saved.stream().filter(m -> m.getName().equals("谢文杰")).findFirst().orElseThrow();
        assertThat(xie.getDeptId()).isEqualTo(2L);
        assertThat(xie.getPosition()).isEqualTo("STAFF");
        assertThat(xie.getStatus()).isEqualTo("ACTIVE");
        var cai = saved.stream().filter(m -> m.getName().equals("蔡键泽")).findFirst().orElseThrow();
        assertThat(cai.getDeptId()).isEqualTo(2L); // 组织部未 mock 在 deptRepo，落入兜底逻辑（见实现说明）
    }

    @Test
    void import_unknownPosition_reportsFailureRow() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("S");
            sheet.createRow(0).createCell(0).setCellValue("姓名");
            sheet.createRow(1).createCell(0).setCellValue("张三");
            // 只有姓名列，职位列缺失 → 职位无法识别
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { wb.write(out);
                var r = service.importFromXlsx(new ByteArrayInputStream(out.toByteArray()), 1L);
                assertThat(r.failed()).hasSize(1);
                assertThat(r.failed().get(0).reason()).contains("职位");
            }
        }
    }

    @Test
    void import_blankNameSkipsRow() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("S");
            sheet.createRow(0).createCell(0).setCellValue("姓名");
            sheet.createRow(1).createCell(0).setCellValue("");
            sheet.createRow(2).createCell(0).setCellValue("张三");
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { wb.write(out);
                var r = service.importFromXlsx(new ByteArrayInputStream(out.toByteArray()), 1L);
                assertThat(r.success()).isEqualTo(1);
                assertThat(r.failed()).isEmpty();
            }
        }
    }
}
```

> 注：上面 `import_parsesAndForwardFillsDept` 中「组织部」未 mock 进 deptRepo，因此蔡键泽的 deptId 解析结果取决于实现。若实现为「未知部门记失败行」，则 expected 需调整。**建议实现**：部门名精确匹配 `deptRepo.findAll()`；匹配不上且部门名非空 → 记失败行「部门无法识别」；部门空/「主任」/「副主任」/「主任室」→ deptId null。为让测试稳定，请把组织部也加入 `deptRepo` mock（`d2.setName("组织部")`），再断言蔡键泽 deptId = 组织部 id。若你选择「未知部门失败」实现，则示例测试需同步改期望。以你写出的实现为准，保证测试与实现一致。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd pams-backend && mvn test -Dtest=MemberImportServiceTest`
Expected: FAIL（`MemberImportService` 不存在）。

- [ ] **Step 3: 实现 MemberImportService**

`MemberImportService.java`（核心：表头定位、部门前向填充、职位/部门/政治面貌归一化、去重、失败行收集、模板生成、导出）：
```java
package com.pams.module.member.service;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.entity.Department;
import com.pams.module.member.dto.MemberImportResultVO;
import com.pams.module.member.dto.MemberEnums;
import com.pams.module.member.dto.MemberVO;
import com.pams.module.member.entity.Member;
import com.pams.module.member.repository.MemberRepository;
import com.pams.repository.DepartmentRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class MemberImportService {
    private final MemberRepository memberRepo;
    private final DepartmentRepository deptRepo;
    private final MemberService memberService;

    public MemberImportService(MemberRepository memberRepo, DepartmentRepository deptRepo) {
        this(memberRepo, deptRepo, null);
    }
    public MemberImportService(MemberRepository memberRepo, DepartmentRepository deptRepo,
                               MemberService memberService) {
        this.memberRepo = memberRepo; this.deptRepo = deptRepo; this.memberService = memberService;
    }

    /** 模板列（与用户登记表一致）；导出在末尾追加「状态」列 */
    private static final String[] HEADERS = {"序号", "部门", "职位", "姓名", "性别", "学号", "班级", "联系方式", "政治面貌"};
    private static final Set<String> NO_DEPT = Set.of("", "主任", "副主任", "主任室");

    @Transactional
    public MemberImportResultVO importFromXlsx(InputStream in, Long sessionId) {
        if (sessionId == null) throw new BizException(2810, "请选择届别");
        Map<String, Long> deptIdByName = new HashMap<>();
        deptRepo.findAll().forEach(d -> deptIdByName.put(d.getName(), d.getId()));

        List<Member> toSave = new ArrayList<>();
        List<MemberImportResultVO.MemberImportFailureVO> failed = new ArrayList<>();
        List<Member> existing = memberRepo.findBySessionId(sessionId);
        Set<String> seen = new HashSet<>();
        existing.forEach(m -> seen.add(key(sessionId, m.getStudentNo())));

        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = firstDataSheet(wb);
            Row header = findHeaderRow(sheet);
            Map<String, Integer> col = locateColumns(header);

            String lastDeptName = "";
            for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                String name = cellStr(optionalCell(r, col, "name"));
                String studentNo = cellStr(optionalCell(r, col, "studentNo"));
                if (isBlank(name) && isBlank(studentNo)) continue; // 空行

                String deptName = cellStr(optionalCell(r, col, "dept"));
                if (deptName != null && !deptName.isBlank()) lastDeptName = deptName; // 合并单元格前向填充
                String posLabel = cellStr(optionalCell(r, col, "position"));

                int excelRowNo = i + 1;
                if (isBlank(name)) { failed.add(new MemberImportResultVO.MemberImportFailureVO(excelRowNo, "", "姓名缺失")); continue; }
                String position = MemberEnums.positionOf(posLabel == null ? "" : posLabel);
                if (position == null) { failed.add(new MemberImportResultVO.MemberImportFailureVO(excelRowNo, name, "职位无法识别: " + posLabel)); continue; }
                Long deptId = null;
                String dName = lastDeptName == null ? "" : lastDeptName.trim();
                if (!NO_DEPT.contains(dName)) {
                    deptId = deptIdByName.get(dName);
                    if (deptId == null) { failed.add(new MemberImportResultVO.MemberImportFailureVO(excelRowNo, name, "部门无法识别: " + dName)); continue; }
                }

                String no = studentNo == null ? "" : studentNo.trim();
                if (no.isEmpty()) { failed.add(new MemberImportResultVO.MemberImportFailureVO(excelRowNo, name, "学号缺失（一键建账号需要学号）")); continue; }
                if (!seen.add(key(sessionId, no))) { failed.add(new MemberImportResultVO.MemberImportFailureVO(excelRowNo, name, "学号已存在")); continue; }

                Member m = new Member();
                m.setSessionId(sessionId);
                m.setDeptId(deptId);
                m.setPosition(position);
                m.setName(name);
                m.setGender(cellStr(optionalCell(r, col, "gender")));
                m.setStudentNo(no);
                m.setClassName(cellStr(optionalCell(r, col, "className")));
                m.setPhone(cellStr(optionalCell(r, col, "phone")));
                String pol = cellStr(optionalCell(r, col, "political"));
                m.setPoliticalStatus("团员".equals(pol) ? "共青团员" : pol);
                m.setStatus("ACTIVE");
                m.setCreatedAt(LocalDateTime.now());
                m.setUpdatedAt(LocalDateTime.now());
                toSave.add(m);
            }
        } catch (IOException e) {
            throw new BizException(4001, "名单文件解析失败");
        }

        if (!toSave.isEmpty()) memberRepo.saveAll(toSave);
        int total = toSave.size() + failed.size();
        return new MemberImportResultVO(total, toSave.size(), 0, failed);
    }

    public byte[] buildTemplate() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("成员信息");
            Row h = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) h.createCell(i).setCellValue(HEADERS[i]);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { wb.write(out); return out.toByteArray(); }
        }
    }

    public byte[] exportXlsx(Long sessionId, Long deptId, String position, String status, String keyword) throws IOException {
        PageResult<MemberVO> page = memberService.page(sessionId, deptId, position, status, keyword, 1, 100000);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("成员信息");
            Row h = sheet.createRow(0);
            String[] cols = {"序号", "部门", "职位", "姓名", "性别", "学号", "班级", "联系方式", "政治面貌", "状态"};
            for (int i = 0; i < cols.length; i++) h.createCell(i).setCellValue(cols[i]);
            List<MemberVO> list = page.getRecords();
            for (int i = 0; i < list.size(); i++) {
                MemberVO m = list.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(i + 1);
                row.createCell(1).setCellValue(m.deptName() == null ? "主任室" : m.deptName());
                row.createCell(2).setCellValue(m.positionLabel());
                row.createCell(3).setCellValue(m.name());
                row.createCell(4).setCellValue(m.gender() == null ? "" : m.gender());
                row.createCell(5).setCellValue(m.studentNo() == null ? "" : m.studentNo());
                row.createCell(6).setCellValue(m.className() == null ? "" : m.className());
                row.createCell(7).setCellValue(m.phone() == null ? "" : m.phone());
                row.createCell(8).setCellValue(m.politicalStatus() == null ? "" : m.politicalStatus());
                row.createCell(9).setCellValue(m.statusLabel());
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { wb.write(out); return out.toByteArray(); }
        }
    }

    // ===== 解析小工具（同 RosterImportService 模式）=====
    private Sheet firstDataSheet(Workbook wb) {
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet s = wb.getSheetAt(i);
            if (s.getLastRowNum() >= 0 && s.getRow(0) != null) return s;
        }
        throw new BizException(4001, "名单文件为空");
    }
    private Row findHeaderRow(Sheet sheet) {
        int last = Math.min(sheet.getLastRowNum(), 9);
        for (int i = 0; i <= last; i++) {
            Row r = sheet.getRow(i);
            if (r == null) continue;
            for (Cell c : r) {
                String norm = norm(cellStr(c));
                if (norm != null && (norm.contains("姓名") || norm.contains("学号"))) return r;
            }
        }
        throw new BizException(4001, "名单文件缺少表头（需含姓名/学号列）");
    }
    private Map<String, Integer> locateColumns(Row header) {
        Map<String, Integer> col = new HashMap<>();
        for (Cell c : header) {
            String norm = norm(cellStr(c));
            if (norm == null) continue;
            if (!col.containsKey("name") && norm.contains("姓名")) col.put("name", c.getColumnIndex());
            else if (!col.containsKey("studentNo") && norm.contains("学号")) col.put("studentNo", c.getColumnIndex());
            else if (!col.containsKey("dept") && norm.contains("部门")) col.put("dept", c.getColumnIndex());
            else if (!col.containsKey("position") && norm.contains("职位")) col.put("position", c.getColumnIndex());
            else if (!col.containsKey("gender") && norm.contains("性别")) col.put("gender", c.getColumnIndex());
            else if (!col.containsKey("className") && norm.contains("班级")) col.put("className", c.getColumnIndex());
            else if (!col.containsKey("phone") && norm.contains("联系")) col.put("phone", c.getColumnIndex());
            else if (!col.containsKey("political") && norm.contains("政治")) col.put("political", c.getColumnIndex());
        }
        if (!col.containsKey("name")) throw new BizException(4001, "名单文件缺少姓名列");
        return col;
    }
    private Cell optionalCell(Row r, Map<String, Integer> col, String field) {
        Integer idx = col.get(field);
        return idx == null ? null : r.getCell(idx);
    }
    private String cellStr(Cell c) {
        if (c == null) return null;
        switch (c.getCellType()) {
            case STRING: { String v = c.getStringCellValue(); return v == null ? null : v.trim(); }
            case NUMERIC: {
                if (DateUtil.isCellDateFormatted(c)) return new DataFormatter().formatCellValue(c);
                double d = c.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
                return Double.toString(d);
            }
            case BOOLEAN: return Boolean.toString(c.getBooleanCellValue());
            case FORMULA:
                try { String fv = c.getStringCellValue(); return fv == null ? null : fv.trim(); }
                catch (IllegalStateException e) { return Double.toString(c.getNumericCellValue()); }
            default: return null;
        }
    }
    private String norm(String s) { return s == null ? null : s.replaceAll("\\s+", ""); }
    private boolean isBlank(String s) { return s == null || s.isBlank(); }
    private String key(Long sessionId, String studentNo) {
        return sessionId + "|" + (studentNo == null ? "" : studentNo);
    }
}
```

- [ ] **Step 4: 加导入/模板/导出端点到 MemberController**

在 `MemberController` 增加（注意 `MemberController` 构造需注入 `MemberImportService`）：
```java
import com.pams.module.member.dto.MemberImportResultVO;
import com.pams.module.member.service.MemberImportService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

@PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public Result<MemberImportResultVO> importMembers(@RequestParam("sessionId") Long sessionId,
                                                  @RequestParam("file") MultipartFile file) {
    try (InputStream in = new ByteArrayInputStream(file.getBytes())) {
        return Result.ok(importService.importFromXlsx(in, sessionId));
    } catch (IOException e) { throw new BizException(4001, "名单文件解析失败"); }
}

@GetMapping("/import/template")
public ResponseEntity<Resource> template() throws IOException {
    byte[] data = importService.buildTemplate();
    return xlsxResponse(data, "成员导入模板.xlsx");
}

@GetMapping("/export")
public ResponseEntity<Resource> export(@RequestParam(required = false) Long sessionId,
                                       @RequestParam(required = false) Long deptId,
                                       @RequestParam(required = false) String position,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String keyword) throws IOException {
    byte[] data = importService.exportXlsx(sessionId, deptId, position, status, keyword);
    return xlsxResponse(data, "成员花名册.xlsx");
}

private ResponseEntity<Resource> xlsxResponse(byte[] data, String filename) {
    String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
    return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
            .body(new ByteArrayResource(data));
}
```
（需 import `org.springframework.http.HttpHeaders`。）

> 注：若 `MemberController` 构造参数 `MemberService service` 与 `MemberImportService importService` 相互依赖（importService 依赖 memberService），用 `@Lazy` 或让 `MemberImportService` 通过构造传 `MemberService` 时加 `@Lazy` 断开环。本方案 `MemberImportService` 第三个构造参数 `MemberService` 用 `@Lazy` 标注即可（或仅当需要导出时）。若编译报循环依赖，把 `MemberImportService` 里的 `memberService` 字段改为 `@Lazy` 注入。

- [ ] **Step 5: 跑测试确认通过**

Run: `cd pams-backend && mvn test -Dtest=MemberImportServiceTest,MemberServiceTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add pams-backend/src/main/java/com/pams/module/member/
git commit -m "feat(member): Excel导入(前向填充/去重/失败行)+模板下载+导出"
```

---

### Task 5: 一键导入账号（MemberAccountImportService）

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/member/dto/UnregisteredMemberVO.java`
- Create: `pams-backend/src/main/java/com/pams/module/member/dto/AccountImportRequest.java`
- Create: `pams-backend/src/main/java/com/pams/module/member/dto/AccountImportResultVO.java`
- Create: `pams-backend/src/main/java/com/pams/module/member/service/MemberAccountImportService.java`
- Modify: `pams-backend/src/main/java/com/pams/module/member/controller/MemberController.java`（加 unregistered/import-accounts 端点，`@PreAuthorize(ADMIN)`）
- Modify: `pams-backend/src/main/java/com/pams/repository/UserRepository.java`（加 `findByStudentNo`）
- Test: `pams-backend/src/test/java/com/pams/module/member/MemberAccountImportServiceTest.java`

**Interfaces:**
- Consumes: `MemberRepository`、`MemberSessionRepository`、`UserRepository`、`RoleRepository`、`PasswordEncoder`、`DepartmentRepository`、`MemberEnums`。
- Produces:
  - `UnregisteredMemberVO(Long id, String name, String studentNo, String deptName, String positionLabel)`
  - `AccountImportRequest(Long sessionId, List<Long> memberIds, Map<Long, String> roleCodes)`
  - `AccountImportResultVO(int created, int skipped)`
  - `MemberAccountImportService.unregistered(Long sessionId)` → `List<UnregisteredMemberVO>`
  - `MemberAccountImportService.importAccounts(AccountImportRequest req, Long currentUserId)` → `AccountImportResultVO`
  - 端点（`@PreAuthorize("hasAnyRole('TEACHER','DIRECTOR')")`）：`GET /api/members/unregistered?sessionId=`、`POST /api/members/import-accounts`。
- 角色默认映射（职位→role code）：DIRECTOR→DIRECTOR；SUB_DIRECTOR→DIRECTOR；DEPT_HEAD/SUB_DEPT_HEAD→按部门（组织部→ORG_LEADER，文秘部→SECRETARY_LEADER，新媒体中心→MEDIA_LEADER，青年科技部→TECH_LEADER，dept 为空→STAFF）；STAFF→STAFF。

- [ ] **Step 1: 写失败测试**

`MemberAccountImportServiceTest.java`（Mockito）：
```java
package com.pams.module.member;

import com.pams.entity.Department;
import com.pams.entity.Role;
import com.pams.entity.User;
import com.pams.module.member.dto.AccountImportRequest;
import com.pams.module.member.entity.Member;
import com.pams.module.member.repository.MemberRepository;
import com.pams.module.member.service.MemberAccountImportService;
import com.pams.repository.RoleRepository;
import com.pams.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemberAccountImportServiceTest {

    MemberRepository memberRepo;
    UserRepository userRepo;
    RoleRepository roleRepo;
    PasswordEncoder encoder;
    MemberAccountImportService service;

    @BeforeEach
    void setup() {
        memberRepo = mock(MemberRepository.class);
        userRepo = mock(UserRepository.class);
        roleRepo = mock(RoleRepository.class);
        encoder = mock(PasswordEncoder.class);
        service = new MemberAccountImportService(memberRepo, userRepo, roleRepo, encoder);
        when(encoder.encode(any())).thenReturn("hashed");
    }

    @Test
    void unregistered_excludesMembersWithoutStudentNoAndAlreadyRegistered() {
        Member hasNo = new Member(); hasNo.setId(1L); hasNo.setName("无学号"); hasNo.setStudentNo(null);
        Member ok = new Member(); ok.setId(2L); ok.setName("张三"); ok.setStudentNo("20250101"); ok.setPosition("STAFF");
        Member reg = new Member(); reg.setId(3L); reg.setName("李四"); reg.setStudentNo("20250102"); reg.setPosition("STAFF");
        when(memberRepo.findBySessionId(1L)).thenReturn(List.of(hasNo, ok, reg));
        when(userRepo.findByStudentNo("20250102")).thenReturn(List.of(new User()));

        var list = service.unregistered(1L);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getId()).isEqualTo(2L);
    }

    @Test
    void importAccounts_createsUsersAndSkipsExisting() {
        Member a = new Member(); a.setId(2L); a.setName("张三"); a.setStudentNo("20250101");
        a.setPosition("STAFF"); a.setDeptId(null);
        Member b = new Member(); b.setId(3L); b.setName("李四"); b.setStudentNo("20250102");
        b.setPosition("DIRECTOR"); b.setDeptId(null);
        when(memberRepo.findBySessionId(1L)).thenReturn(List.of(a, b));
        when(userRepo.existsByUsername("20250101")).thenReturn(false);
        when(userRepo.existsByUsername("20250102")).thenReturn(true);   // 已注册用户名 → skip
        Role staff = new Role(); staff.setCode("STAFF"); staff.setName("干事"); staff.setLevel(1);
        Role dir = new Role(); dir.setCode("DIRECTOR"); dir.setName("主任"); dir.setLevel(4);
        when(roleRepo.findByCode("STAFF")).thenReturn(Optional.of(staff));
        when(roleRepo.findByCode("DIRECTOR")).thenReturn(Optional.of(dir));

        var r = service.importAccounts(new AccountImportRequest(1L, List.of(2L, 3L), null), 99L);

        assertThat(r.created()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(1);
        verify(userRepo).save(any(User.class));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd pams-backend && mvn test -Dtest=MemberAccountImportServiceTest`
Expected: FAIL（`MemberAccountImportService` 不存在）。

- [ ] **Step 3: 加 UserRepository 方法**

`pams-backend/src/main/java/com/pams/repository/UserRepository.java` 加：
```java
List<User> findByStudentNo(String studentNo);
```

- [ ] **Step 4: 实现 Service**

`MemberAccountImportService.java`：
```java
package com.pams.module.member.service;

import com.pams.common.BizException;
import com.pams.entity.Department;
import com.pams.entity.Role;
import com.pams.entity.User;
import com.pams.module.member.dto.AccountImportRequest;
import com.pams.module.member.dto.AccountImportResultVO;
import com.pams.module.member.dto.MemberEnums;
import com.pams.module.member.dto.UnregisteredMemberVO;
import com.pams.module.member.entity.Member;
import com.pams.module.member.repository.MemberRepository;
import com.pams.repository.DepartmentRepository;
import com.pams.repository.RoleRepository;
import com.pams.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MemberAccountImportService {
    private final MemberRepository memberRepo;
    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final PasswordEncoder passwordEncoder;
    private final DepartmentRepository deptRepo;

    public MemberAccountImportService(MemberRepository memberRepo, UserRepository userRepo,
                                      RoleRepository roleRepo, PasswordEncoder passwordEncoder) {
        this(memberRepo, userRepo, roleRepo, passwordEncoder, null);
    }
    public MemberAccountImportService(MemberRepository memberRepo, UserRepository userRepo,
                                      RoleRepository roleRepo, PasswordEncoder passwordEncoder,
                                      DepartmentRepository deptRepo) {
        this.memberRepo = memberRepo; this.userRepo = userRepo; this.roleRepo = roleRepo;
        this.passwordEncoder = passwordEncoder; this.deptRepo = deptRepo;
    }

    private static final Map<String, String> DEPT_LEADER_ROLE = Map.of(
            "组织部", "ORG_LEADER", "文秘部", "SECRETARY_LEADER",
            "新媒体中心", "MEDIA_LEADER", "青年科技部", "TECH_LEADER");

    /** 该届未注册成员：有学号且学号在 sys_user 无匹配。 */
    public List<UnregisteredMemberVO> unregistered(Long sessionId) {
        return memberRepo.findBySessionId(sessionId).stream()
                .filter(m -> m.getStudentNo() != null && !m.getStudentNo().isBlank())
                .filter(m -> userRepo.findByStudentNo(m.getStudentNo()).isEmpty())
                .map(m -> new UnregisteredMemberVO(m.getId(), m.getName(), m.getStudentNo(),
                        deptName(m.getDeptId()),
                        MemberEnums.POSITION_LABELS.getOrDefault(m.getPosition(), m.getPosition())))
                .toList();
    }

    @Transactional
    public AccountImportResultVO importAccounts(AccountImportRequest req, Long currentUserId) {
        if (req.sessionId() == null || req.memberIds() == null || req.memberIds().isEmpty()) {
            throw new BizException(2811, "请选择要导入的成员");
        }
        int created = 0, skipped = 0;
        Map<Long, String> overrides = req.roleCodes() == null ? Map.of() : req.roleCodes();
        for (Long memberId : req.memberIds()) {
            Member m = memberRepo.findById(memberId)
                    .filter(x -> x.getSessionId().equals(req.sessionId()))
                    .orElse(null);
            if (m == null) { skipped++; continue; }
            if (m.getStudentNo() == null || m.getStudentNo().isBlank()) { skipped++; continue; }
            String username = m.getStudentNo().trim();
            if (userRepo.existsByUsername(username) || !userRepo.findByStudentNo(username).isEmpty()) { skipped++; continue; }

            String roleCode = overrides.getOrDefault(memberId, defaultRoleCode(m));
            Role role = roleRepo.findByCode(roleCode).orElse(null);
            if (role == null) { skipped++; continue; }

            User u = new User();
            u.setUsername(username);
            u.setPassword(passwordEncoder.encode("123456"));
            u.setRealName(m.getName());
            u.setStudentNo(username);
            u.setPhone(m.getPhone());
            u.setDept(m.getDeptId() == null ? null : deptRepo == null ? null
                    : deptRepo.findById(m.getDeptId()).orElse(null));
            u.setRole(role);
            u.setStatus(1);
            u.setCreatedAt(LocalDateTime.now());
            u.setUpdatedAt(LocalDateTime.now());
            u.setDeleted(0);
            userRepo.save(u);
            created++;
        }
        return new AccountImportResultVO(created, skipped);
    }

    /** 默认角色映射：主任/副主任→DIRECTOR；部长/副部长→本部门部长角色；干事→STAFF。 */
    private String defaultRoleCode(Member m) {
        switch (m.getPosition()) {
            case "DIRECTOR": case "SUB_DIRECTOR": return "DIRECTOR";
            case "DEPT_HEAD": case "SUB_DEPT_HEAD": {
                if (m.getDeptId() != null && deptRepo != null) {
                    String deptName = deptRepo.findById(m.getDeptId()).map(Department::getName).orElse(null);
                    String code = deptName == null ? null : DEPT_LEADER_ROLE.get(deptName);
                    if (code != null) return code;
                }
                return "STAFF";
            }
            default: return "STAFF";
        }
    }

    private String deptName(Long deptId) {
        if (deptId == null || deptRepo == null) return "主任室";
        return deptRepo.findById(deptId).map(Department::getName).orElse("主任室");
    }
}
```

DTO（record）：
```java
// UnregisteredMemberVO.java
public record UnregisteredMemberVO(Long id, String name, String studentNo, String deptName, String positionLabel) {}

// AccountImportRequest.java
public record AccountImportRequest(Long sessionId, List<Long> memberIds, Map<Long, String> roleCodes) {}

// AccountImportResultVO.java
public record AccountImportResultVO(int created, int skipped) {}
```

- [ ] **Step 5: 加端点到 MemberController**

（构造注入 `MemberAccountImportService`；两个端点用 `ADMIN` 权限）：
```java
private static final String ADMIN = "hasAnyRole('TEACHER','DIRECTOR')";

@GetMapping("/unregistered")
@PreAuthorize(ADMIN)
public Result<List<UnregisteredMemberVO>> unregistered(@RequestParam(required = false) Long sessionId) {
    return Result.ok(accountImportService.unregistered(sessionId));
}

@PostMapping("/import-accounts")
@PreAuthorize(ADMIN)
public Result<AccountImportResultVO> importAccounts(@RequestBody AccountImportRequest req) {
    return Result.ok(accountImportService.importAccounts(req, null));
}
```

- [ ] **Step 6: 跑测试确认通过**

Run: `cd pams-backend && mvn test -Dtest=MemberAccountImportServiceTest,MemberServiceTest,MemberImportServiceTest`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add pams-backend/src/main/java/com/pams/module/member/ \
        pams-backend/src/main/java/com/pams/repository/UserRepository.java
git commit -m "feat(member): 用户管理-从花名册一键导入注册账号（含默认角色映射）"
```

---

### Task 6: 后端集成测试（MockMvc 全链路，验证权限 + 迁移 + 导入）

**Files:**
- Create: `pams-backend/src/test/java/com/pams/module/member/MemberIntegrationTest.java`

**Interfaces:**
- Consumes: 全部已实现端点；种子账号 `zhuren`/123456（DIRECTOR）、`staff`/123456（STAFF）。
- Produces: 一条端到端验证。

- [ ] **Step 1: 写失败测试**

`MemberIntegrationTest.java`（`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`，风格同 RosterImportIntegrationTest；断言：干事 403、主任可建届别/成员/导入、未注册列表、换届归档）：
```java
package com.pams.module.member;

import com.pams.module.member.entity.Member;
import com.pams.module.member.entity.MemberSession;
import com.pams.module.member.repository.MemberRepository;
import com.pams.module.member.repository.MemberSessionRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MemberRepository memberRepo;
    @Autowired MemberSessionRepository sessionRepo;

    private String login(String username) throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}")).andReturn();
        String body = res.getResponse().getContentAsString();
        return body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private byte[] rosterXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("S");
            String[][] rows = {
                {"序号", "部门", "职位", "姓名", "性别", "学号", "班级", "联系方式", "政治面貌"},
                {"1", "文秘部", "干事", "集成测试甲", "男", "2990000001", "25测试班", "13000000000", "群众"},
            };
            for (int i = 0; i < rows.length; i++) {
                Row r = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) r.createCell(j).setCellValue(rows[i][j]);
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { wb.write(out); return out.toByteArray(); }
        }
    }

    @Test
    void staffGets403_andDirectorCanCreateSessionImportListArchive() throws Exception {
        String staffToken = login("staff");
        mvc.perform(get("/api/members").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());

        String token = login("zhuren");

        // 建届别
        MvcResult sres = mvc.perform(post("/api/member-sessions").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"第九届\"}"))
                .andExpect(status().isOk()).andReturn();
        String sessionId = sessionRepo.findAll().stream()
                .filter(s -> "第九届".equals(s.getName())).findFirst().orElseThrow().getId().toString();

        // 导入成员
        MockMultipartFile file = new MockMultipartFile("file", "members.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", rosterXlsx());
        mvc.perform(multipart("/api/members/import").file(file).param("sessionId", sessionId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.success").value(1));

        // 列表含该成员
        mvc.perform(get("/api/members").param("sessionId", sessionId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].name").value("集成测试甲"));

        // 换届归档 → 在职变往届
        mvc.perform(post("/api/members/" + sessionId + "/archive")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(1));
        Member m = memberRepo.findBySessionId(Long.valueOf(sessionId)).get(0);
        assertThat(m.getStatus()).isEqualTo("ALUMNI");
    }
}
```

- [ ] **Step 2: 跑测试确认通过**

Run: `cd pams-backend && mvn test -Dtest=MemberIntegrationTest`
Expected: PASS（验证 V13 迁移 + 权限 + 导入 + 归档全链路）。

- [ ] **Step 3: 提交**

```bash
git add pams-backend/src/test/java/com/pams/module/member/MemberIntegrationTest.java
git commit -m "test(member): 全链路集成测试（干事403/导入/列表/换届归档）"
```

---

### Task 7: 前端 API 层（member.ts + 用户管理相关扩展）

**Files:**
- Create: `pams-web/src/api/member.ts`
- Test: 无（随页面 Task 8/11 验证）；验证用 `cd pams-web && npx tsc --noEmit`。

**Interfaces:**
- Produces（`src/api/member.ts`）：
  - 类型：`MemberVO`、`MemberSessionVO`、`MemberSave`、`MemberImportResult`、`MemberStats`、`MemberDetail`、`MemberCredit`、`UnregisteredMember`、`AccountImportResult`（字段与后端 record 一致）。
  - 枚举映射导出：`POSITION_LABELS: Record<string,string>`、`STATUS_LABELS`、`STATUS_COLOR: Record<string,string>`（状态→Tag 颜色：ACTIVE 绿 / ALUMNI 蓝 / RESIGNED 橙 / EXPELLED 红 / LEFT 灰）、`STATUS_OPTIONS`/`POSITION_OPTIONS`（Select 选项）。
  - 函数（薄封装，见 code）。

- [ ] **Step 1: 写 member.ts**

```ts
import { get, post, put, del, http } from './http'
import type { PageResult } from './types'
import type { AxiosResponse } from 'axios'

export interface MemberVO {
  id: number
  sessionId: number
  sessionName: string | null
  deptId: number | null
  deptName: string | null
  position: string
  positionLabel: string
  name: string
  gender: string | null
  studentNo: string | null
  className: string | null
  phone: string | null
  politicalStatus: string | null
  status: string
  statusLabel: string
  remark: string | null
  createdAt: string
  updatedAt: string
}

export interface MemberSessionVO {
  id: number
  name: string
  isCurrent: number
  sortOrder: number
  remark: string | null
}

export interface MemberSave {
  sessionId: number
  deptId?: number | null
  position: string
  name: string
  gender?: string | null
  studentNo?: string | null
  className?: string | null
  phone?: string | null
  politicalStatus?: string | null
  status?: string
  remark?: string | null
}

export interface MemberImportFailure { row: number; name: string; reason: string }
export interface MemberImportResult { total: number; success: number; skipped: number; failed: MemberImportFailure[] }
export interface NameCount { name: string; count: number }
export interface MemberStats { total: number; byDept: NameCount[]; byPosition: NameCount[]; byStatus: NameCount[] }
export interface MemberCredit { id: number; project: string; credit: number; basis: string | null; remark: string | null; createdAt: string }
export interface MemberDetail {
  member: MemberVO
  scheduleCount: number
  attendanceCount: number
  totalCredit: number
  credits: MemberCredit[]
}
export interface UnregisteredMember { id: number; name: string; studentNo: string; deptName: string; positionLabel: string }
export interface AccountImportResult { created: number; skipped: number }

/** 职位/状态枚举映射（与后端 MemberEnums 一致） */
export const POSITION_LABELS: Record<string, string> = {
  DIRECTOR: '主任', SUB_DIRECTOR: '副主任', DEPT_HEAD: '部长', SUB_DEPT_HEAD: '副部长', STAFF: '干事',
}
export const STATUS_LABELS: Record<string, string> = {
  ACTIVE: '在职', ALUMNI: '往届', RESIGNED: '退部', EXPELLED: '开除', LEFT: '离职',
}
export const STATUS_COLOR: Record<string, string> = {
  ACTIVE: 'success', ALUMNI: 'blue', RESIGNED: 'orange', EXPELLED: 'red', LEFT: 'default',
}
export const POSITION_OPTIONS = Object.entries(POSITION_LABELS).map(([value, label]) => ({ value, label }))
export const STATUS_OPTIONS = Object.entries(STATUS_LABELS).map(([value, label]) => ({ value, label }))
export const POLITICAL_OPTIONS = ['共青团员', '中共预备党员', '中共党员', '群众', '无党派人士'].map((v) => ({ value: v, label: v }))

export const listMembers = (params: {
  sessionId?: number; deptId?: number; position?: string; status?: string; keyword?: string; page?: number; size?: number
}) => get<PageResult<MemberVO>>('/members', params)
export const getMember = (id: number) => get<MemberDetail>(`/members/${id}`)
export const createMember = (data: MemberSave) => post<number>('/members', data)
export const updateMember = (id: number, data: MemberSave) => put<void>(`/members/${id}`, data)
export const deleteMember = (id: number) => del<void>(`/members/${id}`)
export const batchDeleteMembers = (ids: number[]) => post<void>('/members/batch-delete', { ids })
export const importMembers = (formData: FormData) => post<MemberImportResult>('/members/import', formData)
export const getMemberStats = (sessionId?: number) => get<MemberStats>('/members/stats', { sessionId })
export const archiveSession = (sessionId: number) => post<{ count: number }>(`/members/${sessionId}/archive`)
export const listUnregisteredMembers = (sessionId?: number) =>
  get<UnregisteredMember[]>('/members/unregistered', { sessionId })
export const importAccounts = (data: { sessionId?: number; memberIds: number[]; roleCodes?: Record<number, string> }) =>
  post<AccountImportResult>('/members/import-accounts', data)

/** blob 下载（模板 / 导出） */
export const downloadMemberXlsx = (url: string, params?: Record<string, unknown>) =>
  http.get(url, { params, responseType: 'blob' }) as unknown as Promise<AxiosResponse<Blob>>
export const downloadImportTemplate = () => downloadMemberXlsx('/members/import/template')
export const downloadMemberExport = (params?: {
  sessionId?: number; deptId?: number; position?: string; status?: string; keyword?: string
}) => downloadMemberXlsx('/members/export', params)

export const listMemberSessions = () => get<MemberSessionVO[]>('/member-sessions')
export const createMemberSession = (data: { name: string; isCurrent?: number; sortOrder?: number; remark?: string }) =>
  post<number>('/member-sessions', data)
export const updateMemberSession = (id: number, data: { name: string; isCurrent?: number; sortOrder?: number; remark?: string }) =>
  put<void>(`/member-sessions/${id}`, data)
export const deleteMemberSession = (id: number) => del<void>(`/member-sessions/${id}`)
export const setCurrentMemberSession = (id: number) => post<void>(`/member-sessions/${id}/set-current`)
```

- [ ] **Step 2: 类型检查**

Run: `cd pams-web && npx tsc --noEmit`
Expected: PASS（无报错；member.ts 尚未被引用，应至少不引入类型错误）。

- [ ] **Step 3: 提交**

```bash
git add pams-web/src/api/member.ts
git commit -m "feat(member): 前端 API 层（member.ts 类型+枚举映射+薄函数）"
```

---

### Task 8: 前端成员列表页（MemberList.tsx）

**Files:**
- Create: `pams-web/src/pages/member/MemberList.tsx`
- Verify: `cd pams-web && npx tsc --noEmit`

**Interfaces:**
- Consumes: `api/member.ts` 全部函数；`listDepts`（`api/dept.ts`）；`GlassCard/GlassTable/GlassModal/PageHeader`；`useAuthStore`。
- Produces: 完整页面组件（届别 Segmented + 统计卡片 + 筛选 + 表格 + 新增/编辑弹窗 + 导入报告弹窗 + 导出/换届归档/批量删除 + 届别管理弹窗）。路由注册在 Task 10。

- [ ] **Step 1: 写组件**

`MemberList.tsx`（关键结构；细节样式沿用现有页面）：
```tsx
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  App, Button, Form, Input, Modal, Popconfirm, Segmented, Select, Space, Table, Tag, Upload,
} from 'antd'
import type { TableColumnsType, UploadFile } from 'antd'
import {
  DeleteOutlined, DownloadOutlined, EditOutlined, ExportOutlined, PlusOutlined, ReloadOutlined, UploadOutlined,
} from '@ant-design/icons'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import { listDepts, type DeptVO } from '@/api/dept'
import {
  archiveSession, batchDeleteMembers, createMember, createMemberSession, deleteMember, deleteMemberSession,
  downloadImportTemplate, downloadMemberExport, getMemberStats, importMembers, listMemberSessions,
  listMembers, setCurrentMemberSession, updateMember, updateMemberSession,
  type MemberImportResult, type MemberSave, type MemberSessionVO, type MemberVO,
  POSITION_OPTIONS, STATUS_OPTIONS, STATUS_COLOR, STATUS_LABELS, POLITICAL_OPTIONS,
} from '@/api/member'

type MemberRecord = MemberVO & { key: number }

interface MemberFormValues extends MemberSave {}

export default function MemberList() {
  const { message, modal } = App.useApp()
  const [sessions, setSessions] = useState<MemberSessionVO[]>([])
  const [sessionId, setSessionId] = useState<number | undefined>()
  const [data, setData] = useState<MemberRecord[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  const [keyword, setKeyword] = useState('')
  const [deptFilter, setDeptFilter] = useState<number | undefined>()
  const [positionFilter, setPositionFilter] = useState<string | undefined>()
  const [statusFilter, setStatusFilter] = useState<string | undefined>()
  const [stats, setStats] = useState<{ total: number; byDept: { name: string; count: number }[] } | null>(null)

  const [depts, setDepts] = useState<DeptVO[]>([])
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<MemberVO | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<MemberFormValues>()

  const [sessionModalOpen, setSessionModalOpen] = useState(false)
  const [editingSession, setEditingSession] = useState<MemberSessionVO | null>(null)
  const [sessionForm] = Form.useForm<{ name: string; remark?: string }>()

  const [importing, setImporting] = useState(false)
  const [importFileList, setImportFileList] = useState<UploadFile[]>([])
  const [importResult, setImportResult] = useState<MemberImportResult | null>(null)
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([])

  const currentSession = useMemo(
    () => sessions.find((s) => s.id === sessionId) ?? sessions.find((s) => s.isCurrent === 1),
    [sessions, sessionId],
  )
  const activeSessionId = sessionId ?? currentSession?.id

  const fetchSessions = useCallback(async () => {
    const list = await listMemberSessions()
    setSessions(list)
    if (sessionId == null) {
      const cur = list.find((s) => s.isCurrent === 1) ?? list[0]
      setSessionId(cur?.id)
    }
  }, [sessionId])

  const fetchStats = useCallback(async () => {
    if (activeSessionId == null) return
    const s = await getMemberStats(activeSessionId)
    setStats({ total: s.total, byDept: s.byDept })
  }, [activeSessionId])

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const res = await listMembers({
        sessionId: activeSessionId, deptId: deptFilter, position: positionFilter,
        status: statusFilter, keyword: keyword || undefined, page, size,
      })
      setData((res.records ?? []).map((r) => ({ ...r, key: r.id })))
      setTotal(res.total)
    } catch { /* http 拦截已提示 */ } finally { setLoading(false) }
  }, [activeSessionId, deptFilter, positionFilter, statusFilter, keyword, page, size])

  useEffect(() => { fetchSessions() }, [fetchSessions])
  useEffect(() => { fetchList() }, [fetchList])
  useEffect(() => { fetchStats() }, [fetchStats])
  useEffect(() => { setDepts; listDepts().then(setDepts).catch(() => {}) }, [])

  const openCreate = () => { setEditing(null); setModalOpen(true) }
  const openEdit = (r: MemberVO) => { setEditing(r); setModalOpen(true) }
  const formInitialValues = useMemo(() => {
    if (!editing) return { sessionId: activeSessionId, status: 'ACTIVE' }
    return {
      sessionId: editing.sessionId, deptId: editing.deptId, position: editing.position, name: editing.name,
      gender: editing.gender, studentNo: editing.studentNo, className: editing.className,
      phone: editing.phone, politicalStatus: editing.politicalStatus, status: editing.status, remark: editing.remark,
    }
  }, [editing, activeSessionId])

  const handleSave = async () => {
    const v = await form.validateFields()
    setSaving(true)
    try {
      const payload: MemberSave = {
        sessionId: v.sessionId ?? activeSessionId!, position: v.position!, name: v.name.trim(),
        deptId: v.deptId ?? null, gender: v.gender ?? null, studentNo: v.studentNo?.trim() || null,
        className: v.className?.trim() || null, phone: v.phone?.trim() || null,
        politicalStatus: v.politicalStatus ?? null, status: v.status ?? 'ACTIVE', remark: v.remark ?? null,
      }
      if (editing) { await updateMember(editing.id, payload); message.success('成员已更新') }
      else { await createMember(payload); message.success('成员已新增') }
      setModalOpen(false); fetchList(); fetchStats()
    } catch { /* 已提示 */ } finally { setSaving(false) }
  }

  const handleDelete = async (id: number) => {
    await deleteMember(id); message.success('已删除'); fetchList(); fetchStats()
  }

  const handleBatchDelete = async () => {
    if (!selectedRowKeys.length) return
    modal.confirm({
      title: `确认删除选中的 ${selectedRowKeys.length} 名成员？`,
      onOk: async () => {
        await batchDeleteMembers(selectedRowKeys.map(Number))
        message.success('已删除'); setSelectedRowKeys([]); fetchList(); fetchStats()
      },
    })
  }

  const handleImport = async () => {
    if (!importFileList.length || activeSessionId == null) return
    setImporting(true)
    try {
      const fd = new FormData()
      fd.append('sessionId', String(activeSessionId))
      fd.append('file', importFileList[0].originFileObj as Blob, importFileList[0].name)
      const r = await importMembers(fd)
      setImportResult(r)
      setImportFileList([])
      fetchList(); fetchStats()
    } catch { /* 已提示 */ } finally { setImporting(false) }
  }

  const handleArchive = () => {
    if (activeSessionId == null) return
    modal.confirm({
      title: `确认换届归档「${currentSession?.name ?? ''}」？`,
      content: '该届全部「在职」成员将被批量置为「往届」。',
      okText: '归档',
      onOk: async () => {
        const r = await archiveSession(activeSessionId)
        message.success(`已归档 ${r.count} 人`)
        fetchList(); fetchStats()
      },
    })
  }

  const handleExport = async () => {
    const res = await downloadMemberExport({ sessionId: activeSessionId, deptId: deptFilter, position: positionFilter, status: statusFilter, keyword: keyword || undefined })
    const url = URL.createObjectURL(res.data)
    const a = document.createElement('a')
    a.href = url; a.download = `成员花名册_${currentSession?.name ?? ''}.xlsx`; a.click()
    URL.revokeObjectURL(url)
  }

  const handleSessionSave = async () => {
    const v = await sessionForm.validateFields()
    if (editingSession) { await updateMemberSession(editingSession.id, { ...v, sortOrder: editingSession.sortOrder }); message.success('届别已更新') }
    else { await createMemberSession({ ...v, isCurrent: sessions.length === 0 ? 1 : 0 }); message.success('届别已新增') }
    setSessionModalOpen(false); fetchSessions()
  }

  const columns: TableColumnsType<MemberRecord> = [
    { title: '姓名', dataIndex: 'name', key: 'name', width: 110 },
    { title: '部门', dataIndex: 'deptName', key: 'deptName', width: 120, render: (v: string | null) => v ?? '主任室' },
    { title: '职位', dataIndex: 'positionLabel', key: 'positionLabel', width: 100 },
    { title: '性别', dataIndex: 'gender', key: 'gender', width: 60, render: (v: string | null) => v || '-' },
    { title: '班级', dataIndex: 'className', key: 'className', width: 160, render: (v: string | null) => v || '-' },
    { title: '学号', dataIndex: 'studentNo', key: 'studentNo', width: 140, render: (v: string | null) => v || '-' },
    { title: '联系方式', dataIndex: 'phone', key: 'phone', width: 130, render: (v: string | null) => v || '-' },
    { title: '政治面貌', dataIndex: 'politicalStatus', key: 'politicalStatus', width: 100, render: (v: string | null) => v || '-' },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 90,
      render: (s: string) => <Tag color={STATUS_COLOR[s]}>{STATUS_LABELS[s]}</Tag>,
    },
    {
      title: '操作', key: 'action', width: 150,
      render: (_: unknown, r: MemberRecord) => (
        <Space size="small">
          <Button type="link" size="small" onClick={() => { window.location.href = `/members/${r.id}` }}>详情</Button>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>编辑</Button>
          <Popconfirm title="确认删除该成员？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="成员管理"
        description="党建办公室干部干事花名册（仅干部可见），支持 Excel 导入 / 手动添加 / 换届归档"
        extra={
          <Space wrap>
            <Button icon={<PlusOutlined />} onClick={openCreate}>新增成员</Button>
            <Button icon={<UploadOutlined />} onClick={() => document.getElementById('member-import')?.click()}>
              导入 Excel
            </Button>
            <Button icon={<DownloadOutlined />} onClick={() => downloadImportTemplate().then((res) => {
              const url = URL.createObjectURL(res.data); const a = document.createElement('a')
              a.href = url; a.download = '成员导入模板.xlsx'; a.click(); URL.revokeObjectURL(url)
            })}>下载模板</Button>
            <Button icon={<ExportOutlined />} onClick={handleExport}>导出</Button>
            <Button icon={<ReloadOutlined />} onClick={handleArchive}>换届归档</Button>
          </Space>
        }
      />

      <GlassCard style={{ padding: 12, marginBottom: 12 }}>
        <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}>
          <Segmented
            options={sessions.map((s) => ({ label: s.isCurrent === 1 ? `${s.name}（当前）` : s.name, value: s.id }))}
            value={activeSessionId}
            onChange={(v) => { setSessionId(v as number); setPage(1) }}
          />
          <Button size="small" type="link" onClick={() => { setEditingSession(null); sessionForm.resetFields(); setSessionModalOpen(true) }}>
            届别管理
          </Button>
        </Space>
        {stats && (
          <Space wrap style={{ marginTop: 8 }}>
            <Tag color="blue">总人数 {stats.total}</Tag>
            {stats.byDept.map((d) => <Tag key={d.name}>{d.name} {d.count}</Tag>)}
          </Space>
        )}
      </GlassCard>

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <Input.Search placeholder="姓名 / 学号 / 手机号" allowClear style={{ width: 220 }}
            onSearch={(v) => { setPage(1); setKeyword(v) }} />
          <Select placeholder="部门" allowClear options={depts.map((d) => ({ value: d.id, label: d.name }))}
            style={{ width: 140 }} value={deptFilter} onChange={(v) => { setPage(1); setDeptFilter(v) }} />
          <Select placeholder="职位" allowClear options={POSITION_OPTIONS} style={{ width: 120 }}
            value={positionFilter} onChange={(v) => { setPage(1); setPositionFilter(v) }} />
          <Select placeholder="状态" allowClear options={STATUS_OPTIONS} style={{ width: 120 }}
            value={statusFilter} onChange={(v) => { setPage(1); setStatusFilter(v) }} />
          {selectedRowKeys.length > 0 && (
            <Button danger onClick={handleBatchDelete}>批量删除（{selectedRowKeys.length}）</Button>
          )}
        </Space>
      </GlassCard>

      <GlassTable<MemberRecord>
        columns={columns} dataSource={data} rowKey="id" loading={loading}
        rowSelection={{ selectedRowKeys, onChange: setSelectedRowKeys }}
        pagination={{ current: page, pageSize: size, total, showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`, onChange: (p, s) => { setPage(p); setSize(s) } }}
      />

      <input id="member-import" type="file" accept=".xlsx,.xls" hidden
        onChange={(e) => {
          const f = e.target.files?.[0]
          if (f) { setImportFileList([{ uid: '-1', name: f.name, originFileObj: f } as UploadFile]) ; handleImport() }
          e.target.value = ''
        }} />

      {/* 新增 / 编辑成员 */}
      <GlassModal
        title={editing ? '编辑成员' : '新增成员'} open={modalOpen} onCancel={() => setModalOpen(false)}
        footer={<Space><Button onClick={() => setModalOpen(false)}>取消</Button>
          <Button type="primary" loading={saving} onClick={handleSave}>保存</Button></Space>}>
        <Form form={form} layout="vertical" preserve={false} initialValues={formInitialValues}>
          <Form.Item name="sessionId" label="届别" rules={[{ required: true, message: '请选择届别' }]}>
            <Select options={sessions.map((s) => ({ value: s.id, label: s.name }))} disabled={!!editing} />
          </Form.Item>
          <Form.Item name="name" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input maxLength={50} />
          </Form.Item>
          <Space size="middle" wrap>
            <Form.Item name="position" label="职位" rules={[{ required: true, message: '请选择职位' }]}>
              <Select options={POSITION_OPTIONS} style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="deptId" label="部门">
              <Select allowClear placeholder="主任/副主任可留空（主任室）"
                options={depts.map((d) => ({ value: d.id, label: d.name }))} style={{ width: 180 }} />
            </Form.Item>
            <Form.Item name="gender" label="性别">
              <Select allowClear options={[{ value: '男', label: '男' }, { value: '女', label: '女' }]} style={{ width: 90 }} />
            </Form.Item>
          </Space>
          <Space size="middle" wrap>
            <Form.Item name="studentNo" label="学号">
              <Input maxLength={30} style={{ width: 200 }} />
            </Form.Item>
            <Form.Item name="className" label="班级">
              <Input maxLength={100} style={{ width: 200 }} />
            </Form.Item>
            <Form.Item name="phone" label="联系方式">
              <Input maxLength={20} style={{ width: 160 }} />
            </Form.Item>
          </Space>
          <Space size="middle" wrap>
            <Form.Item name="politicalStatus" label="政治面貌">
              <Select allowClear options={POLITICAL_OPTIONS} style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="status" label="状态">
              <Select options={STATUS_OPTIONS} style={{ width: 140 }} />
            </Form.Item>
          </Space>
          <Form.Item name="remark" label="备注">
            <Input.TextArea maxLength={255} rows={2} />
          </Form.Item>
        </Form>
      </GlassModal>

      {/* 届别管理 */}
      <GlassModal
        title="届别管理" open={sessionModalOpen} onCancel={() => setSessionModalOpen(false)}
        footer={<Space>
          <Button onClick={() => setSessionModalOpen(false)}>关闭</Button>
          <Button type="primary" onClick={handleSessionSave}>保存</Button>
        </Space>}>
        <Form form={sessionForm} layout="vertical" preserve={false}>
          <Form.Item name="name" label="届别名称" rules={[{ required: true, message: '请输入届名' }]}>
            <Input maxLength={50} placeholder="如：第十届" />
          </Form.Item>
          <Form.Item name="remark" label="备注"><Input maxLength={255} /></Form.Item>
        </Form>
        <Table
          size="small" rowKey="id" pagination={false}
          dataSource={sessions}
          columns={[
            { title: '届别', dataIndex: 'name' },
            { title: '当前', dataIndex: 'isCurrent', width: 70, render: (v: number) => (v === 1 ? <Tag color="green">当前</Tag> : '-') },
            { title: '操作', key: 'op', width: 180,
              render: (_: unknown, s: MemberSessionVO) => (
                <Space size="small">
                  {s.isCurrent !== 1 && (
                    <Button type="link" size="small" onClick={async () => { await setCurrentMemberSession(s.id); message.success('已设为当前届'); fetchSessions() }}>
                      设为当前
                    </Button>
                  )}
                  <Button type="link" size="small" onClick={() => { setEditingSession(s); sessionForm.setFieldsValue({ name: s.name, remark: s.remark }); setSessionModalOpen(true) }}>编辑</Button>
                  <Popconfirm title="删除该届别？" onConfirm={async () => { await deleteMemberSession(s.id); message.success('已删除'); fetchSessions() }} okText="删除" cancelText="取消">
                    <Button type="link" size="small" danger>删除</Button>
                  </Popconfirm>
                </Space>
              ) },
          ]}
        />
      </GlassModal>

      {/* 导入结果报告 */}
      <Modal
        title="导入结果" open={!!importResult} onCancel={() => setImportResult(null)} footer={null}
        width={560}>
        {importResult && (
          <div>
            <Space wrap style={{ marginBottom: 12 }}>
              <Tag color="blue">共 {importResult.total} 行</Tag>
              <Tag color="success">成功 {importResult.success}</Tag>
              {importResult.failed.length > 0 && <Tag color="error">失败 {importResult.failed.length}</Tag>}
            </Space>
            {importResult.failed.length > 0 ? (
              <Table
                size="small" rowKey="row" pagination={false} dataSource={importResult.failed}
                columns={[
                  { title: '行号', dataIndex: 'row', width: 80 },
                  { title: '姓名', dataIndex: 'name', width: 120 },
                  { title: '原因', dataIndex: 'reason' },
                ]} />
            ) : <div style={{ color: '#999' }}>全部导入成功。</div>}
          </div>
        )}
      </Modal>
    </div>
  )
}
```

> 实现要点：`handleImport` 用受控 `<input type="file" hidden>`（避免 AntD Upload 自动上传的复杂度），选文件后立即导入并弹结果。编辑弹窗回填用 `formInitialValues`（GlassModal destroyOnHidden 卸载字段，同 UserList 模式）。换届归档、批量删除、届别删除均二次确认。

- [ ] **Step 2: 类型检查**

Run: `cd pams-web && npx tsc --noEmit`
Expected: PASS。

- [ ] **Step 3: 提交**

```bash
git add pams-web/src/pages/member/MemberList.tsx
git commit -m "feat(member): 成员列表页（届别切换/统计卡片/筛选/导入导出/换届归档/CRUD）"
```

---

### Task 9: 前端成员详情页（MemberDetail.tsx）

**Files:**
- Create: `pams-web/src/pages/member/MemberDetail.tsx`
- Verify: `cd pams-web && npx tsc --noEmit`

**Interfaces:**
- Consumes: `getMember`（`api/member.ts`）、`STATUS_COLOR/STATUS_LABELS`、`POSITION_LABELS`、`GlassCard/PageHeader`；路由参数 `:id`。
- Produces: 详情页（基础信息卡 + 统计概览 + 素拓记录表 + 快捷改状态）。

- [ ] **Step 1: 写组件**

`MemberDetail.tsx`：
```tsx
import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { App, Button, Card, Descriptions, Select, Space, Statistic, Table, Tag } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import GlassCard from '@/components/glass/GlassCard'
import PageHeader from '@/components/glass/PageHeader'
import {
  getMember, updateMember, STATUS_COLOR, STATUS_LABELS, STATUS_OPTIONS, POSITION_LABELS,
  type MemberDetail, type MemberVO,
} from '@/api/member'

export default function MemberDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { message } = App.useApp()
  const [detail, setDetail] = useState<MemberDetail | null>(null)
  const [status, setStatus] = useState<string>('ACTIVE')

  const fetchDetail = useCallback(async () => {
    const d = await getMember(Number(id))
    setDetail(d)
    setStatus(d.member.status)
  }, [id])

  useEffect(() => { fetchDetail() }, [fetchDetail])

  const changeStatus = async (next: string) => {
    if (!detail) return
    await updateMember(detail.member.id, {
      sessionId: detail.member.sessionId, deptId: detail.member.deptId, position: detail.member.position,
      name: detail.member.name, gender: detail.member.gender, studentNo: detail.member.studentNo,
      className: detail.member.className, phone: detail.member.phone,
      politicalStatus: detail.member.politicalStatus, status: next, remark: detail.member.remark,
    })
    setStatus(next)
    message.success('状态已更新')
    fetchDetail()
  }

  if (!detail) return <div>加载中...</div>
  const m: MemberVO = detail.member

  return (
    <div>
      <PageHeader
        title={`${m.name} · 成员详情`}
        description={`${detail.member.sessionName ?? ''} / ${m.deptName ?? '主任室'} / ${POSITION_LABELS[m.position] ?? m.position}`}
        extra={<Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/members')}>返回列表</Button>}
      />

      <GlassCard style={{ padding: 20, marginBottom: 16 }}>
        <Space size="large" wrap>
          <Tag color={STATUS_COLOR[m.status]} style={{ fontSize: 14, padding: '4px 12px' }}>{STATUS_LABELS[m.status]}</Tag>
          <Select value={status} options={STATUS_OPTIONS} style={{ width: 120 }}
            onChange={(v) => changeStatus(v)} placeholder="快捷改状态" />
        </Space>
        <Descriptions column={3} style={{ marginTop: 16 }}>
          <Descriptions.Item label="姓名">{m.name}</Descriptions.Item>
          <Descriptions.Item label="性别">{m.gender || '-'}</Descriptions.Item>
          <Descriptions.Item label="学号">{m.studentNo || '-'}</Descriptions.Item>
          <Descriptions.Item label="班级">{m.className || '-'}</Descriptions.Item>
          <Descriptions.Item label="联系方式">{m.phone || '-'}</Descriptions.Item>
          <Descriptions.Item label="政治面貌">{m.politicalStatus || '-'}</Descriptions.Item>
          <Descriptions.Item label="部门">{m.deptName ?? '主任室'}</Descriptions.Item>
          <Descriptions.Item label="职位">{POSITION_LABELS[m.position] ?? m.position}</Descriptions.Item>
          <Descriptions.Item label="届别">{m.sessionName || '-'}</Descriptions.Item>
          <Descriptions.Item label="备注" span={3}>{m.remark || '-'}</Descriptions.Item>
        </Descriptions>
      </GlassCard>

      <div style={{ display: 'flex', gap: 16, marginBottom: 16 }}>
        <Card style={{ flex: 1 }}><Statistic title="排班次数" value={detail.scheduleCount} /></Card>
        <Card style={{ flex: 1 }}><Statistic title="考勤记录" value={detail.attendanceCount} /></Card>
        <Card style={{ flex: 1 }}><Statistic title="素拓累计分" value={detail.totalCredit} precision={2} /></Card>
      </div>

      <GlassCard style={{ padding: 16 }}>
        <PageHeader title="素拓记录" description="按学号精确聚合 credit_record" />
        <Table
          size="small" rowKey="id" pagination={{ pageSize: 10 }} dataSource={detail.credits}
          columns={[
            { title: '项目', dataIndex: 'project' },
            { title: '分值', dataIndex: 'credit', width: 100, render: (v: number) => <Tag color="gold">{v}</Tag> },
            { title: '依据', dataIndex: 'basis', width: 140, render: (v: string | null) => v || '-' },
            { title: '备注', dataIndex: 'remark', render: (v: string | null) => v || '-' },
            { title: '时间', dataIndex: 'createdAt', width: 180, render: (v: string) => v?.slice(0, 16).replace('T', ' ') || '-' },
          ]} />
      </GlassCard>
    </div>
  )
}
```

- [ ] **Step 2: 类型检查**

Run: `cd pams-web && npx tsc --noEmit`
Expected: PASS。

- [ ] **Step 3: 提交**

```bash
git add pams-web/src/pages/member/MemberDetail.tsx
git commit -m "feat(member): 成员详情页（基础信息/统计概览/素拓记录/快捷改状态）"
```

---

### Task 10: 前端路由 + 菜单

**Files:**
- Modify: `pams-web/src/router/index.tsx`
- Modify: `pams-web/src/layouts/MainLayout.tsx`
- Verify: `cd pams-web && npx tsc --noEmit`

**Interfaces:**
- Consumes: `MemberList`、`MemberDetail` 组件；`RequireRole` + `LEADER_ROLES`。
- Produces: 路由 `/members`、`/members/:id`（LEADER_ROLES 包裹）；侧边栏菜单「成员管理」（`roleLevel >= 3`）。

- [ ] **Step 1: 注册路由**

`router/index.tsx` 增加：
```tsx
const MemberList = lazy(() => import('@/pages/member/MemberList'))
const MemberDetail = lazy(() => import('@/pages/member/MemberDetail'))
```
在 children 里（放在排班考勤块之后、档案资产块之前）加：
```tsx
      // 成员管理（敏感名单，仅干部可见）
      {
        path: '/members',
        element: <RequireRole roles={LEADER_ROLES}><Outlet /></RequireRole>,
        children: [
          { path: '', element: <MemberList /> },
          { path: ':id', element: <MemberDetail /> },
        ],
      },
```
（`LEADER_ROLES`/`Outlet`/`RequireRole` 已在文件顶部 import。）

- [ ] **Step 2: 加菜单项**

`MainLayout.tsx` 的 `menuItems` 中，在「排班考勤」子菜单之后、「材料库」之前加（`isMinisterOrAbove` 分支内）：
```tsx
        // 成员管理（仅干部可见）
        items.push({ key: '/members', label: '成员管理', icon: <IdcardOutlined /> })
```
确认 `IdcardOutlined` 已从 `@ant-design/icons` import（若未引入则补上；若已用于党务台账则复用）。同时确认 `selectedKey` 前缀映射把 `/members` 识别为自身（检查 `selectedKey` 逻辑：若按 pathname 精确匹配 menu key 则天然命中，若按前缀需补 `/members` 前缀规则）。

- [ ] **Step 3: 类型检查 + 提交**

Run: `cd pams-web && npx tsc --noEmit`
Expected: PASS。

```bash
git add pams-web/src/router/index.tsx pams-web/src/layouts/MainLayout.tsx
git commit -m "feat(member): 路由 /members 与侧边栏「成员管理」菜单（仅干部可见）"
```

---

### Task 11: 用户管理「从花名册一键导入账号」

**Files:**
- Modify: `pams-web/src/pages/admin/UserList.tsx`
- Verify: `cd pams-web && npx tsc --noEmit`

**Interfaces:**
- Consumes: `listMemberSessions`、`listUnregisteredMembers`、`importAccounts`（`api/member.ts`）；`listRoles`（`api/user.ts`）。
- Produces: UserList 工具栏「从花名册导入账号」按钮 + 弹窗（选届别 → 未注册成员勾选列表 → 确认导入，返回创建/跳过统计）。

- [ ] **Step 1: 在 UserList 加按钮与弹窗**

`UserList.tsx` 修改点：
1. import 顶部加：
```tsx
import { importAccounts, listMemberSessions, listUnregisteredMembers, type UnregisteredMember } from '@/api/member'
```
2. 新增 state：
```tsx
const [accountModalOpen, setAccountModalOpen] = useState(false)
const [accSessions, setAccSessions] = useState<{ id: number; name: string; isCurrent: number }[]>([])
const [accSessionId, setAccSessionId] = useState<number>()
const [unregistered, setUnregistered] = useState<UnregisteredMember[]>([])
const [accSelected, setAccSelected] = useState<number[]>([])
const [importingAccounts, setImportingAccounts] = useState(false)
```
3. `PageHeader` 的 `extra`（`isMinisterOrAbove` 分支内，「新增用户」按钮旁）加：
```tsx
<Button onClick={() => { setAccountModalOpen(true); listMemberSessions().then((s) => { setAccSessions(s); setAccSessionId(s.find((x) => x.isCurrent === 1)?.id ?? s[0]?.id) }).catch(() => {}) }}>
  从花名册导入账号
</Button>
```
4. 选届别后加载未注册成员（弹窗内 Select `onChange`）：
```tsx
const loadUnregistered = (sid?: number) => {
  if (!sid) { setUnregistered([]); return }
  listUnregisteredMembers(sid).then(setUnregistered).catch(() => {})
}
```
5. 提交：
```tsx
const handleImportAccounts = async () => {
  if (!accSessionId || !accSelected.length) return
  setImportingAccounts(true)
  try {
    const r = await importAccounts({ sessionId: accSessionId, memberIds: accSelected })
    message.success(`创建 ${r.created} 个账号${r.skipped ? `，跳过 ${r.skipped}` : ''}`)
    setAccountModalOpen(false); setAccSelected([]); fetchList()
  } catch { /* 已提示 */ } finally { setImportingAccounts(false) }
}
```
6. 弹窗 JSX（加在现有新增/编辑 `GlassModal` 之前）：
```tsx
<GlassModal
  title="从花名册导入注册账号"
  open={accountModalOpen}
  onCancel={() => setAccountModalOpen(false)}
  footer={<Space>
    <Button onClick={() => setAccountModalOpen(false)}>取消</Button>
    <Button type="primary" loading={importingAccounts} disabled={!accSelected.length} onClick={handleImportAccounts}>
      导入选中账号（{accSelected.length}）
    </Button>
  </Space>}>
  <Form layout="vertical">
    <Form.Item label="选择届别">
      <Select
        style={{ width: 220 }}
        value={accSessionId}
        options={accSessions.map((s) => ({ value: s.id, label: s.isCurrent === 1 ? `${s.name}（当前）` : s.name }))}
        onChange={(v) => { setAccSessionId(v); setAccSelected([]); loadUnregistered(v) }}
      />
    </Form.Item>
  </Form>
  <p style={{ color: '#999', fontSize: 12 }}>
    仅显示「学号」且未注册账号的成员；用户名 = 学号，默认密码 123456，角色按职位自动映射（可后续在用户管理中调整）。
  </p>
  <Table
    size="small" rowKey="id" pagination={false}
    dataSource={unregistered}
    rowSelection={{ selectedRowKeys: accSelected, onChange: (keys) => setAccSelected(keys.map(Number)) }}
    columns={[
      { title: '姓名', dataIndex: 'name', width: 120 },
      { title: '学号', dataIndex: 'studentNo', width: 150 },
      { title: '部门', dataIndex: 'deptName', width: 120 },
      { title: '职位', dataIndex: 'positionLabel', width: 100 },
    ]}
  />
</GlassModal>
```
（`Table` 已在 UserList 使用；`GlassModal`/`Space` 已 import。）

- [ ] **Step 2: 类型检查 + 提交**

Run: `cd pams-web && npx tsc --noEmit`
Expected: PASS。

```bash
git add pams-web/src/pages/admin/UserList.tsx
git commit -m "feat(member): 用户管理-从花名册一键导入注册账号弹窗"
```

---

### Task 12: 种子数据 + 全量验证

**Files:**
- Modify: `pams-backend/src/main/java/com/pams/config/DataSeeder.java`（seed 一届别「第九届」为当前届）
- Verify: 全量 `mvn test` + `npm run build` + 浏览器手工验证。

**Interfaces:**
- Consumes: `MemberSessionRepository`。
- Produces: dev 库启动即有「第九届」当前届，可直接导入真实 Excel。

- [ ] **Step 1: 加届别种子**

`DataSeeder.java`：
- 构造参数与字段加 `MemberSessionRepository memberSessionRepository`（`com.pams.module.member.repository.MemberSessionRepository`）。
- `seedOrg()` 末尾（`roleRepository.save(r)` 循环后）加：
```java
if (memberSessionRepository.count() > 0) return; // 幂等（但 seedOrg 已有 departmentRepository.count()>0 短路，此处兜底）
MemberSession s9 = new MemberSession();
s9.setName("第九届"); s9.setIsCurrent(1); s9.setSortOrder(1);
s9.setCreatedAt(LocalDateTime.now()); s9.setUpdatedAt(LocalDateTime.now());
memberSessionRepository.save(s9);
```
> 注意：`seedOrg()` 顶部已 `if (departmentRepository.count() > 0) return;`，届别种子会随部门种子在同一分支内执行，幂等。把届别种子放 `saveUser("staff", ...)` 之后即可。若担心 `memberSessionRepository.count()>0` 短路的嵌套，直接在 `seedOrg()` 内部门/角色/账号之后追加，无需额外短路。

- [ ] **Step 2: 全量后端测试**

Run: `cd pams-backend && mvn test`
Expected: 全绿（新增 4 个测试类 + 既有 ~124 用例）。

- [ ] **Step 3: 前端构建**

Run: `cd pams-web && npm run build`
Expected: 构建成功（tsc + vite）。

- [ ] **Step 4: 手工验证（浏览器）**

启动后端（`start.bat`）+ 前端 dev，用 `zhuren`/123456 登录：
1. 侧边栏出现「成员管理」；`staff`/123456 登录看不到且直达 `/members` 返回 403。
2. 成员管理页默认选中「第九届」，下载模板 → 导入你提供的登记表 xlsx → 结果弹窗显示成功/失败行。
3. 统计卡片、部门/职位/状态筛选、搜索、分页正常。
4. 点某成员「详情」→ 详情页展示基础信息 + 排班/考勤/素拓聚合。
5. 「换届归档」→ 确认后该届在职批量变往届。
6. 用户管理 →「从花名册导入账号」→ 选第九届 → 勾选未注册成员 → 导入成功，登录新账号验证。

- [ ] **Step 5: 提交**

```bash
git add pams-backend/src/main/java/com/pams/config/DataSeeder.java
git commit -m "feat(member): 种子届别「第九届」当前届"
```

---

## Self-Review（对照 spec 逐条核对）

- **spec §1 数据模型**：Task 1 V13 迁移实现 member_session + member（唯一键、软删除、枚举码）→ ✅
- **spec §2 接口清单**：Task 2/3/4/5 覆盖全部端点（含 import/template/export/unregistered/import-accounts/archive/stats）→ ✅
- **spec §3 导入**：Task 4 表头定位 + 部门前向填充 + 归一化 + 去重 + 失败行 + 模板/导出 → ✅
- **spec §4 前端**：Task 7/8/9/10/11 覆盖 API 层、列表页、详情页、路由菜单、用户管理弹窗 → ✅
- **spec §5 详情页**：Task 3（聚合接口）+ Task 9（页面）→ ✅
- **spec §6 权限矩阵**：Task 2-5 `@PreAuthorize(LEADER)`、账号导入 `ADMIN`、Task 10 `RequireRole(LEADER_ROLES)` + 菜单 `roleLevel>=3` → ✅
- **spec §7 测试**：Task 1-6 后端测试 + Task 12 全量验证 → ✅
- **spec 非目标**：未做成员-sys_user 强关联、未做成员自助、未做排班/签到双向联动、未引入通知 → ✅

**已知偏差**：迁移 SQL 去掉了 `ON UPDATE CURRENT_TIMESTAMP`（H2 MySQL 模式不支持），`updated_at` 由 Service 显式赋值 —— 行为等价，测试用同一脚本已验证。

**环境注意**：工作树有他处会话未提交的前端改动（`pams-web/index.html` 等），只 add 本模块文件，禁止 `git add -A`。
