package com.pams.module.org.dto;

/** Minimal account directory for organization management; never returns credentials or contacts. */
public record OrgAccountVO(Long id, String username, String realName, Long deptId,
                           String roleName, Integer status) {}
