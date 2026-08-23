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
        // 脱敏：仅返回目录名，去掉盘符/用户主目录等绝对路径前缀，避免向登录用户泄露服务器目录结构
        Path abs = Path.of(uploadDir).toAbsolutePath().normalize();
        String safe = abs.getFileName() == null ? uploadDir : abs.getFileName().toString();
        return Result.ok(new SystemInfoVO(version, safe, "pong"));
    }
}
