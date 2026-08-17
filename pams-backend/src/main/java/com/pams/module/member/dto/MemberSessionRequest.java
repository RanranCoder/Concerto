package com.pams.module.member.dto;

public record MemberSessionRequest(String name, Integer isCurrent, Integer sortOrder, String remark) {
}
