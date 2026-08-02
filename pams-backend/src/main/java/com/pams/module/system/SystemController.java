package com.pams.module.system;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pams.common.Result;

import java.nio.file.Path;

/**
 * 系统信息接口（设置页展示）。GET /api/system/info 需要登录（与业务一致）。
 * version 默认取 pom 版本号，可用 `-Dapp.version=xxx` 覆盖为构建哈希。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final String version;
    private final String uploadDir;

    public SystemController(
            @Value("${app.version:1.0.0}") String version,
            @Value("${pams.upload-dir:./uploads}") String uploadDir) {
        this.version = version;
        this.uploadDir = uploadDir;
    }

    @GetMapping("/info")
    public Result<SystemInfoVO> info() {
        String absolute = Path.of(uploadDir).toAbsolutePath().normalize().toString();
        return Result.ok(new SystemInfoVO(version, absolute, "pong"));
    }
}
