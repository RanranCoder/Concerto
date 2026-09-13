package com.pams.module.archive.repository;

import com.pams.module.archive.entity.AnnouncementRead;
import com.pams.module.archive.entity.AnnouncementReadId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AnnouncementReadRepository extends JpaRepository<AnnouncementRead, AnnouncementReadId> {

    List<AnnouncementRead> findByUserId(Long userId);

    boolean existsByAnnouncementIdAndUserId(Long announcementId, Long userId);

    /** 判断一批公告中哪些已被某用户读过（一次性取已读 id 集合，避免逐条 exists 查询） */
    @Query("select r.announcementId from AnnouncementRead r where r.userId = :userId")
    List<Long> findReadIdsByUserId(Long userId);
}
