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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

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
        // 第 7 个参数（事件发布器）传 null：本测试只验业务校验，不涉及知识库索引联动
        // （服务内部对 eventPublisher 做了空判断，与本项目其他服务同一约定）
        service = new MemberService(memberRepo, sessionRepo, deptRepo, creditRepo, attRepo, spRepo, null);
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
    void update_storedNullStudentNo_setsNewStudentNo_noNpe() {
        MemberSession s = new MemberSession(); s.setId(1L); s.setName("第九届");
        when(sessionRepo.findById(1L)).thenReturn(Optional.of(s));
        Member m = new Member(); m.setId(7L); m.setSessionId(1L); m.setStudentNo(null);
        when(memberRepo.findById(7L)).thenReturn(Optional.of(m));
        when(memberRepo.existsBySessionIdAndStudentNoAndIdNot(1L, "20250101", 7L)).thenReturn(false);

        service.update(7L, new MemberRequest(1L, null, "STAFF", "张三", "男", "20250101",
                "班", "123", "共青团员", "ACTIVE", null));

        assertThat(m.getStudentNo()).isEqualTo("20250101");
        verify(memberRepo).save(m);
    }

    @Test
    void update_moveToAnotherSession_conflictingStudentNoThrows() {
        MemberSession target = new MemberSession(); target.setId(2L); target.setName("第十届");
        when(sessionRepo.findById(2L)).thenReturn(Optional.of(target));
        Member m = new Member(); m.setId(7L); m.setSessionId(1L); m.setStudentNo("20250101");
        when(memberRepo.findById(7L)).thenReturn(Optional.of(m));
        when(memberRepo.existsBySessionIdAndStudentNoAndIdNot(2L, "20250101", 7L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(7L, new MemberRequest(2L, null, "STAFF", "张三", "男",
                "20250101", "班", "123", "共青团员", "ACTIVE", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("学号");
    }

    @Test
    void archive_returnsCount() {
        when(memberRepo.archiveSession(eq(1L), any(LocalDateTime.class))).thenReturn(12);
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
        Member m = new Member(); m.setId(5L); m.setDeleted(0L);
        when(memberRepo.findById(5L)).thenReturn(Optional.of(m));
        service.delete(5L);
        assertThat(m.getDeleted()).isEqualTo(5L);
        verify(memberRepo).save(m);
    }

    @Test
    void page_batchesSessionAndDeptLookups_notNPlusOne() {
        MemberSession s1 = new MemberSession(); s1.setId(1L); s1.setName("第九届");
        MemberSession s2 = new MemberSession(); s2.setId(2L); s2.setName("第十届");
        when(sessionRepo.findAllById(anySet())).thenReturn(List.of(s1, s2));
        Department d = new Department(); d.setId(2L); d.setName("文秘部");
        when(deptRepo.findAllById(anySet())).thenReturn(List.of(d));

        Member m1 = new Member(); m1.setId(1L); m1.setSessionId(1L); m1.setDeptId(2L);
        m1.setPosition("STAFF"); m1.setName("张三"); m1.setStatus("ACTIVE");
        Member m2 = new Member(); m2.setId(2L); m2.setSessionId(2L); m2.setDeptId(null);
        m2.setPosition("STAFF"); m2.setName("李四"); m2.setStatus("ACTIVE");
        when(memberRepo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(m1, m2)));

        var r = service.page(null, null, null, null, null, 1, 10);

        assertThat(r.getRecords()).hasSize(2);
        assertThat(r.getRecords().get(0).sessionName()).isEqualTo("第九届");
        assertThat(r.getRecords().get(0).deptName()).isEqualTo("文秘部");
        assertThat(r.getRecords().get(1).sessionName()).isEqualTo("第十届");
        assertThat(r.getRecords().get(1).deptName()).isNull();
        assertThat(r.getTotal()).isEqualTo(2);
        // 批量载入，不应出现逐条 findById 查询
        verify(sessionRepo, never()).findById(anyLong());
        verify(deptRepo, never()).findById(anyLong());
        verify(sessionRepo).findAllById(anySet());
        verify(deptRepo).findAllById(anySet());
    }
}
