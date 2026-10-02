package com.pams.module.knowledge.repository;

import com.pams.module.knowledge.entity.KbChunk;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 分块仓储。
 *
 * <p>额外实现 {@link JpaSpecificationExecutor} 而不是用一批 {@code @Query}：
 * 内容台账要支持"来源类型 + 关键词"的自由组合筛选，用 JPQL 拼可选参数
 * 会写成 {@code (:x is null or ...)} 这种写法，在 Hibernate 6 上容易踩到
 * 参数类型推断的坑（而且查不出来时很难定位）。Specification 是类型安全的。
 */
public interface KbChunkRepository extends JpaRepository<KbChunk, Long>,
        JpaSpecificationExecutor<KbChunk> {

    Page<KbChunk> findBySourceType(String sourceType, Pageable pageable);

    List<KbChunk> findBySourceTypeAndSourceIdOrderBySeqAsc(String sourceType, Long sourceId);

    long countBySourceType(String sourceType);

    long countBySourceTypeAndTier(String sourceType, String tier);

    /** 某个源在索引里有多少个"文档"（去重后的 source_id 数），用于覆盖统计 */
    @Query("select count(distinct c.sourceId) from KbChunk c where c.sourceType = :sourceType")
    long countDistinctSourceIdBySourceType(@Param("sourceType") String sourceType);

    /** 重建/增量更新前清理该源的旧分块。物理删除而非软删——分块是派生数据，没有留存价值 */
    @Modifying
    @Query("delete from KbChunk c where c.sourceType = :sourceType and c.sourceId = :sourceId")
    int deleteBySource(@Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    @Modifying
    @Query("delete from KbChunk c where c.sourceType = :sourceType")
    int deleteBySourceType(@Param("sourceType") String sourceType);

    /** 最近一次成功索引的时间（知识库页面"更新于…"用它，而不是任务运行时间 —— 方案 R19） */
    @Query("select max(c.indexedAt) from KbChunk c")
    java.time.LocalDateTime lastIndexedAt();
}
