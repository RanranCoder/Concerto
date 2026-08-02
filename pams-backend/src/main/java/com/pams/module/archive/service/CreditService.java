package com.pams.module.archive.service;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.module.archive.dto.CreditRequest;
import com.pams.module.archive.entity.CreditRecord;
import com.pams.module.archive.repository.CreditRecordRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CreditService {
    private final CreditRecordRepository repository;
    public CreditService(CreditRecordRepository repository) { this.repository = repository; }

    public PageResult<Map<String, Object>> page(String keyword, Long userId, Long activityId, int page, int size) {
        Specification<CreditRecord> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                ps.add(cb.or(cb.like(root.get("personName"), like),
                        cb.like(root.get("studentNo"), like),
                        cb.like(root.get("project"), like)));
            }
            if (userId != null) ps.add(cb.equal(root.get("userId"), userId));
            if (activityId != null) ps.add(cb.equal(root.get("activityId"), activityId));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<CreditRecord> p = repository.findAll(spec, PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")));

        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(p.getContent().stream().map(this::toVo).toList());
        r.setTotal(p.getTotalElements()); r.setCurrent(page); r.setSize(size);
        return r;
    }

    public Map<String, Object> toVo(CreditRecord c) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", c.getId());
        vo.put("userId", c.getUserId());
        vo.put("personName", c.getPersonName());
        vo.put("studentNo", c.getStudentNo() == null ? "" : c.getStudentNo());
        vo.put("activityId", c.getActivityId());
        vo.put("project", c.getProject());
        vo.put("credit", c.getCredit());
        vo.put("basis", c.getBasis() == null ? "" : c.getBasis());
        vo.put("remark", c.getRemark() == null ? "" : c.getRemark());
        vo.put("recordBy", c.getRecordBy());
        vo.put("createdAt", c.getCreatedAt());
        return vo;
    }

    public CreditRecord getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2403, "加分记录不存在"));
    }

    @Transactional
    public CreditRecord create(Long recordBy, CreditRequest req) {
        CreditRecord c = new CreditRecord();
        c.setRecordBy(recordBy);
        c.setCreatedAt(LocalDateTime.now());
        apply(c, req);
        return repository.save(c);
    }

    @Transactional
    public void update(Long id, CreditRequest req) {
        apply(getEntity(id), req);
    }

    /** 素拓加分记录为流水账，无逻辑删除列，删除即物理删除 */
    @Transactional
    public void delete(Long id) {
        repository.delete(getEntity(id));
    }

    private void apply(CreditRecord c, CreditRequest req) {
        c.setUserId(req.getUserId());
        c.setPersonName(req.getPersonName());
        c.setStudentNo(req.getStudentNo());
        c.setActivityId(req.getActivityId());
        c.setProject(req.getProject());
        // 素拓分数保留两位小数（与 credit DECIMAL(4,2) 对齐）
        c.setCredit(req.getCredit() == null ? null : req.getCredit().setScale(2, RoundingMode.HALF_UP));
        c.setBasis(req.getBasis());
        c.setRemark(req.getRemark());
    }
}
