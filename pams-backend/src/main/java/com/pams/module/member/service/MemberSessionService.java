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
