package com.pams.module.archive.service;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.module.archive.dto.MaterialRequest;
import com.pams.module.archive.entity.FileRecord;
import com.pams.module.archive.entity.Material;
import com.pams.module.archive.repository.FileRecordRepository;
import com.pams.module.archive.repository.MaterialRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MaterialService {
    private final MaterialRepository repository;
    private final FileRecordRepository fileRecordRepository;
    public MaterialService(MaterialRepository repository, FileRecordRepository fileRecordRepository) {
        this.repository = repository;
        this.fileRecordRepository = fileRecordRepository;
    }

    public PageResult<Map<String, Object>> page(String keyword, String bizType, Long activityId, Long deptId,
                                                int page, int size) {
        Specification<Material> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                ps.add(cb.or(cb.like(root.get("name"), like),
                        cb.like(root.get("description"), like),
                        cb.like(root.get("tag"), like)));
            }
            if (bizType != null && !bizType.isBlank()) ps.add(cb.equal(root.get("bizType"), bizType));
            if (activityId != null) ps.add(cb.equal(root.get("activityId"), activityId));
            if (deptId != null) ps.add(cb.equal(root.get("deptId"), deptId));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<Material> p = repository.findAll(spec, PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")));

        List<Material> content = p.getContent();
        Map<Long, String> filenames = filenamesOf(content);
        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(content.stream().map(m -> toVo(m, filenames)).toList());
        r.setTotal(p.getTotalElements()); r.setCurrent(page); r.setSize(size);
        return r;
    }

    /** 批量取 fileId -> file_record.filename，避免逐个查询 N+1 */
    private Map<Long, String> filenamesOf(List<Material> materials) {
        List<Long> fileIds = materials.stream().map(Material::getFileId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (fileIds.isEmpty()) return Map.of();
        return fileRecordRepository.findAllById(fileIds).stream()
                .collect(java.util.stream.Collectors.toMap(FileRecord::getId, FileRecord::getFilename, (a, b) -> a));
    }

    public Map<String, Object> toVo(Material m, Map<Long, String> filenames) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", m.getId());
        vo.put("name", m.getName());
        vo.put("bizType", m.getBizType());
        vo.put("activityId", m.getActivityId());
        vo.put("deptId", m.getDeptId());
        vo.put("uploaderId", m.getUploaderId());
        vo.put("tag", m.getTag() == null ? "" : m.getTag());
        vo.put("description", m.getDescription() == null ? "" : m.getDescription());
        vo.put("fileId", m.getFileId());
        vo.put("originFilename", m.getFileId() == null ? null : filenames.get(m.getFileId()));
        vo.put("createdAt", m.getCreatedAt());
        return vo;
    }

    public Material getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2401, "材料不存在"));
    }

    @Transactional
    public Material create(Long uploaderId, MaterialRequest req) {
        Material m = new Material();
        m.setUploaderId(uploaderId);
        apply(m, req);
        m.setDeleted(0);
        m.setCreatedAt(LocalDateTime.now());
        return repository.save(m);
    }

    @Transactional
    public void update(Long id, MaterialRequest req) {
        apply(getEntity(id), req);
    }

    @Transactional
    public void delete(Long id) {
        Material m = getEntity(id);
        m.setDeleted(1);
        repository.save(m);
    }

    private void apply(Material m, MaterialRequest req) {
        m.setName(req.getName());
        m.setBizType(req.getBizType());
        m.setActivityId(req.getActivityId());
        m.setDeptId(req.getDeptId());
        m.setTag(req.getTag());
        m.setDescription(req.getDescription());
        m.setFileId(req.getFileId());
    }

    /** 材料归档树：activityId -> bizType -> 材料列表（替代手工"12月26日"汇总包） */
    public List<Map<String, Object>> tree(Long activityId) {
        List<Material> all;
        if (activityId != null) {
            all = repository.findAll((root, q, cb) -> cb.equal(root.get("activityId"), activityId),
                    Sort.by(Sort.Direction.DESC, "id"));
        } else {
            all = repository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        }
        Map<Long, List<Material>> byActivity = new LinkedHashMap<>();
        for (Material m : all) {
            byActivity.computeIfAbsent(m.getActivityId(), k -> new ArrayList<>()).add(m);
        }
        Map<Long, String> filenames = filenamesOf(all);
        List<Map<String, Object>> result = new ArrayList<>();
        byActivity.forEach((aid, list) -> {
            Map<String, List<Map<String, Object>>> byType = new LinkedHashMap<>();
            for (Material m : list) {
                byType.computeIfAbsent(m.getBizType(), k -> new ArrayList<>()).add(toVo(m, filenames));
            }
            List<Map<String, Object>> types = new ArrayList<>();
            byType.forEach((t, materials) -> {
                Map<String, Object> typeNode = new LinkedHashMap<>();
                typeNode.put("bizType", t);
                typeNode.put("materials", materials);
                types.add(typeNode);
            });
            Map<String, Object> activityNode = new LinkedHashMap<>();
            activityNode.put("activityId", aid);
            activityNode.put("bizTypes", types);
            result.add(activityNode);
        });
        return result;
    }
}
