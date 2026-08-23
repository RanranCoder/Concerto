package com.pams.module.content.repository;

import com.pams.module.content.entity.Article;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ArticleRepository extends JpaRepository<Article, Long>,
        JpaSpecificationExecutor<Article> {
    @Query("SELECT a FROM Article a WHERE a.deleted = 0 AND a.status <> 'PUBLISHED' " +
           "AND a.deadline IS NOT NULL AND a.deadline <= :threshold")
    List<Article> findOverdue(@Param("threshold") LocalDateTime threshold);

    /**
     * 最近 5 篇已发布推文（publishTime 倒序）。
     * 替代 findAll 全表遍历后内存过滤/排序/截断。
     * DESC 默认 NULLS LAST（MySQL/H2），与原 nullsLast(reverseOrder()) 一致。
     * @SQLRestriction(deleted=0) 自动生效。
     */
    List<Article> findTop5ByStatusOrderByPublishTimeDesc(Article.ArticleStatus status);
}
