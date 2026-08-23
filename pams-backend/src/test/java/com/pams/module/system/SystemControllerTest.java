package com.pams.module.system;

import com.pams.common.Result;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SystemControllerTest {

    @Test
    void info_returnsMaskedRelativeUploadDir() {
        // 默认相对配置 ./uploads：脱敏后只返回目录名，不泄露盘符/路径分隔符/用户主目录
        SystemController c = new SystemController("1.0.0", "./uploads");
        Result<SystemInfoVO> r = c.info();
        assertThat(r.getData().uploadDir()).isEqualTo("uploads");
        assertThat(r.getData().uploadDir()).doesNotContain("\\", "/", ":");
    }

    @Test
    void info_masksAbsoluteUploadDir() {
        // 绝对路径配置：仍只返回目录名，去掉盘符/用户主目录前缀
        String abs = Path.of("uploads").toAbsolutePath().normalize().toString();
        SystemController c = new SystemController("1.0.0", abs);
        Result<SystemInfoVO> r = c.info();
        assertThat(r.getData().uploadDir()).isEqualTo("uploads");
        assertThat(r.getData().version()).isEqualTo("1.0.0");
        assertThat(r.getData().ping()).isEqualTo("pong");
    }
}
