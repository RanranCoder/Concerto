package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.module.activity.dto.SigninRequest;
import com.pams.module.activity.dto.SigninTokenDTO;
import com.pams.module.activity.entity.Signin;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.repository.SigninRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SigninService {
    private final SigninRepository repository;
    private final ActivityRepository activityRepository;
    private final SigninRosterService rosterService;
    private final Map<String, TokenEntry> tokenStore = new ConcurrentHashMap<>();

    @Autowired
    public SigninService(SigninRepository repository, ActivityRepository activityRepository,
                         SigninRosterService rosterService) {
        this.repository = repository;
        this.activityRepository = activityRepository;
        this.rosterService = rosterService;
    }

    public static class TokenEntry {
        private final Long activityId;
        private final LocalDateTime expiresAt;
        public TokenEntry(Long activityId, LocalDateTime expiresAt) {
            this.activityId = activityId;
            this.expiresAt = expiresAt;
        }
        public Long getActivityId() { return activityId; }
        public LocalDateTime getExpiresAt() { return expiresAt; }
    }

    /** 生成一次性签到令牌（24h 有效）；刷新即作废同活动旧令牌，保证“新码生效、旧码作废” */
    public SigninTokenDTO generateToken(Long activityId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        TokenEntry entry = new TokenEntry(activityId, LocalDateTime.now().plusHours(24));
        // 先作废同活动的旧令牌（含已过期的），避免刷新后旧码仍可签到
        tokenStore.entrySet().removeIf(e -> e.getValue().getActivityId().equals(activityId));
        tokenStore.put(token, entry);
        SigninTokenDTO dto = new SigninTokenDTO();
        dto.setToken(token);
        dto.setActivityId(activityId);
        dto.setExpiresAt(entry.getExpiresAt());
        return dto;
    }

    /** 测试辅助：将 token 置为已过期 */
    public void forceExpire(String token) {
        TokenEntry e = tokenStore.get(token);
        if (e != null) {
            tokenStore.put(token, new TokenEntry(e.getActivityId(), LocalDateTime.now().minusSeconds(1)));
        }
    }

    public Signin scanSignin(String token, String name, String studentNo) {
        return scanSignin(token, name, studentNo, null);
    }

    /**
     * 扫码签到（支持核验字段宽松匹配）：
     * 校验 token/活动 → 构造 Signin（fields 映射到列）→ 若活动有应签名单且匹配到，remark 追加"（应签名单）"→ save。
     * 不匹配仍签到（宽松策略），仅不加名单标记。
     */
    public Signin scanSignin(String token, String name, String studentNo, Map<String, String> fields) {
        Long activityId = validateAndResolveActivity(token);
        Signin s = new Signin();
        s.setActivityId(activityId);
        s.setName(name);
        s.setStudentNo(studentNo);
        if (fields != null) {
            s.setPhone(fields.get("手机号"));
            s.setClassName(fields.get("班级"));
            s.setIdentityType(fields.get("身份"));
        }
        s.setSignType(Signin.SignType.SCAN);
        s.setSignTime(LocalDateTime.now());
        s.setCreatedAt(LocalDateTime.now());
        if (rosterService != null && rosterService.isInRoster(activityId, fields)) {
            s.setRemark("（应签名单）");
        }
        return repository.save(s);
    }

    private Long validateAndResolveActivity(String token) {
        Long activityId = resolveActivityByToken(token);
        // 活动必须存在（用 ActivityRepository 校验真实活动，而非签到的自增 id）
        if (!activityRepository.existsById(activityId)) {
            tokenStore.remove(token);
            throw new BizException(2001, "活动不存在");
        }
        return activityId;
    }

    /** 校验 token 有效并解析活动 id（供公开接口读取核验字段配置，不校验活动存在，返回原始值） */
    public Long resolveActivityByToken(String token) {
        TokenEntry e = tokenStore.get(token);
        if (e == null) throw new BizException(2302, "签到码无效或已失效");
        if (e.getExpiresAt().isBefore(LocalDateTime.now())) {
            tokenStore.remove(token);
            throw new BizException(2303, "签到码已过期，请刷新");
        }
        return e.getActivityId();
    }

    public List<Signin> listByActivity(Long activityId, String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            return repository.findByActivityIdAndNameContaining(activityId, keyword.trim());
        }
        return repository.findByActivityId(activityId);
    }

    public long count(Long activityId) {
        return repository.countByActivityId(activityId);
    }

    @Transactional
    public Signin create(SigninRequest req) {
        Signin s = new Signin();
        s.setActivityId(req.getActivityId());
        s.setPersonId(req.getPersonId());
        s.setName(req.getName());
        s.setStudentNo(req.getStudentNo());
        s.setClassName(req.getClassName());
        s.setIdentityType(req.getIdentityType());
        s.setSignType(req.getSignType() == null ? Signin.SignType.MANUAL : req.getSignType());
        s.setSignTime(req.getSignTime());
        s.setLocation(req.getLocation());
        s.setPhone(req.getPhone());
        s.setRemark(req.getRemark());
        s.setCreatedAt(LocalDateTime.now());
        return repository.save(s);
    }

    @Transactional
    public void delete(Long id) {
        getEntity(id);
        repository.deleteById(id);
    }

    public Signin getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2301, "签到记录不存在"));
    }
}
