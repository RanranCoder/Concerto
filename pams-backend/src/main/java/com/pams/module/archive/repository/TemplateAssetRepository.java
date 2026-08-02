package com.pams.module.archive.repository;

import com.pams.module.archive.entity.TemplateAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TemplateAssetRepository extends JpaRepository<TemplateAsset, Long>,
        JpaSpecificationExecutor<TemplateAsset> {
}
