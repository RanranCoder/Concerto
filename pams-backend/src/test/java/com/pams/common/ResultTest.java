package com.pams.common;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {
    @Test
    void ok_returnsCode200() {
        Result<String> r = Result.ok("x");
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData()).isEqualTo("x");
    }
    @Test
    void fail_returnsGivenCode() {
        Result<Void> r = Result.fail(400, "bad");
        assertThat(r.getCode()).isEqualTo(400);
        assertThat(r.getMessage()).isEqualTo("bad");
    }
}
