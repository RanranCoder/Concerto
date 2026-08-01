package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.module.activity.dto.ScoreRecordRequest;
import com.pams.module.activity.dto.ScoreRuleRequest;
import com.pams.module.activity.entity.ScoreRecord;
import com.pams.module.activity.entity.ScoreRule;
import com.pams.module.activity.repository.ScoreRecordRepository;
import com.pams.module.activity.repository.ScoreRuleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ScoreService {
    private final ScoreRecordRepository recordRepo;
    private final ScoreRuleRepository ruleRepo;

    @Autowired
    public ScoreService(ScoreRecordRepository recordRepo, ScoreRuleRepository ruleRepo) {
        this.recordRepo = recordRepo;
        this.ruleRepo = ruleRepo;
    }
    public ScoreService(ScoreRecordRepository recordRepo) {
        this(recordRepo, null);
    }

    public int computeTotal(String dimensionScores) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(dimensionScores);
            int sum = 0;
            for (com.fasterxml.jackson.databind.JsonNode v : node) sum += v.asInt();
            return sum;
        } catch (Exception e) {
            return 0;
        }
    }

    @Transactional
    public Long createRecord(ScoreRecord r) {
        r.setTotal(computeTotal(r.getDimensionScores()));
        return recordRepo.save(r).getId();
    }

    // ---- 规则 CRUD ----

    public List<ScoreRule> listRules(Long activityId) {
        return ruleRepo.findByActivityIdOrderBySortOrderAsc(activityId);
    }

    public List<ScoreRecord> listRecords(Long activityId) {
        return recordRepo.findByActivityId(activityId);
    }

    @Transactional
    public Long createRule(ScoreRuleRequest req) {
        ScoreRule rule = new ScoreRule();
        rule.setActivityId(req.getActivityId());
        rule.setDimensionName(req.getDimensionName());
        rule.setFullMarks(req.getFullMarks());
        rule.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        return ruleRepo.save(rule).getId();
    }

    @Transactional
    public void updateRule(Long id, ScoreRuleRequest req) {
        ScoreRule rule = ruleRepo.findById(id)
                .orElseThrow(() -> new BizException(2202, "评分规则不存在"));
        rule.setDimensionName(req.getDimensionName());
        rule.setFullMarks(req.getFullMarks());
        rule.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        ruleRepo.save(rule);
    }

    @Transactional
    public void deleteRule(Long id) {
        ruleRepo.findById(id).orElseThrow(() -> new BizException(2202, "评分规则不存在"));
        ruleRepo.deleteById(id);
    }

    // ---- 记录 CRUD ----

    @Transactional
    public Long createRecord(ScoreRecordRequest req) {
        ScoreRecord r = new ScoreRecord();
        r.setActivityId(req.getActivityId());
        r.setTeamName(req.getTeamName());
        r.setGroupName(req.getGroupName());
        r.setDimensionScores(req.getDimensionScores());
        r.setRankNo(req.getRankNo());
        r.setRemark(req.getRemark());
        r.setCreatedAt(LocalDateTime.now());
        return createRecord(r);
    }

    @Transactional
    public void updateRecord(Long id, ScoreRecordRequest req) {
        ScoreRecord r = recordRepo.findById(id)
                .orElseThrow(() -> new BizException(2203, "评分记录不存在"));
        r.setTeamName(req.getTeamName());
        r.setGroupName(req.getGroupName());
        r.setDimensionScores(req.getDimensionScores());
        r.setTotal(computeTotal(req.getDimensionScores()));
        r.setRankNo(req.getRankNo());
        r.setRemark(req.getRemark());
        recordRepo.save(r);
    }

    @Transactional
    public void deleteRecord(Long id) {
        recordRepo.findById(id).orElseThrow(() -> new BizException(2203, "评分记录不存在"));
        recordRepo.deleteById(id);
    }
}
