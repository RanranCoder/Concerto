package com.pams.module.member.dto;

public record MemberSessionVO(Long id, String name, Integer isCurrent, Integer sortOrder, String remark) {
}
