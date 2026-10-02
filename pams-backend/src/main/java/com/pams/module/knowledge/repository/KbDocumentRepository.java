package com.pams.module.knowledge.repository;

import com.pams.module.knowledge.entity.KbDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface KbDocumentRepository extends JpaRepository<KbDocument, Long>,
        JpaSpecificationExecutor<KbDocument> {

    /** 知识库页面内容台账：按类型/关键字筛选 */
    Page<KbDocument> findByDocTypeAndStatus(String docType, String status, Pageable pageable);

    Page<KbDocument> findByStatus(String status, Pageable pageable);

    /** 索引重建遍历用：只取该进索引的（草稿/归档不进） */
    List<KbDocument> findByStatusOrderByIdAsc(String status);

    long countByStatus(String status);
}
