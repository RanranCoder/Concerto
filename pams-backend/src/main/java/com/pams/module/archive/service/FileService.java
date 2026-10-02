package com.pams.module.archive.service;

import com.pams.common.BizException;
import com.pams.entity.User;
import com.pams.module.archive.entity.FileRecord;
import com.pams.module.archive.repository.FileRecordRepository;
import com.pams.repository.UserRepository;
import com.pams.security.LoginUser;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * 文件读取的对象级/部门级鉴权（审计 #2 文件下载 IDOR 修复）。
 *
 * <p>下载/预览/导出在前端统一走 GET /api/files/{id}/download 一个通道
 * （pams-web src/api/file.ts、src/components/file/httpBlob.ts），本类是该通道唯一的可见性闸门。
 *
 * <p>可见性口径与材料列表的部门过滤一致（MaterialService.page 的 deptId 过滤），
 * 并落实红线「名单、函调、党员信息需部长以上」：
 * <ul>
 *   <li>部长及以上（role.level &gt;= 3，与 {@code RoleExprs.LEADER} 同口径；不手抄角色名，
 *       待审计 #19 统一后并轨）：全部可见</li>
 *   <li>其余用户：仅上传者本人、或与上传者同部门的文件可见</li>
 *   <li>敏感 bizType（签到表/应签名单/排班名单/考勤/发票）：额外要求部长及以上，
 *       本人上传也不例外——名单类原件含姓名学号等个人信息，按红线不得经干事外传</li>
 * </ul>
 *
 * <p>越权与文件不存在返回完全相同的响应（4001 文件不存在），避免自增 fileId 被枚举探测出
 * 「哪些 id 有文件」的存在性旁路。
 */
@Service
public class FileService {

    /** 敏感 bizType（红线件）：签到表、应签名单/排班名单、考勤、发票；函调/党员材料归入 ROSTER 类 */
    private static final Set<String> SENSITIVE_BIZ_TYPES = Set.of(
            "SIGNIN", "SCHEDULE", "ROSTER", "ATTENDANCE", "INVOICE");

    /** 「部长及以上」级别阈值：种子角色中四部部长 level=3（见 DataSeeder.seedBaseDict） */
    private static final int LEADER_LEVEL = 3;

    private final FileRecordRepository fileRecordRepository;
    private final UserRepository userRepository;

    public FileService(FileRecordRepository fileRecordRepository, UserRepository userRepository) {
        this.fileRecordRepository = fileRecordRepository;
        this.userRepository = userRepository;
    }

    /**
     * 取出当前用户有权读取的文件记录；不存在或越权一律抛 4001「文件不存在」（同响应防枚举）。
     */
    public FileRecord getReadableRecord(Long id, LoginUser current) {
        FileRecord rec = fileRecordRepository.findById(id).orElseThrow(FileService::notFound);
        if (!canRead(rec, current)) {
            throw notFound();
        }
        return rec;
    }

    private boolean canRead(FileRecord rec, LoginUser current) {
        if (current == null) {
            return false;
        }
        if (isLeader(current)) {
            return true;
        }
        // 敏感件对非部长用户一律关闭（含本人上传）
        if (isSensitive(rec)) {
            return false;
        }
        if (rec.getUploaderId() != null && rec.getUploaderId().equals(current.getId())) {
            return true;
        }
        Long ownerDeptId = uploaderDeptId(rec);
        return ownerDeptId != null && ownerDeptId.equals(current.getDeptId());
    }

    /** 部长及以上：按角色 level 判定（RoleExprs.LEADER 的级别语义），不落角色名字面量 */
    private boolean isLeader(LoginUser current) {
        return current.getRoleLevel() != null && current.getRoleLevel() >= LEADER_LEVEL;
    }

    private boolean isSensitive(FileRecord rec) {
        String bizType = rec.getBizType();
        return bizType != null
                && SENSITIVE_BIZ_TYPES.contains(bizType.toUpperCase(Locale.ROOT));
    }

    /** 文件归属部门 = 上传者部门（file_record 无 dept_id，与材料列表 deptId 过滤的归属口径对齐） */
    private Long uploaderDeptId(FileRecord rec) {
        if (rec.getUploaderId() == null) {
            return null;
        }
        return userRepository.findById(rec.getUploaderId())
                .map(User::getDept)
                .map(d -> d.getId())
                .orElse(null);
    }

    private static BizException notFound() {
        return new BizException(4001, "文件不存在");
    }
}
