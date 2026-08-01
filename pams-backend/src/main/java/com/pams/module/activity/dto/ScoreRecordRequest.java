package com.pams.module.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ScoreRecordRequest {
    @NotNull(message = "活动ID不能为空")
    private Long activityId;
    @NotBlank(message = "队伍名称不能为空")
    private String teamName;
    private String groupName;
    /** JSON 字符串，如 {"1":28,"2":18,"3":16}，total 由服务端求和后写入 */
    private String dimensionScores;
    private Integer rankNo;
    private String remark;
}
