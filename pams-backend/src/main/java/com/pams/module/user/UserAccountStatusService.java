package com.pams.module.user;

import com.pams.common.BizException;
import com.pams.common.OrgContext;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.module.user.dto.UserStatusRequest;
import com.pams.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;

/** Each target has an independent transaction and live authorization check. Never rewrites its profile or role. */
@Service
public class UserAccountStatusService {
    private final UserRepository users;
    private final TenantAuthorizationService authorization;
    private final TransactionTemplate transaction;

    public UserAccountStatusService(UserRepository users, TenantAuthorizationService authorization, PlatformTransactionManager manager) {
        this.users = users;
        this.authorization = authorization;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<Map<String, Object>> apply(UserStatusRequest request) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(request.userIds())) {
            try {
                String outcome = transaction.execute(tx -> {
                    authorization.verifyUserMutation(id);
                    var user = users.findById(id).orElseThrow(() -> new BizException(404, "用户不存在"));
                    if (!Objects.equals(user.getOrgId(), OrgContext.get())) throw new BizException(404, "用户不存在");
                    if (authorization.pendingAccountSetup(id)) throw new BizException(409, "待设密账号需先完成密码设置");
                    if (Objects.equals(user.getStatus(), request.status())) return "UNCHANGED";
                    user.setStatus(request.status());
                    user.setUpdatedAt(LocalDateTime.now());
                    users.save(user);
                    authorization.userChanged(user.getOrgId());
                    return "UPDATED";
                });
                results.add(Map.of("id", id, "status", outcome, "message", outcome.equals("UPDATED") ? "状态已更新" : "状态无需修改"));
            } catch (BizException ex) {
                results.add(Map.of("id", id, "status", "FAILED", "message", ex.getMessage()));
            }
        }
        return results;
    }
}
