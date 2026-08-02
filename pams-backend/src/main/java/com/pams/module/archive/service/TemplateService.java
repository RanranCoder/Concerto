package com.pams.module.archive.service;

import com.pams.common.BizException;
import com.pams.module.archive.dto.TemplateRequest;
import com.pams.module.archive.entity.TemplateAsset;
import com.pams.module.archive.repository.TemplateAssetRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TemplateService {
    private final TemplateAssetRepository repository;
    public TemplateService(TemplateAssetRepository repository) { this.repository = repository; }

    public List<TemplateAsset> list(String category) {
        Sort sort = Sort.by(Sort.Direction.DESC, "id");
        if (category != null && !category.isBlank()) {
            return repository.findAll((root, q, cb) -> cb.equal(root.get("category"), category), sort);
        }
        return repository.findAll(sort);
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
