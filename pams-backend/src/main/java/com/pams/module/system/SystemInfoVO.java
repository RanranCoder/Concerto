package com.pams.module.system;

/**
 * 系统信息：设置页展示。uploadDir 为脱敏后的上传目录展示名（仅目录名，不含绝对路径，
 * 避免泄露服务器盘符/用户主目录等目录结构）。
 * @param version  应用版本（git 构建哈希，未配置时取 pom 版本号）
 * @param uploadDir 上传目录展示名（脱敏后）
 * @param ping     健康检查结果（pong 表示服务正常）
 */
public record SystemInfoVO(String version, String uploadDir, String ping) {
}
