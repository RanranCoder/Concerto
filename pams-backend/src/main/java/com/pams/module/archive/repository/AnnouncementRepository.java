package com.pams.module.archive.repository;

import com.pams.module.archive.entity.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long>,
        JpaSpecificationExecutor<Announcement> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Announcement a where a.id=:id")
    java.util.Optional<Announcement> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    /**
     * 最近 5 条公告（publishTime 倒序，再按 createdAt 倒序）。
     * 替代 findAll 全表遍历后内存排序/截断。
     * DESC 默认 NULLS LAST，与原 nullsLast(reverseOrder()) 一致。
     * @SQLRestriction(deleted=0) 自动生效。
     */
    List<Announcement> findTop5ByOrderByPublishTimeDescCreatedAtDesc();
}
