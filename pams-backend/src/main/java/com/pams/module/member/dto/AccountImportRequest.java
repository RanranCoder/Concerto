package com.pams.module.member.dto;

import java.util.List;
import java.util.Map;

/** 从花名册一键导入账号请求：届别 + 成员 id 列表 + 可选的成员→角色码覆盖。 */
public record AccountImportRequest(Long sessionId, List<Long> memberIds, Map<Long, String> roleCodes) {}
