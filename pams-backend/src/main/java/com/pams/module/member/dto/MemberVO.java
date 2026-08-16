package com.pams.module.member.dto;

import java.time.LocalDateTime;

public record MemberVO(Long id, Long sessionId, String sessionName, Long deptId, String deptName,
                       String position, String positionLabel, String name, String gender, String studentNo,
                       String className, String phone, String politicalStatus, String status, String statusLabel,
                       String remark, LocalDateTime createdAt, LocalDateTime updatedAt) {}
