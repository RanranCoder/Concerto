package com.pams.module.member.service;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.entity.Department;
import com.pams.module.archive.entity.CreditRecord;
import com.pams.module.archive.repository.CreditRecordRepository;
import com.pams.module.member.dto.*;
import com.pams.module.member.entity.Member;
import com.pams.module.member.entity.MemberSession;
import com.pams.module.member.event.MemberChangedEvent;
import com.pams.module.member.repository.MemberRepository;
import com.pams.module.member.repository.MemberSessionRepository;
import com.pams.module.routine.repository.AttendanceRepository;
import com.pams.module.routine.repository.SchedulePersonRepository;
import com.pams.repository.DepartmentRepository;
import org.springframework.context.ApplicationEventPublisher;
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
    /**
     * V27：成员变更事件 —— 供知识库增量重建。
     *
     * <p>知识库把成员数据做成了可检索投影（{@code MemberSource}），
     * 所以成员一变索引就得跟着变，否则用户问"谁是组织部长"会得到旧答案且不报错（方案 R6）。
     * 单条变更发 {@code MemberChangedEvent.one(id)}；批量发 {@code bulk()}（整源重建）。
     */
    private final ApplicationEventPublisher eventPublisher;

    public MemberService(MemberRepository memberRepo, MemberSessionRepository sessionRepo,
                         DepartmentRepository deptRepo, CreditRecordRepository creditRepo,
                         AttendanceRepository attRepo, SchedulePersonRepository spRepo,
                         ApplicationEventPublisher eventPublisher) {
        this.memberRepo = memberRepo; this.sessionRepo = sessionRepo;
        this.deptRepo = deptRepo; this.creditRepo = creditRepo;
        this.attRepo = attRepo; this.spRepo = spRepo;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 发布"整源变更"（批量导入后调用）。
     *
     * <p>供 {@code MemberImportService} 复用：导入是 {@code saveAll} 批量写入，
     * 逐条发事件在几百人的场景下会往执行队列塞几百个任务。
     * 走整源重建粒度更粗但正确。
     *
     * <p>对 {@code eventPublisher} 做空判断是本项目的既有约定（PlanService / ArticleService 同款）——
     * 单测直接 new 服务时不需要造一个事件发布器。
     */
    public void publishBulkChanged() {
        if (eventPublisher != null) eventPublisher.publishEvent(MemberChangedEvent.bulk());
    }

    private void publishOne(Long memberId) {
        if (eventPublisher != null && memberId != null) {
            eventPublisher.publishEvent(MemberChangedEvent.one(memberId));
        }
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
        r.setRecords(toVOList(p.getContent()));
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
        Long id = memberRepo.save(m).getId();
        // 事务内发布 → AFTER_COMMIT 时才真正重建索引（索引失败不影响本次写入）
        publishOne(id);
        return id;
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
        publishOne(id);
    }

    @Transactional
    public void delete(Long id) {
        Member m = memberRepo.findById(id).orElseThrow(() -> new BizException(2805, "成员不存在"));
        m.setDeleted(m.getId());
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);
        // 源删则索引删：抽取会返回空，索引器据此清理该成员的分块（方案 §2.3 删除语义）
        publishOne(id);
    }

    @Transactional
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        for (Long id : ids) { memberRepo.findById(id).ifPresent(m -> { m.setDeleted(m.getId()); memberRepo.save(m); }); }
        // 按"整源重建"处理：逐条发事件在批量选中很多时会把执行队列塞满，
        // 而整源重建的代价是可控的
        publishBulkChanged();
    }

    @Transactional
    public int archive(Long sessionId) {
        int n = memberRepo.archiveSession(sessionId, LocalDateTime.now());
        // 整届归档 = 大量成员同时变更 → 整源重建
        publishBulkChanged();
        return n;
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
        // 单条组装（详情用）：逐项按需查询一次
        String sessionName = sessionRepo.findById(m.getSessionId()).map(MemberSession::getName).orElse(null);
        String deptName = m.getDeptId() == null ? null
                : deptRepo.findById(m.getDeptId()).map(Department::getName).orElse(null);
        return buildVO(m, sessionName, deptName);
    }

    /** 批量组装：按 sessionId/deptId 集合一次批量载入名称 Map，避免逐条查询（N+1）。 */
    private List<MemberVO> toVOList(List<Member> members) {
        if (members.isEmpty()) return List.of();
        Set<Long> sessionIds = members.stream().map(Member::getSessionId).collect(Collectors.toSet());
        Set<Long> deptIds = members.stream().map(Member::getDeptId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> sessionNames = sessionRepo.findAllById(sessionIds).stream()
                .collect(Collectors.toMap(MemberSession::getId, MemberSession::getName));
        Map<Long, String> deptNames = deptRepo.findAllById(deptIds).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        return members.stream().map(m -> buildVO(m,
                sessionNames.get(m.getSessionId()),
                m.getDeptId() == null ? null : deptNames.get(m.getDeptId()))).toList();
    }

    private MemberVO buildVO(Member m, String sessionName, String deptName) {
        return new MemberVO(m.getId(), m.getSessionId(), sessionName, m.getDeptId(), deptName,
                m.getPosition(), MemberEnums.POSITION_LABELS.getOrDefault(m.getPosition(), m.getPosition()),
                m.getName(), m.getGender(), m.getStudentNo(), m.getClassName(), m.getPhone(),
                m.getPoliticalStatus(), m.getStatus(),
                MemberEnums.STATUS_LABELS.getOrDefault(m.getStatus(), m.getStatus()),
                m.getRemark(), m.getCreatedAt(), m.getUpdatedAt());
    }
}
