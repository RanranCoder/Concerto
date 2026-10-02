package com.pams.module.knowledge.repository;

import com.pams.module.knowledge.entity.KbSnapshot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 快照仓储。
 *
 * <p>实现 {@link JpaSpecificationExecutor}：快照列表要按 specCode 可选筛选，
 * 与 {@code KbChunkRepository} / {@code KbDocumentRepository} 同一套做法 ——
 * 用 JPQL 拼可选参数（{@code (:x is null or ...)}）在 Hibernate 6 上容易踩参数类型推断的坑。
 */
public interface KbSnapshotRepository extends JpaRepository<KbSnapshot, Long>,
        JpaSpecificationExecutor<KbSnapshot> {

    /** 幂等判存：同一 spec + 同一作用域 + 同一截止时刻只允许一行（V28 唯一键） */
    Optional<KbSnapshot> findBySpecCodeAndScopeTypeAndScopeIdAndAsOf(
            String specCode, String scopeType, Long scopeId, LocalDateTime asOf);

    /** 某个作用域下该 spec 的最新一份（生成前判断"数字有没有变"用） */
    Optional<KbSnapshot> findFirstBySpecCodeAndScopeTypeAndScopeIdOrderByAsOfDesc(
            String specCode, String scopeType, Long scopeId);

    List<KbSnapshot> findBySpecCodeAndScopeTypeOrderByAsOfDesc(
            String specCode, String scopeType, Pageable pageable);

    Page<KbSnapshot> findAllByOrderByAsOfDesc(Pageable pageable);

    long countByDeleted(Integer deleted);

    @Query("select count(distinct s.specCode) from KbSnapshot s")
    long countDistinctSpecCodes();

    /** 供知识库页展示"快照更新于…" */
    @Query("select max(s.asOf) from KbSnapshot s")
    LocalDateTime latestAsOf();

    /**
     * 清理旧的同名快照，只保留最近 N 份。
     *
     * <p>为什么需要：快照按日生成会无限堆积，而"在册 87 人"会有一百个历史版本
     * 互相竞争检索配额（方案 R9）。保留策略按 spec 粒度做，避免误删关键时点。
     */
    @Query("select s.id from KbSnapshot s where s.specCode = :specCode "
            + "and s.scopeType = :scopeType and s.scopeId = :scopeId "
            + "order by s.asOf desc")
    List<Long> findIdsNewestFirst(@Param("specCode") String specCode,
                                  @Param("scopeType") String scopeType,
                                  @Param("scopeId") Long scopeId, Pageable pageable);
}
