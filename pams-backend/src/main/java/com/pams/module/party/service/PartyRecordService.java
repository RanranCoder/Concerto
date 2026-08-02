package com.pams.module.party.service;

import com.pams.common.BizException;
import com.pams.module.party.dto.PartyInvestigationRequest;
import com.pams.module.party.dto.PartyRegisterRequest;
import com.pams.module.party.dto.PartyRosterRequest;
import com.pams.module.party.dto.PartyTransferRequest;
import com.pams.module.party.entity.PartyInvestigation;
import com.pams.module.party.entity.PartyRegister;
import com.pams.module.party.entity.PartyRoster;
import com.pams.module.party.entity.PartyTransfer;
import com.pams.module.party.repository.PartyInvestigationRepository;
import com.pams.module.party.repository.PartyMemberRepository;
import com.pams.module.party.repository.PartyRegisterRepository;
import com.pams.module.party.repository.PartyRosterRepository;
import com.pams.module.party.repository.PartyTransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 党务台账中的名单/函调/登记/转移 CRUD，均校验所属成员存在。
 */
@Service
public class PartyRecordService {
    private final PartyMemberRepository memberRepo;
    private final PartyRosterRepository rosterRepo;
    private final PartyInvestigationRepository investigationRepo;
    private final PartyRegisterRepository registerRepo;
    private final PartyTransferRepository transferRepo;

    public PartyRecordService(PartyMemberRepository memberRepo,
                              PartyRosterRepository rosterRepo,
                              PartyInvestigationRepository investigationRepo,
                              PartyRegisterRepository registerRepo,
                              PartyTransferRepository transferRepo) {
        this.memberRepo = memberRepo;
        this.rosterRepo = rosterRepo;
        this.investigationRepo = investigationRepo;
        this.registerRepo = registerRepo;
        this.transferRepo = transferRepo;
    }

    private void requireMember(Long memberId) {
        if (memberId == null) {
            throw new BizException(3003, "成员不能为空");
        }
        if (!memberRepo.existsById(memberId)) {
            throw new BizException(3001, "成员不存在");
        }
    }

    // ===================== 名单 roster =====================

    public List<PartyRoster> listRosters(String type, String issueNo) {
        if (issueNo != null && !issueNo.isBlank()) {
            return rosterRepo.findByIssueNo(issueNo);
        }
        if (type != null && !type.isBlank()) {
            return rosterRepo.findByRosterType(type);
        }
        return rosterRepo.findAll();
    }

    @Transactional
    public Long createRoster(PartyRosterRequest req) {
        PartyRoster r = new PartyRoster();
        applyRoster(r, req);
        r.setCreatedAt(LocalDateTime.now());
        return rosterRepo.save(r).getId();
    }

    @Transactional
    public void updateRoster(Long id, PartyRosterRequest req) {
        PartyRoster r = rosterRepo.findById(id).orElseThrow(() -> new BizException(3004, "名单记录不存在"));
        applyRoster(r, req);
        rosterRepo.save(r);
    }

    @Transactional
    public void deleteRoster(Long id) {
        if (!rosterRepo.existsById(id)) {
            throw new BizException(3004, "名单记录不存在");
        }
        rosterRepo.deleteById(id);
    }

    private void applyRoster(PartyRoster r, PartyRosterRequest req) {
        r.setRosterType(req.getRosterType());
        r.setIssueNo(req.getIssueNo());
        r.setName(req.getName());
        r.setGender(req.getGender());
        r.setStudentNo(req.getStudentNo());
        r.setClassName(req.getClassName());
        r.setBranchName(req.getBranchName());
        r.setRemark(req.getRemark());
    }

    // ===================== 函调 investigation =====================

    public List<PartyInvestigation> listInvestigations(Long memberId) {
        if (memberId != null) {
            return investigationRepo.findByMemberId(memberId);
        }
        return investigationRepo.findAll();
    }

    @Transactional
    public Long createInvestigation(PartyInvestigationRequest req) {
        requireMember(req.getMemberId());
        PartyInvestigation e = new PartyInvestigation();
        applyInvestigation(e, req);
        e.setCreatedAt(LocalDateTime.now());
        return investigationRepo.save(e).getId();
    }

    @Transactional
    public void updateInvestigation(Long id, PartyInvestigationRequest req) {
        PartyInvestigation e = investigationRepo.findById(id)
                .orElseThrow(() -> new BizException(3005, "函调记录不存在"));
        requireMember(req.getMemberId());
        applyInvestigation(e, req);
        investigationRepo.save(e);
    }

