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
}
