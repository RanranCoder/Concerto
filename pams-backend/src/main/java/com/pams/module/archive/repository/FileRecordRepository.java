package com.pams.module.archive.repository;

import com.pams.module.archive.entity.FileRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface FileRecordRepository extends JpaRepository<FileRecord, Long> {
    java.util.Optional<FileRecord> findFirstByPath(String path);

    List<FileRecord> findAllByIdIn(Collection<Long> ids);
}
