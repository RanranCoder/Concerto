package com.pams.module.system;

/**
 * 系统信息：设置页展示。uploadDir 为绝对路径（路径拼接目录独立于进程 cwd）。
 * @param version  应用版本（git 构建哈希，未配置时取 pom 版本号）
 * @param uploadDir 上传文件存储目录绝对路径
 * @param ping     健康检查结果（pong 表示服务正常）
 */
public record SystemInfoVO(String version, String uploadDir, String ping) {
}
