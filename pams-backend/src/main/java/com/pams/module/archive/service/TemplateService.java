package com.pams.module.archive.service;

import com.pams.common.BizException;
import com.pams.module.archive.dto.TemplateRequest;
import com.pams.module.archive.entity.FileRecord;
import com.pams.module.archive.entity.TemplateAsset;
import com.pams.module.archive.repository.FileRecordRepository;
import com.pams.module.archive.repository.TemplateAssetRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TemplateService {
    private final TemplateAssetRepository repository;
    private final FileRecordRepository fileRecordRepository;
    public TemplateService(TemplateAssetRepository repository, FileRecordRepository fileRecordRepository) {
        this.repository = repository;
        this.fileRecordRepository = fileRecordRepository;
    }

    public List<Map<String, Object>> list(String category) {
        Sort sort = Sort.by(Sort.Direction.DESC, "id");
        List<TemplateAsset> all;
        if (category != null && !category.isBlank()) {
            all = repository.findAll((root, q, cb) -> cb.equal(root.get("category"), category), sort);
        } else {
            all = repository.findAll(sort);
        }
        Set<Long> ids = all.stream().map(TemplateAsset::getFileId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> filenameById = ids.isEmpty()
                ? Map.of()
                : fileRecordRepository.findAllByIdIn(ids).stream()
                        .collect(Collectors.toMap(FileRecord::getId, FileRecord::getFilename));
        return all.stream().map(t -> toVo(t, filenameById)).toList();
    }

    private Map<String, Object> toVo(TemplateAsset t, Map<Long, String> filenameById) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", t.getId());
        vo.put("name", t.getName());
        vo.put("category", t.getCategory());
        vo.put("description", t.getDescription());
        vo.put("fileId", t.getFileId());
        vo.put("originFilename", t.getFileId() == null ? null : filenameById.get(t.getFileId()));
        vo.put("createdBy", t.getCreatedBy());
        vo.put("createdAt", t.getCreatedAt());
        return vo;
    }

    public TemplateAsset getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2402, "模板不存在"));
    }

    @Transactional
    public TemplateAsset create(Long createdBy, TemplateRequest req) {
        TemplateAsset t = new TemplateAsset();
        t.setCreatedBy(createdBy);
        t.setCreatedAt(LocalDateTime.now());
        t.setDeleted(0);
        apply(t, req);
        return repository.save(t);
    }

    @Transactional
    public void update(Long id, TemplateRequest req) {
        apply(getEntity(id), req);
    }

    @Transactional
    public void delete(Long id) {
        TemplateAsset t = getEntity(id);
        t.setDeleted(1);
        repository.save(t);
    }

    private void apply(TemplateAsset t, TemplateRequest req) {
        t.setName(req.getName());
        t.setCategory(req.getCategory());
        t.setDescription(req.getDescription());
        t.setFileId(req.getFileId());
    }
}
