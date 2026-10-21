package com.pams.module.member.dto;

import java.util.List;
import java.util.Map;

/** 每个选中成员必须明确指定当前租户的已发布角色 ID。 */
public record AccountImportRequest(Long sessionId, List<Long> memberIds, Map<Long, Long> roleIds) {}
