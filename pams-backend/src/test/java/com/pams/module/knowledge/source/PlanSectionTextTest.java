package com.pams.module.knowledge.source;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PlanSectionTextTest {
    @Test void formatsFlowBeforeItCanBeSplitIntoChunks() {
        assertThat(PlanSectionText.readable("[{\"step\":\"签到\",\"detail\":\"领取资料\"}]"))
                .isEqualTo("1. 环节：签到；内容：领取资料");
    }
    @Test void preservesBudgetZerosAndDecimalAmounts() {
        assertThat(PlanSectionText.readable("[{\"item\":\"资料\",\"quantity\":0,\"unitPrice\":1.5,\"totalPrice\":0}]"))
                .contains("项目：资料", "数量：0", "单价：1.5", "合计：0");
    }
    @Test void doesNotRewriteProseOrUnknownJson() {
        for (String text : new String[]{"普通文本", "[{\"x\":1}]", "[{\"item\":\"截断", "[1]"})
            assertThat(PlanSectionText.readable(text)).isEqualTo(text);
    }
}
