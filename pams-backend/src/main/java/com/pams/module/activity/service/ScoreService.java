package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.module.activity.dto.ScoreRecordRequest;
import com.pams.module.activity.dto.ScoreRuleRequest;
import com.pams.module.activity.entity.ScoreRecord;
import com.pams.module.activity.entity.ScoreRule;
import com.pams.module.activity.repository.ScoreRecordRepository;
import com.pams.module.activity.repository.ScoreRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ScoreService {
    private static final ObjectReader SCORES = new ObjectMapper().reader()
            .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ScoreRecordRepository recordRepo;
    private final ScoreRuleRepository ruleRepo;
    private final ActivityService activities;

    public ScoreService(ScoreRecordRepository recordRepo, ScoreRuleRepository ruleRepo, ActivityService activities) {
        this.recordRepo = recordRepo;
        this.ruleRepo = ruleRepo;
        this.activities = activities;
    }

    private int computeTotal(Long activityId, String dimensionScores) {
        if (dimensionScores == null || dimensionScores.isBlank()) {
            throw new BizException(400, "请至少填写一个评分维度");
        }
        JsonNode scores;
        try {
            scores = SCORES.readTree(dimensionScores);
        } catch (JsonProcessingException e) {
            throw new BizException(400, "评分维度格式无效");
        }
        if (!scores.isObject() || scores.isEmpty()) throw new BizException(400, "请至少填写一个评分维度");
        Map<String, Integer> limits = new HashMap<>();
        for (ScoreRule rule : listRules(activityId)) {
            limits.put(rule.getId().toString(), rule.getFullMarks());
        }
        long sum = 0;
        for (var field : scores.properties()) {
            Integer max = limits.get(field.getKey());
            JsonNode value = field.getValue();
            if (max == null || !value.isIntegralNumber() || !value.canConvertToInt()
                    || value.intValue() < 0 || value.intValue() > max) {
                throw new BizException(400, "评分维度无效或分值超出允许范围");
            }
            sum += value.intValue();
        }
        if (sum > Integer.MAX_VALUE) throw new BizException(400, "评分总分超出允许范围");
        return (int) sum;
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
        var activity = activities.lockForChildWrite(req.getActivityId());
        ScoreRule rule = new ScoreRule();
        rule.setActivityId(activity.getId());
        rule.setOrgId(activity.getOrgId());
        rule.setDimensionName(req.getDimensionName());
        rule.setFullMarks(req.getFullMarks());
        rule.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        return ruleRepo.save(rule).getId();
    }

    @Transactional
    public void updateRule(Long id, ScoreRuleRequest req) {
        ScoreRule rule = ruleRepo.findById(id)
                .orElseThrow(() -> new BizException(2202, "评分规则不存在"));
        activities.lockForChildWrite(rule.getActivityId());
        rule.setDimensionName(req.getDimensionName());
        rule.setFullMarks(req.getFullMarks());
        rule.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        ruleRepo.save(rule);
    }

    @Transactional
    public void deleteRule(Long id) {
        var rule = ruleRepo.findById(id).orElseThrow(() -> new BizException(2202, "评分规则不存在"));
        activities.lockForChildWrite(rule.getActivityId());
        ruleRepo.deleteById(id);
    }

    // ---- 记录 CRUD ----

    @Transactional
    public Long createRecord(ScoreRecordRequest req) {
        var activity = activities.lockForChildWrite(req.getActivityId());
        ScoreRecord r = new ScoreRecord();
        r.setActivityId(activity.getId());
        r.setOrgId(activity.getOrgId());
        r.setTeamName(req.getTeamName());
        r.setGroupName(req.getGroupName());
        r.setDimensionScores(req.getDimensionScores());
        r.setRankNo(req.getRankNo());
        r.setRemark(req.getRemark());
        r.setCreatedAt(LocalDateTime.now());
        r.setTotal(computeTotal(activity.getId(), req.getDimensionScores()));
        return recordRepo.save(r).getId();
    }

    @Transactional
    public void updateRecord(Long id, ScoreRecordRequest req) {
        ScoreRecord r = recordRepo.findById(id)
                .orElseThrow(() -> new BizException(2203, "评分记录不存在"));
        activities.lockForChildWrite(r.getActivityId());
        int total = computeTotal(r.getActivityId(), req.getDimensionScores());
        r.setTeamName(req.getTeamName());
        r.setGroupName(req.getGroupName());
        r.setDimensionScores(req.getDimensionScores());
        r.setTotal(total);
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
