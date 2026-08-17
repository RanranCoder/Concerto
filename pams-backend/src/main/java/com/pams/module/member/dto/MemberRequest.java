package com.pams.module.member.dto;

public record MemberRequest(Long sessionId, Long deptId, String position, String name, String gender,
                            String studentNo, String className, String phone, String politicalStatus, String status, String remark) {}