    @Transactional
    public void deleteInvestigation(Long id) {
        if (!investigationRepo.existsById(id)) {
            throw new BizException(3005, "函调记录不存在");
        }
        investigationRepo.deleteById(id);
    }

    private void applyInvestigation(PartyInvestigation e, PartyInvestigationRequest req) {
        e.setMemberId(req.getMemberId());
        e.setFatherName(req.getFatherName());
        e.setFatherBranch(req.getFatherBranch());
        e.setFatherBranchAddr(req.getFatherBranchAddr());
        e.setMotherName(req.getMotherName());
        e.setMotherBranch(req.getMotherBranch());
        e.setMotherBranchAddr(req.getMotherBranchAddr());
        e.setRelativeName(req.getRelativeName());
        e.setRelativeBranch(req.getRelativeBranch());
        e.setRelativeBranchAddr(req.getRelativeBranchAddr());
    }

    // ===================== 登记 register =====================

    public List<PartyRegister> listRegisters(Long memberId) {
        if (memberId != null) {
            return registerRepo.findByMemberId(memberId);
        }
        return registerRepo.findAll();
    }

    @Transactional
    public Long createRegister(PartyRegisterRequest req) {
        requireMember(req.getMemberId());
        PartyRegister e = new PartyRegister();
        applyRegister(e, req);
        e.setCreatedAt(LocalDateTime.now());
        return registerRepo.save(e).getId();
    }

    @Transactional
    public void updateRegister(Long id, PartyRegisterRequest req) {
        PartyRegister e = registerRepo.findById(id).orElseThrow(() -> new BizException(3006, "登记记录不存在"));
        requireMember(req.getMemberId());
        applyRegister(e, req);
        registerRepo.save(e);
    }

    @Transactional
    public void deleteRegister(Long id) {
        if (!registerRepo.existsById(id)) {
            throw new BizException(3006, "登记记录不存在");
        }
        registerRepo.deleteById(id);
    }

    private void applyRegister(PartyRegister e, PartyRegisterRequest req) {
        e.setMemberId(req.getMemberId());
        e.setCollege(req.getCollege());
        e.setBranch(req.getBranch());
        e.setClassName(req.getClassName());
        e.setName(req.getName());
        e.setGender(req.getGender());
        e.setBirthDate(req.getBirthDate());
        e.setNativePlace(req.getNativePlace());
        e.setNation(req.getNation());
        e.setIdCard(req.getIdCard());
        e.setPhone(req.getPhone());
        e.setHomeAddress(req.getHomeAddress());
        e.setApplyDate(req.getApplyDate());
        e.setEducation(req.getEducation());
        e.setTalkPerson(req.getTalkPerson());
        e.setConditionNote(req.getConditionNote());
        e.setRemark(req.getRemark());
    }

    // ===================== 转移 transfer =====================

    public List<PartyTransfer> listTransfers(Long memberId) {
        if (memberId != null) {
            return transferRepo.findByMemberId(memberId);
        }
        return transferRepo.findAll();
    }

    @Transactional
    public Long createTransfer(PartyTransferRequest req) {
        requireMember(req.getMemberId());
        PartyTransfer e = new PartyTransfer();
        applyTransfer(e, req);
        e.setCreatedAt(LocalDateTime.now());
        return transferRepo.save(e).getId();
    }

    @Transactional
    public void updateTransfer(Long id, PartyTransferRequest req) {
        PartyTransfer e = transferRepo.findById(id).orElseThrow(() -> new BizException(3007, "转移记录不存在"));
        requireMember(req.getMemberId());
        applyTransfer(e, req);
        transferRepo.save(e);
    }

    @Transactional
    public void deleteTransfer(Long id) {
        if (!transferRepo.existsById(id)) {
            throw new BizException(3007, "转移记录不存在");
        }
        transferRepo.deleteById(id);
    }

    private void applyTransfer(PartyTransfer e, PartyTransferRequest req) {
        e.setMemberId(req.getMemberId());
        e.setClassName(req.getClassName());
        e.setName(req.getName());
        e.setGender(req.getGender());
        e.setNation(req.getNation());
        e.setIsProbationary(req.getIsProbationary());
        e.setIdCard(req.getIdCard());
        e.setReceiveOrg(req.getReceiveOrg());
        e.setPhone(req.getPhone());
        e.setWechat(req.getWechat());
        e.setIsOnline(req.getIsOnline());
        e.setSignDate(req.getSignDate());
        e.setRemark(req.getRemark());
    }
}
