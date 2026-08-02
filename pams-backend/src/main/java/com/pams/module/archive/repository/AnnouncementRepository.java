package com.pams.module.archive.repository;

import com.pams.module.archive.entity.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long>,
        JpaSpecificationExecutor<Announcement> {
}
