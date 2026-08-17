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
                && memberRepo.existsBySessionIdAndStudentNoAndIdNot(req.sessionId(), req.studentNo().trim(), id)) {
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
        Function<Member, String> deptLabel = m -> m.getDeptId() == null ? "主任室" : deptNames.getOrDefault(m.getDeptId(), "未知");
        Function<Member, String> posLabel = m -> MemberEnums.POSITION_LABELS.getOrDefault(m.getPosition(), m.getPosition());
        Function<Member, String> stLabel = m -> MemberEnums.STATUS_LABELS.getOrDefault(m.getStatus(), m.getStatus());
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
                credits.stream().map(c -> new MemberDetailVO.MemberCreditVO(c.getId(), c.getProject(), c.getCredit(),
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
