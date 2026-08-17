package com.pams.module.member.dto;

/** 该届未注册成员：有学号且学号在 sys_user 无匹配。 */
public record UnregisteredMemberVO(Long id, String name, String studentNo, String deptName, String positionLabel) {}
