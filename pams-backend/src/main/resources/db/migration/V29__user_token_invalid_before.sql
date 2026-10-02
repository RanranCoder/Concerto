-- ===================== JWT 无吊销机制修复：用户级失效水位（2026-09 审计 #3） =====================
-- 背景：JWT 自包含、无服务端状态，签发后在有效期内无法作废——受害者「退出登录」只清前端
--   存储/Cookie，攻击者持有的旧 token（同机窃取/XSS/钓鱼）仍可 Bearer 调用全部 API 直至过期。
-- 方案（最小可靠，不引入 Redis/黑名单表）：每用户一列失效水位 token_invalid_before。
--   登出/改密时把水位推进到当前时刻；JwtAuthenticationFilter 每请求比对 token 的 iat（秒），
--   早于水位一律拒绝（401）。不改 JWT 载荷（uid/role 沿用）。
-- 粒度约定：DATETIME 秒精度（MySQL DATETIME(0) 截断小数秒），代码侧写入时向上取整到下一整秒
--   （JwtUtil.revocationWatermark），保证「登出同秒内签发的旧 token」也被作废，且 MySQL/H2 行为一致。
ALTER TABLE sys_user ADD COLUMN token_invalid_before DATETIME NULL COMMENT 'token失效水位：iat 早于该时刻的 JWT 一律401（登出/改密时推进）';
