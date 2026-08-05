package com.pams.module.seat.service;

import com.pams.common.BizException;
import com.pams.module.seat.dto.SeatLayoutRequest;
import com.pams.module.seat.entity.SeatLayout;
import com.pams.module.seat.repository.SeatLayoutRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SeatLayoutService {

    private final SeatLayoutRepository repository;

    public SeatLayoutService(SeatLayoutRepository repository) { this.repository = repository; }

    public Map<String, Object> toVo(SeatLayout s) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", s.getId());
        vo.put("activityId", s.getActivityId());
        vo.put("name", s.getName());
        vo.put("rows", s.getRows());
        vo.put("cols", s.getCols());
        vo.put("aisleCols", s.getAisleCols() == null ? "" : s.getAisleCols());
        vo.put("aisleWidthRatio", s.getAisleWidthRatio() == null ? 1.5 : s.getAisleWidthRatio());
        vo.put("seatData", s.getSeatData() == null ? "[]" : s.getSeatData());
        vo.put("colorLabels", s.getColorLabels() == null ? "[]" : s.getColorLabels());
        vo.put("isTemplate", s.getIsTemplate() != null && s.getIsTemplate() == 1);
        vo.put("templateCategory", s.getTemplateCategory() == null ? "" : s.getTemplateCategory());
        vo.put("createdBy", s.getCreatedBy());
        vo.put("createdAt", s.getCreatedAt());
        vo.put("updatedAt", s.getUpdatedAt());
        return vo;
    }

    /** 获取活动的当前布局 */
    public Map<String, Object> getByActivity(Long activityId) {
        return repository.findTopByActivityIdOrderByUpdatedAtDesc(activityId)
                .map(this::toVo)
                .orElse(null);
    }

    public List<Map<String, Object>> listByActivity(Long activityId) {
        return repository.findByActivityIdOrderByUpdatedAtDesc(activityId).stream().map(this::toVo).toList();
    }

    public List<Map<String, Object>> listTemplates() {
        return repository.findByIsTemplateOrderByIdAsc(1).stream().map(this::toVo).toList();
    }

    public SeatLayout getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2501, "座位表布局不存在"));
    }

    @Transactional
    public Long create(Long userId, SeatLayoutRequest req) {
        SeatLayout s = new SeatLayout();
        apply(s, req);
        s.setCreatedBy(userId);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        return repository.save(s).getId();
    }

    @Transactional
    public void update(Long id, SeatLayoutRequest req) {
        SeatLayout s = getEntity(id);
        apply(s, req);
        s.setUpdatedAt(LocalDateTime.now());
        repository.save(s);
    }

    /** 保存为模板：复制当前布局为模板 */
    @Transactional
    public Long saveAsTemplate(Long userId, Long id, String templateCategory) {
        SeatLayout src = getEntity(id);
        SeatLayout t = new SeatLayout();
        t.setName(src.getName() + "（模板）");
        t.setRows(src.getRows());
        t.setCols(src.getCols());
        t.setAisleCols(src.getAisleCols());
        t.setAisleWidthRatio(src.getAisleWidthRatio());
        t.setSeatData(src.getSeatData());
        t.setColorLabels(src.getColorLabels());
        t.setIsTemplate(1);
        t.setTemplateCategory(templateCategory == null ? "自定义" : templateCategory);
        t.setCreatedBy(userId);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        return repository.save(t).getId();
    }

    /** 从模板新建活动布局 */
    @Transactional
    public Long createFromTemplate(Long userId, Long templateId, Long activityId, String name) {
        SeatLayout t = getEntity(templateId);
        if (t.getIsTemplate() == null || t.getIsTemplate() != 1) {
            throw new BizException(2502, "该布局不是模板");
        }
        SeatLayout s = new SeatLayout();
        s.setActivityId(activityId);
        s.setName(name == null || name.isBlank() ? t.getName() : name);
        s.setRows(t.getRows());
        s.setCols(t.getCols());
        s.setAisleCols(t.getAisleCols());
        s.setAisleWidthRatio(t.getAisleWidthRatio());
        s.setSeatData(t.getSeatData());
        s.setColorLabels(t.getColorLabels());
        s.setIsTemplate(0);
        s.setCreatedBy(userId);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        return repository.save(s).getId();
    }

    @Transactional
    public void deleteTemplate(Long id) {
        SeatLayout s = getEntity(id);
        if (s.getIsTemplate() != null && s.getIsTemplate() == 1) {
            repository.delete(s);
        } else {
            throw new BizException(2503, "仅模板可删除");
        }
    }

    @Transactional
    public void deleteByActivity(Long activityId) {
        repository.findByActivityIdOrderByUpdatedAtDesc(activityId)
                .forEach(repository::delete);
    }

    private void apply(SeatLayout s, SeatLayoutRequest req) {
        s.setActivityId(req.getActivityId());
        s.setName(req.getName());
        s.setRows(req.getRows() == null ? 10 : req.getRows());
        s.setCols(req.getCols() == null ? 10 : req.getCols());
        s.setAisleCols(req.getAisleCols());
        s.setAisleWidthRatio(req.getAisleWidthRatio() == null
                ? new java.math.BigDecimal("1.5") : req.getAisleWidthRatio());
        s.setSeatData(req.getSeatData() == null ? "[]" : req.getSeatData());
        s.setColorLabels(req.getColorLabels() == null ? "[]" : req.getColorLabels());
        s.setIsTemplate(Boolean.TRUE.equals(req.getAsTemplate()) ? 1 : 0);
        s.setTemplateCategory(req.getTemplateCategory());
    }
}
