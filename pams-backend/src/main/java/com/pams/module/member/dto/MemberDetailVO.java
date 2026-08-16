package com.pams.module.member.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record MemberDetailVO(MemberVO member, long scheduleCount, long attendanceCount,
                             BigDecimal totalCredit, List<MemberCreditVO> credits) {
    public record MemberCreditVO(Long id, String project, BigDecimal credit, String basis,
                                 String remark, LocalDateTime createdAt) {}
}
