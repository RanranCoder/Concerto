package com.pams.module.archive.repository;

import com.pams.module.archive.entity.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface MaterialRepository extends JpaRepository<Material, Long>,
        JpaSpecificationExecutor<Material> {

    /**
     * 最近 5 条材料（createdAt 倒序）。
     * 替代 findAll 全表遍历后内存排序/截断。
     * DESC 默认 NULLS LAST，与原 nullsLast(reverseOrder()) 一致。
     * @SQLRestriction(deleted=0) 自动生效。
     */
    List<Material> findTop5ByOrderByCreatedAtDesc();
}
