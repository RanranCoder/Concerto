package com.pams.module.agent.provider;

import com.pams.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

@Component
@DependsOnDatabaseInitialization
public class ProviderSecrets {
    private final String configured;
    private final Path keyFile;
    private final JdbcTemplate db;
    private volatile String master;
    private boolean warned;
    /** Explicit in-memory keys for isolated use; application initialization uses the database-bound constructor. */
    public ProviderSecrets(String master) { this.configured = master; this.keyFile = null; this.db = null; }
    @Autowired
    public ProviderSecrets(@Value("${pams.agent.provider-encryption-key:}") String configured,
                           @Value("${pams.agent.provider-key-file:./data/secrets/ai-provider.key}") String file,
                           JdbcTemplate db) {
        this.configured = configured; this.keyFile = Path.of(file).toAbsolutePath().normalize(); this.db = db;
    }
    private boolean valid(String value) { try { return value != null && Base64.getDecoder().decode(value).length == 32; } catch (Exception e) { return false; } }
    public synchronized boolean available() {
        if (master != null) return true;
        try {
            String selected;
            if (configured != null && !configured.isBlank()) {
                selected = configured.trim();
                if (!valid(selected)) throw new IllegalStateException("配置的加密密钥格式无效");
                if (db != null) bind(selected);
            } else if (db != null) {
                // A JVM monitor plus OS lock protects both same-process and shared-volume initializers.
                synchronized (ProviderSecrets.class) { selected = loadOrCreate(); }
            } else return false;
            master = selected; warned = false; return true;
        } catch (Exception e) {
            if (!warned) {
                org.slf4j.LoggerFactory.getLogger(ProviderSecrets.class).warn("AI 密钥存储初始化未完成，请检查持久化目录权限、已有密钥文件与数据库的对应关系。不会重置现存密钥。");
                warned = true;
            }
            return false;
        }
    }
    private String loadOrCreate() throws Exception {
        Path parent = keyFile.getParent(); boolean created = !Files.exists(parent); Files.createDirectories(parent);
        var posix = Files.getFileAttributeView(parent, PosixFileAttributeView.class);
        if (created && posix != null) Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        Path lock = parent.resolve(keyFile.getFileName() + ".lock");
        try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE); var held = channel.lock()) {
            String selected;
            if (Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(keyFile, LinkOption.NOFOLLOW_LINKS) || Files.size(keyFile) > 256) throw new IllegalStateException("密钥文件无效");
                selected = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
                if (!valid(selected)) throw new IllegalStateException("密钥文件损坏");
            } else {
                if (!db.queryForList("select fingerprint from agent_provider_key_state where id=1").isEmpty()
                        || db.queryForObject("select count(*) from agent_provider_config", Long.class) > 0)
                    throw new IllegalStateException("已有密文需要恢复原始密钥，不能重新生成");
                byte[] generated = new byte[32]; new SecureRandom().nextBytes(generated);
                selected = Base64.getEncoder().encodeToString(generated);
                Path temporary = posix == null ? Files.createTempFile(parent, ".ai-key-", ".tmp")
                        : Files.createTempFile(parent, ".ai-key-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                try {
                    var acl = Files.getFileAttributeView(temporary, AclFileAttributeView.class);
                    if (acl != null) acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                            .setPrincipal(Files.getOwner(temporary)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
                    try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        ByteBuffer bytes = StandardCharsets.UTF_8.encode(selected);
                        while (bytes.hasRemaining()) output.write(bytes);
                        output.force(true);
                    }
                    Files.move(temporary, keyFile, StandardCopyOption.ATOMIC_MOVE);
                } finally { Files.deleteIfExists(temporary); }
            }
            bind(selected); return selected;
        }
    }
    private void bind(String selected) throws Exception {
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(selected)));
        var state = db.queryForList("select fingerprint from agent_provider_key_state where id=1", String.class);
        if (state.isEmpty()) {
            // Adopt an existing explicit key only after proving it decrypts existing ciphertext.
            var existing = db.queryForList("select api_key_ciphertext from agent_provider_config", String.class);
            for (String ciphertext : existing) decryptWith(ciphertext, selected);
            try { db.update("insert into agent_provider_key_state(id,fingerprint) values(1,?)", fingerprint); }
            catch (org.springframework.dao.DuplicateKeyException concurrentInitializer) { /* Read the winner below. */ }
            state = db.queryForList("select fingerprint from agent_provider_key_state where id=1", String.class);
        }
        if (state.isEmpty() || !MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.UTF_8), state.getFirst().getBytes(StandardCharsets.UTF_8)))
            throw new IllegalStateException("密钥与数据库不匹配");
    }
    private String decryptWith(String value, String selected) throws Exception {
        var parts = value.split("\\."); var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(selected), "AES"), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
        return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
    }
    private SecretKeySpec key() {
        if (!available()) throw new BizException(4400, "密钥存储初始化失败，请检查服务端持久化目录权限或恢复已有密钥文件");
        return new SecretKeySpec(Base64.getDecoder().decode(master), "AES");
    }
    public String encrypt(String value) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding"); byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] data = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(data);
        } catch (BizException e) { throw e; } catch (Exception e) { throw new IllegalStateException("密钥加密失败"); }
    }
    public String decrypt(String value) {
        try {
            var parts = value.split("\\."); var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (BizException e) { throw e; } catch (Exception e) { throw new BizException(4400, "AI 密钥无法解密，请检查服务端加密配置"); }
    }
}
