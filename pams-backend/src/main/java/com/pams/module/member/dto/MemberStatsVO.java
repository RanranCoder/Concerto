package com.pams.module.member.dto;

import java.util.List;

public record MemberStatsVO(long total, List<NameCount> byDept, List<NameCount> byPosition,
                            List<NameCount> byStatus) {
    public record NameCount(String name, long count) {}
}
