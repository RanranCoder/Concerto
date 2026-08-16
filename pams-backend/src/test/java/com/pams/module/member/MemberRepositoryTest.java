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
        s.setName("测试届甲"); s.setIsCurrent(1); s.setSortOrder(1);
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
        s.setName("测试届乙"); s.setCreatedAt(LocalDateTime.now()); s.setUpdatedAt(LocalDateTime.now());
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
        s.setName("测试届丙"); s.setCreatedAt(LocalDateTime.now()); s.setUpdatedAt(LocalDateTime.now());
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
