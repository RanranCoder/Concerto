package com.pams.module.agent.provider;

import com.pams.common.*;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.llm.*;
import com.pams.security.LoginUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@Service
public class AgentProviderService {
    public record Draft(Long id, Long version, String displayName, String protocol, String baseUrl,
                        String modelId, String apiKey, boolean summaryEnabled, String testTicket, Integer contextWindowTokens) {
        public Draft(Long id, Long version, String displayName, String protocol, String baseUrl,
                     String modelId, String apiKey, boolean summaryEnabled, String testTicket) {
            this(id, version, displayName, protocol, baseUrl, modelId, apiKey, summaryEnabled, testTicket, null);
        }
        @Override public String toString() { return "ProviderDraft[id=" + id + "]"; }
    }
    public record View(long id, long version, String displayName, String protocol, String baseUrl, String modelId,
                       boolean summaryEnabled, boolean hasApiKey, String testStatus, Instant testedAt, boolean testSummary,
                       Integer contextWindowTokens) {}
    public record Listing(List<View> items, Long defaultId, long defaultVersion, String deploymentProtocol,
                          String deploymentModel, boolean deploymentAvailable, boolean encryptionReady, int deploymentContextWindowTokens) {}
    public record TestResult(boolean success, String message, long durationMs, boolean summaryAvailable, String ticket) {}
    public record ModelStatus(boolean configured, String displayName, String protocol, String modelId, String source) {}
    private record Stored(long id, long orgId, long version, String name, String protocol, String url, String model,
                          String ciphertext, boolean summary, String test, Instant testedAt, boolean testSummary,
                          Integer contextWindowTokens) {}
    private final JdbcTemplate db;
    private final ProviderSecrets secrets;
    private final ProviderUrlPolicy urls;
    private final AgentProperties props;
    private final Semaphore tests = new Semaphore(4);
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ProviderHttpClient http;
    public AgentProviderService(JdbcTemplate db, ProviderSecrets secrets, ProviderUrlPolicy urls, AgentProperties props) {
        this.db = db; this.secrets = secrets; this.urls = urls; this.props = props;
    }
    private long org() { Long id = OrgContext.get(); if (id == null) throw new BizException(403, "组织上下文不可用"); return id; }
    private void lock(long org) { db.queryForObject("select id from organization where id=? for update", Long.class, org); }
    private Stored read(long id, long org) {
        return db.query("select * from agent_provider_config where id=? and org_id=?", this::row, id, org).stream()
                .findFirst().orElseThrow(() -> new BizException(404, "AI 服务配置不存在"));
    }
    private Stored row(ResultSet r, int n) throws SQLException {
        Timestamp timestamp = r.getTimestamp("tested_at");
        return new Stored(r.getLong("id"), r.getLong("org_id"), r.getLong("version"), r.getString("display_name"),
                r.getString("protocol"), r.getString("base_url"), r.getString("model_id"), r.getString("api_key_ciphertext"),
                r.getBoolean("summary_enabled"), r.getString("test_status"), timestamp == null ? null : timestamp.toInstant(), r.getBoolean("test_summary"),
                r.getObject("context_window_tokens", Integer.class));
    }
    private View view(Stored s) { return new View(s.id, s.version, s.name, s.protocol, s.url, s.model, s.summary, true, s.test, s.testedAt, s.testSummary, s.contextWindowTokens); }
    private Map<String,Object> defaults(long org) {
        return db.queryForList("select config_id,version from agent_provider_default where org_id=?", org).stream()
                .findFirst().orElse(Map.of("version", 0L));
    }
    private Long defaultId(Map<String,Object> d) { return d.get("config_id") instanceof Number n ? n.longValue() : null; }
    private long defaultVersion(Map<String,Object> d) { return ((Number)d.get("version")).longValue(); }
    public Listing list() {
        long org = org(); var d = defaults(org);
        return new Listing(db.query("select * from agent_provider_config where org_id=? order by id", this::row, org).stream().map(this::view).toList(),
                defaultId(d), defaultVersion(d), props.getProtocol(), props.getModel(), !"stub".equals(props.getProvider()) && props.resolveApiKey() != null && props.getModel() != null && !props.getModel().isBlank(), secrets.available(), props.getContextWindowTokens());
    }
    /** Safe for organization members: configuration identity, never credentials or endpoint addresses. */
    public ModelStatus modelStatus() {
        var current = db.queryForList("select c.display_name,c.protocol,c.model_id from agent_provider_config c join agent_provider_default d on d.config_id=c.id and d.org_id=c.org_id where c.org_id=?", org());
        if (!current.isEmpty()) {
            var c = current.getFirst();
            return new ModelStatus(true, (String)c.get("display_name"), (String)c.get("protocol"), (String)c.get("model_id"), "organization");
        }
        boolean configured = !"stub".equals(props.getProvider()) && props.resolveApiKey() != null && props.getModel() != null && !props.getModel().isBlank();
        return new ModelStatus(configured, configured ? "部署模型" : "规则模式", configured ? props.getProtocol() : "stub", configured ? props.getModel() : "", configured ? "deployment" : "rules");
    }
    private String text(String input, int max, String field) {
        if (input == null || input.isBlank() || input.trim().length() > max || input.chars().anyMatch(c -> c < 32 || c == 127))
            throw new BizException(4400, field + "不能为空、超长或包含控制字符");
        return input.trim();
    }
    private ModelEndpoint endpoint(Draft d, Stored old) {
        String protocol = text(d.protocol, 40, "协议");
        if (!Set.of("openai_chat_completions", "openai_responses").contains(protocol)) throw new BizException(4400, "不支持的协议");
        String url = urls.validate(text(d.baseUrl, 2048, "Base URL"));
        String key = d.apiKey == null || d.apiKey.isBlank() ? old == null ? null : secrets.decrypt(old.ciphertext) : text(d.apiKey, 4096, "API Key");
        if (key == null) throw new BizException(4400, "新增服务必须填写 API Key");
        if (d.summaryEnabled && !"openai_responses".equals(protocol)) throw new BizException(4400, "推理摘要仅适用于 Responses");
        Integer window = d.contextWindowTokens;
        if (window != null && !Set.of(128_000, 262_000, 500_000, 1_000_000).contains(window))
            throw new BizException(4400, "上下文窗口仅支持 128k、262k、500k 或 1000k");
        if (window == null && old != null) window = old.contextWindowTokens;
        return new ModelEndpoint(protocol, url, key, text(d.modelId, 200, "请求模型 ID"), d.summaryEnabled, window);
    }
    public LlmProvider create(ModelEndpoint endpoint) {
        urls.validate(endpoint.baseUrl());
        if (http == null) return "openai_responses".equals(endpoint.protocol()) ? new OpenAiResponsesProvider(endpoint, props.getTimeoutMs())
                : new OpenAiChatCompletionsProvider(endpoint, props.getTimeoutMs());
        return "openai_responses".equals(endpoint.protocol()) ? new OpenAiResponsesProvider(endpoint, props.getTimeoutMs(), http.client())
                : new OpenAiChatCompletionsProvider(endpoint, props.getTimeoutMs(), http.client());
    }
    private String fingerprint(long org, Draft d, ModelEndpoint e) {
        try {
            String payload = org + "\n" + d.id + "\n" + d.version + "\n" + e.protocol() + "\n" + e.baseUrl() + "\n" + e.model() + "\n" + e.apiKey() + "\n" + e.summary();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("配置摘要失败"); }
    }
    private boolean validTicket(long org, Draft d, ModelEndpoint e) {
        if (d.testTicket == null || d.testTicket.isBlank()) return false;
        try {
            String[] ticket = secrets.decrypt(d.testTicket).split("\\|");
            return ticket.length == 4 && ticket[0].equals(fingerprint(org, d, e)) && Long.parseLong(ticket[1]) > System.currentTimeMillis();
        } catch (Exception ignored) { return false; }
    }
    private void audit(long org, Long id, String action) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        long uid = auth != null && auth.getPrincipal() instanceof LoginUser user ? user.getId() : 0L;
        db.update("insert into agent_provider_audit(org_id,user_id,config_id,action) values(?,?,?,?)", org, uid, id, action);
    }
    public TestResult test(Draft d) {
        long org = org(); if (!secrets.available()) throw new BizException(4400, "密钥存储初始化失败，请检查服务端持久化目录权限或恢复已有密钥文件");
        var old = d.id == null ? null : read(d.id, org);
        if (old != null && !Objects.equals(d.version, old.version)) throw new BizException(409, "配置已更新，请刷新后重试");
        text(d.displayName, 50, "显示名称"); var endpoint = endpoint(d, old);
        synchronized (tests) {
            long count = db.queryForObject("select count(*) from agent_provider_audit where org_id=? and action='TEST' and created_at>?", Long.class,
                    org, Timestamp.from(Instant.now().minusSeconds(60)));
            if (count >= 6 || !tests.tryAcquire()) throw new BizException(429, "测试过于频繁，请稍后重试");
            audit(org, d.id, "TEST");
        }
        long start = System.nanoTime();
        try {
            var provider = create(endpoint); var cancellation = new LlmCancellation();
            return cancellation.call(null, () -> false, props.getTimeoutMs() * 3L, () -> {
                var prompt = List.of(LlmProvider.ChatMessage.text("user", "Reply with OK."));
                var plain = provider.chat(new LlmProvider.ChatRequest(prompt, List.of(), 0, 1024));
                if (plain.content().isBlank()) throw new IllegalStateException("普通请求未返回正文");
                var tools = List.of(new LlmProvider.ToolSpec("connection_probe", "Connection test only. Call this tool with no arguments.",
                        Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)));
                var messages = new ArrayList<LlmProvider.ChatMessage>();
                messages.add(LlmProvider.ChatMessage.text("user", "You must call connection_probe once, then reply OK after receiving its result."));
                var first = provider.chatStream(new LlmProvider.ChatRequest(messages, tools, 0, 2048), piece -> {}, piece -> {});
                if (first.toolCalls().isEmpty() || first.toolCalls().stream().anyMatch(c -> !"connection_probe".equals(c.name()) || c.id().isBlank() || !c.arguments().isEmpty()))
                    throw new IllegalStateException("模型未通过工具调用验证");
                messages.add(LlmProvider.ChatMessage.assistantCalling(first));
                for (var call : first.toolCalls()) messages.add(LlmProvider.ChatMessage.toolResult(call.id(), "OK"));
                var second = provider.chatStream(new LlmProvider.ChatRequest(messages, tools, 0, 2048), piece -> {}, piece -> {});
                if (second.content().isBlank() || !second.toolCalls().isEmpty()) throw new IllegalStateException("模型未通过工具结果回传验证");
                boolean summary = first.reasoningContent() != null || second.reasoningContent() != null;
                String ticket = secrets.encrypt(fingerprint(org, d, endpoint) + "|" + (System.currentTimeMillis() + 1_800_000) + "|" + summary + "|" + System.currentTimeMillis());
                return new TestResult(true, "普通请求、SSE 及工具往返测试通过", (System.nanoTime() - start) / 1_000_000, summary, ticket);
            });
        } catch (Exception e) {
            if (old != null && old.protocol.equals(endpoint.protocol()) && old.url.equals(endpoint.baseUrl())
                    && old.model.equals(endpoint.model()) && old.summary == endpoint.summary() && secrets.decrypt(old.ciphertext).equals(endpoint.apiKey())) {
                db.update("update agent_provider_config set test_status='FAILED',tested_at=CURRENT_TIMESTAMP,test_summary=false where id=? and org_id=? and version=?", old.id, org, old.version);
            }
            // Never propagate upstream response bodies, URLs or credentials.
            return new TestResult(false, e instanceof IllegalStateException && e.getMessage() != null && e.getMessage().startsWith("模型服务返回 HTTP")
                    ? e.getMessage() : "连接测试失败：请核对地址、密钥、模型及所选协议的流式与工具能力", (System.nanoTime() - start) / 1_000_000, false, null);
        } finally { tests.release(); }
    }
    @Transactional
    public View save(Draft d) {
        long org = org(); lock(org); Stored old = d.id == null ? null : read(d.id, org);
        if (old != null && !Objects.equals(d.version, old.version)) throw new BizException(409, "配置已被修改，请刷新后重试");
        String name = text(d.displayName, 50, "显示名称"); ModelEndpoint e = endpoint(d, old);
        long duplicate = db.queryForObject("select count(*) from agent_provider_config where org_id=? and display_name=? and id<>?", Long.class, org, name, old == null ? -1 : old.id);
        if (duplicate > 0) throw new BizException(4400, "当前组织已有同名 AI 服务");
        boolean changed = old == null || !old.protocol.equals(e.protocol()) || !old.url.equals(e.baseUrl()) || !old.model.equals(e.model())
                || old.summary != e.summary() || d.apiKey != null && !d.apiKey.isBlank();
        boolean verified = validTicket(org, d, e);
        if (changed && old != null && Objects.equals(defaultId(defaults(org)), old.id) && !verified)
            throw new BizException(4400, "修改默认服务的连接信息前，请先测试当前配置");
        String state = verified ? "PASSED" : changed ? "UNTESTED" : old.test;
        Instant tested = verified ? Instant.ofEpochMilli(Long.parseLong(secrets.decrypt(d.testTicket).split("\\|")[3])) : changed ? null : old.testedAt;
        boolean summary = verified ? Boolean.parseBoolean(secrets.decrypt(d.testTicket).split("\\|")[2]) : !changed && old.testSummary;
        String cipher = old != null && (d.apiKey == null || d.apiKey.isBlank()) ? old.ciphertext : secrets.encrypt(e.apiKey());
        long id;
        if (old == null) {
            if (db.queryForObject("select count(*) from agent_provider_config where org_id=?", Long.class, org) >= 50) throw new BizException(4400, "每个组织最多保存 50 个 AI 服务");
            KeyHolder key = new GeneratedKeyHolder();
            db.update(connection -> {
                var statement = connection.prepareStatement("insert into agent_provider_config(org_id,display_name,protocol,base_url,model_id,api_key_ciphertext,summary_enabled,test_status,tested_at,test_summary,context_window_tokens) values(?,?,?,?,?,?,?,?,?,?,?)", new String[]{"id"});
                Object[] values = {org, name, e.protocol(), e.baseUrl(), e.model(), cipher, e.summary(), state, tested == null ? null : Timestamp.from(tested), summary, e.contextWindowTokens()};
                for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
                return statement;
            }, key); id = Objects.requireNonNull(key.getKey()).longValue();
        } else if (!changed && !verified) {
            // Local settings must not overwrite a newer connection-test result.
            id = old.id;
            db.update("update agent_provider_config set display_name=?,context_window_tokens=?,version=version+1,updated_at=CURRENT_TIMESTAMP where id=? and org_id=?", name, e.contextWindowTokens(), id, org);
        } else {
            id = old.id;
            db.update("update agent_provider_config set display_name=?,protocol=?,base_url=?,model_id=?,api_key_ciphertext=?,summary_enabled=?,test_status=?,tested_at=?,test_summary=?,context_window_tokens=?,version=version+1,updated_at=CURRENT_TIMESTAMP where id=? and org_id=?",
                    name,e.protocol(),e.baseUrl(),e.model(),cipher,e.summary(),state,tested == null ? null : Timestamp.from(tested),summary,e.contextWindowTokens(),id,org);
        }
        audit(org, id, old == null ? "CREATE" : "UPDATE"); return view(read(id, org));
    }
    @Transactional
    public void activate(Long id, long version, long expectedDefaultVersion) {
        long org = org(); lock(org); var current = defaults(org);
        if (defaultVersion(current) != expectedDefaultVersion) throw new BizException(409, "默认服务已变更，请刷新后重试");
        if (id != null) {
            var s = read(id, org);
            if (s.version != version) throw new BizException(409, "配置已变更，请刷新后重试");
            if (!"PASSED".equals(s.test) || s.testedAt == null || s.testedAt.isBefore(Instant.now().minusSeconds(1800))) throw new BizException(4400, "请先测试并保存当前配置（测试有效期 30 分钟）");
        }
        if (db.queryForObject("select count(*) from agent_provider_default where org_id=?", Long.class, org) == 0)
            db.update("insert into agent_provider_default(org_id,config_id,version) values(?,?,1)", org, id);
        else db.update("update agent_provider_default set config_id=?,version=version+1 where org_id=?", id, org);
        audit(org, id, id == null ? "RESET" : "ACTIVATE");
    }
    @Transactional
    public void delete(long id, long version) {
        long org = org(); lock(org); var s = read(id, org);
        if (s.version != version) throw new BizException(409, "配置已变更，请刷新后重试");
        if (Objects.equals(defaultId(defaults(org)), id)) throw new BizException(4400, "请先切换默认服务或恢复部署配置");
        db.update("delete from agent_provider_config where id=? and org_id=?", id, org); audit(org, id, "DELETE");
    }
    public LlmProvider resolve(long org, LlmProvider deployment) {
        return resolve(org, deployment, null);
    }
    public LlmProvider resolve(long org, LlmProvider deployment, String stream) {
        // One query gives an atomic configuration snapshot, even across concurrent activation or editing.
        var found = db.query("select c.* from agent_provider_config c join agent_provider_default d on d.config_id=c.id and d.org_id=c.org_id where c.org_id=?", this::row, org);
        if (found.isEmpty()) {
            if (stream != null) db.update("insert into agent_provider_task_usage(stream_id,org_id,protocol,model_id) values(?,?,?,?)",
                    stream, org, deployment.name(), Objects.toString(props.getModel(), ""));
            return deployment;
        }
        var s = found.getFirst();
        if (stream != null) db.update("insert into agent_provider_task_usage(stream_id,org_id,config_id,config_version,protocol,model_id) values(?,?,?,?,?,?)",
                stream, org, s.id, s.version, s.protocol, s.model);
        return create(new ModelEndpoint(s.protocol, s.url, secrets.decrypt(s.ciphertext), s.model, s.summary, s.contextWindowTokens));
    }
}
