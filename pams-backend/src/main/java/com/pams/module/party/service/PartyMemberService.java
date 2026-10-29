package com.pams.module.party.service;

import com.pams.common.BizException;
import com.pams.module.notification.service.BusinessNoticeService;
import com.pams.module.notification.service.BusinessNoticeService.Domain;
import com.pams.module.notification.service.BusinessNoticeService.Action;
import com.pams.common.PageResult;
import com.pams.module.party.dto.PartyMemberRequest;
import com.pams.module.party.dto.PartyStageRequest;
import com.pams.module.party.entity.PartyMember;
import com.pams.module.party.entity.PartyStage;
import com.pams.module.party.entity.PartyStageType;
import com.pams.module.party.repository.PartyMemberRepository;
import com.pams.module.party.repository.PartyStageRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PartyMemberService {
    @org.springframework.beans.factory.annotation.Autowired
    private BusinessNoticeService notices;
    private void notice(Domain domain,Action action,int count,Long actor) { if(notices!=null)notices.add(domain,action,count,actor); }
    private final PartyMemberRepository memberRepo;
    private final PartyStageRepository stageRepo;

    public PartyMemberService(PartyMemberRepository memberRepo, PartyStageRepository stageRepo) {
        this.memberRepo = memberRepo;
        this.stageRepo = stageRepo;
    }

    /** 流转记录状态：CURRENT=当前阶段，HISTORY=已流转过的历史阶段。 */
    private static final String STATUS_CURRENT = "CURRENT";
    private static final String STATUS_HISTORY = "HISTORY";

    /**
     * 追加一条 party_stage 流转记录，并把 party_member.political_status 更新为对应阶段的中文身份。
     * 校验阶段合法、只允许按 PartyStageType 顺序逐步推进；新 CURRENT 生效前把旧 CURRENT 标记为历史。
     */
    @Transactional
    public void changeStage(Long memberId, String stage, String issueNo, String startDate, String endDate, String remark) {
        PartyMember m = memberRepo.findById(memberId).orElseThrow(() -> new BizException(3001, "成员不存在"));
        PartyStageType target;
        try {
            target = PartyStageType.valueOf(stage);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BizException(3008, "阶段不合法");
        }
        List<PartyStage> existing = stageRepo.findByMemberIdOrderByStartDateAsc(memberId);
        if (existing != null) {
            int maxOrdinal = existing.stream()
                    .filter(s -> s.getStage() != null)
                    .mapToInt(s -> s.getStage().ordinal())
                    .max().orElse(-1);
            // 已有流转记录时，只允许推进到下一个阶段，禁止乱序跳转/回退
            if (maxOrdinal >= 0 && target.ordinal() != maxOrdinal + 1) {
                throw new BizException(3009, "阶段流转只能按顺序推进");
            }
            // 把旧 CURRENT 标记为历史，保证同一时刻只有一个 CURRENT
            for (PartyStage old : existing) {
                if (STATUS_CURRENT.equals(old.getStatus())) {
                    old.setStatus(STATUS_HISTORY);
                    stageRepo.save(old);
                }
            }
        }
        PartyStage s = new PartyStage();
        s.setMemberId(memberId);
        s.setStage(target);
        s.setIssueNo(issueNo);
        s.setStatus(STATUS_CURRENT);
        s.setStartDate(startDate == null ? null : LocalDate.parse(startDate));
        s.setEndDate(endDate == null ? null : LocalDate.parse(endDate));
        s.setRemark(remark);
        s.setCreatedAt(LocalDateTime.now());
        stageRepo.save(s);
        m.setPoliticalStatus(target.label());
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);
        notice(Domain.PARTY_STAGE,Action.STAGE,1,null);
    }

    /**
     * 成员分页。staff=true（干事）时脱敏：列表不返回身份证/家庭地址/电话。
     * stage 参数兼容传枚举名（ACTIVE）或中文身份（入党积极分子）。
     */
    public PageResult<Map<String, Object>> page(String keyword, String stage, int page, int size, boolean staff) {
        Page<PartyMember> p = memberRepo.findAll((root, q, cb) -> {
            var preds = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                preds.add(cb.or(cb.like(root.get("name"), like),
                                cb.like(root.get("studentNo"), like),
                                cb.like(root.get("className"), like)));
            }
            if (stage != null && !stage.isBlank()) {
                String ps = stage;
                try {
                    ps = PartyStageType.valueOf(stage).label();
                } catch (IllegalArgumentException ignored) {
                    // 已是中文身份，直接用
                }
                preds.add(cb.equal(root.get("politicalStatus"), ps));
            }
            return cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        }, PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")));

        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(p.getContent().stream().map(m -> toVO(m, staff)).toList());
        r.setTotal(p.getTotalElements());
        r.setCurrent(page);
        r.setSize(size);
        return r;
    }

    public Map<String, Object> detail(Long id, boolean staff) {
        PartyMember m = memberRepo.findById(id).orElseThrow(() -> new BizException(3001, "成员不存在"));
        return toVO(m, staff);
    }

    /**
     * 流转历史。
     */
    public List<PartyStage> stages(Long memberId) {
        return stageRepo.findByMemberIdOrderByStartDateAsc(memberId);
    }

    private Map<String, Object> toVO(PartyMember m, boolean staff) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", m.getId());
        vo.put("name", m.getName() == null ? "" : m.getName());
        vo.put("gender", m.getGender() == null ? "" : m.getGender());
        vo.put("nation", m.getNation() == null ? "" : m.getNation());
        if (!staff) {
            vo.put("idCard", m.getIdCard() == null ? "" : m.getIdCard());
        }
        vo.put("birthDate", m.getBirthDate());
        vo.put("nativePlace", m.getNativePlace() == null ? "" : m.getNativePlace());
        vo.put("education", m.getEducation() == null ? "" : m.getEducation());
        if (!staff) {
            vo.put("phone", m.getPhone() == null ? "" : m.getPhone());
            vo.put("homeAddress", m.getHomeAddress() == null ? "" : m.getHomeAddress());
        }
        vo.put("className", m.getClassName() == null ? "" : m.getClassName());
        vo.put("college", m.getCollege() == null ? "" : m.getCollege());
        vo.put("branchName", m.getBranchName() == null ? "" : m.getBranchName());
        vo.put("politicalStatus", m.getPoliticalStatus() == null ? "" : m.getPoliticalStatus());
        vo.put("studentNo", m.getStudentNo() == null ? "" : m.getStudentNo());
        vo.put("remark", m.getRemark() == null ? "" : m.getRemark());
        vo.put("createdAt", m.getCreatedAt());
        vo.put("updatedAt", m.getUpdatedAt());
        return vo;
    }

    @Transactional
    public Long create(PartyMemberRequest req) {
        if (req.getStudentNo() != null && !req.getStudentNo().isBlank()
                && memberRepo.existsByStudentNo(req.getStudentNo().trim())) {
            throw new BizException(3002, "学号已存在");
        }
        PartyMember m = new PartyMember();
        apply(m, req);
        m.setDeleted(0);
        m.setCreatedAt(LocalDateTime.now());
        m.setUpdatedAt(LocalDateTime.now());
        return memberRepo.save(m).getId();
    }

    @Transactional
    public void update(Long id, PartyMemberRequest req) {
        PartyMember m = memberRepo.findById(id).orElseThrow(() -> new BizException(3001, "成员不存在"));
        // 与创建口径一致：学号查重，排除自身
        if (req.getStudentNo() != null && !req.getStudentNo().isBlank()
                && memberRepo.existsByStudentNoAndIdNot(req.getStudentNo().trim(), id)) {
            throw new BizException(3002, "学号已存在");
        }
        apply(m, req);
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);
    }

    @Transactional
    public void delete(Long id) {
        PartyMember m = memberRepo.findById(id).orElseThrow(() -> new BizException(3001, "成员不存在"));
        m.setDeleted(1);
        m.setUpdatedAt(LocalDateTime.now());
        memberRepo.save(m);
    }

    private void apply(PartyMember m, PartyMemberRequest req) {
        m.setName(req.getName());
        m.setGender(req.getGender());
        m.setNation(req.getNation());
        m.setIdCard(req.getIdCard());
        m.setBirthDate(req.getBirthDate());
        m.setNativePlace(req.getNativePlace());
        m.setEducation(req.getEducation());
        m.setPhone(req.getPhone());
        m.setHomeAddress(req.getHomeAddress());
        m.setClassName(req.getClassName());
        m.setCollege(req.getCollege());
        m.setBranchName(req.getBranchName());
        m.setPoliticalStatus(req.getPoliticalStatus());
        m.setStudentNo(req.getStudentNo());
        m.setRemark(req.getRemark());
    }
}
