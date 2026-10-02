package com.pams.module.agent.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Agent 定义（对应 V32 {@code agent_definition}）。
 *
 * <p>多 Agent 的注册表：把「角色提示词 / 工具作用域 / 模型」从代码硬编码中抽出来，
 * 变为可配置数据。默认定义由 {@code AgentDefinitionSeeder} 幂等种入，
 * {@code rolePrompt} 为空时回退到代码内置默认（见 {@code AgentRegistry}）。
 */
@Data
@Entity
@Table(name = "agent_definition")
public class AgentDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 标识：coordinator / retrieval / planning / ... */
    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(length = 255)
    private String description;

    /** 角色提示词（NULL 时用代码内置默认） */
    @Column(name = "role_prompt", columnDefinition = "TEXT")
    private String rolePrompt;

    /** 工具作用域 JSON 数组；NULL = 全部可见工具 */
    @Column(name = "tool_scope", columnDefinition = "TEXT")
    private String toolScope;

    @Column(length = 50)
    private String model;

    private BigDecimal temperature;

    @Column(nullable = false)
    private Integer enabled = 1;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
