package com.pams.module.archive.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "file_record")
public class FileRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 255)
    private String filename;
    @Column(nullable = false, length = 255)
    private String storedName;
    @Column(nullable = false, length = 500)
    private String path;
    private Long size;
    @Column(length = 100)
    private String contentType;
    @Column(length = 30)
    private String bizType;
    private Long uploaderId;
    private LocalDateTime createdAt;
}
