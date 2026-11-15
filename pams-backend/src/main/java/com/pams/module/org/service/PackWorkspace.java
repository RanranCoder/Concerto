package com.pams.module.org.service;

import com.pams.common.BizException;
import com.pams.common.OrgContext;
import com.pams.common.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PackWorkspace {
    private final JdbcTemplate db;

    public PackWorkspace(JdbcTemplate db) { this.db = db; }

    public long org() { return OrgContext.getOrDefault(); }

    public void lockEnabled(String pack) {
        var rows = db.queryForList("SELECT enabled FROM org_domain_pack WHERE org_id=? AND pack_code=? FOR UPDATE",
                Integer.class, org(), pack);
        if (rows.isEmpty() || rows.getFirst() != 1) throw new BizException(403, "当前组织未启用此功能");
    }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return db.query(sql, (rs, index) -> row(rs), args);
    }

    private Map<String, Object> row(ResultSet rs) throws SQLException {
        var row = new LinkedHashMap<String, Object>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String[] words = meta.getColumnLabel(i).toLowerCase(Locale.ROOT).split("_");
            var name = new StringBuilder(words[0]);
            for (int j = 1; j < words.length; j++) {
                name.append(Character.toUpperCase(words[j].charAt(0))).append(words[j].substring(1));
            }
            Object value = rs.getObject(i);
            if (value instanceof java.sql.Timestamp time) value = time.toLocalDateTime();
            if (value instanceof java.sql.Date date) value = date.toLocalDate();
            row.put(name.toString(), value);
        }
        return row;
    }

    public Map<String, Object> one(String sql, String missing, Object... args) {
        var result = rows(sql, args);
        if (result.isEmpty()) throw new BizException(404, missing);
        return result.getFirst();
    }

    public PageResult<Map<String, Object>> page(String select, String where, int page, Object... args) {
        int current = Math.max(page, 1);
        int size = 20;
        var bindings = new ArrayList<>(List.of(args));
        var result = new PageResult<Map<String, Object>>();
        result.setTotal(db.queryForObject("SELECT COUNT(*) " + where, Long.class, args));
        result.setCurrent(current);
        result.setSize(size);
        bindings.add(size);
        bindings.add((long) (current - 1) * size);
        result.setRecords(rows("SELECT " + select + " " + where + " ORDER BY r.id DESC LIMIT ? OFFSET ?", bindings.toArray()));
        return result;
    }

    public List<Map<String, Object>> choices(String source, String keyword) {
        String table = switch (source) {
            case "members" -> "member";
            case "branches" -> "sys_department";
            case "activities" -> "activity";
            default -> throw new BizException(400, "不支持的查询类型");
        };
        String extra = switch (source) {
            case "members" -> ",student_no";
            case "activities" -> ",start_date,status";
            default -> "";
        };
        String active = "branches".equals(source) ? "" : " AND deleted=0";
        String query = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        if ("members".equals(source)) {
            return rows("SELECT id,name" + extra + " FROM " + table + " WHERE org_id=?" + active
                    + " AND (name LIKE ? OR student_no LIKE ?) ORDER BY id DESC LIMIT 50", org(), query, query);
        }
        return rows("SELECT id,name" + extra + " FROM " + table + " WHERE org_id=?" + active
                + " AND name LIKE ? ORDER BY id DESC LIMIT 50", org(), query);
    }

    public void requireBranch(long id) {
        one("SELECT id FROM sys_department WHERE org_id=? AND id=?", "组织节点不存在", org(), id);
    }

    public void requireActivity(long id) {
        one("SELECT id FROM activity WHERE org_id=? AND id=? AND deleted=0", "活动不存在", org(), id);
    }

    public static String text(String value, int limit, String label, boolean required) {
        String clean = value == null ? "" : value.trim();
        if (required && clean.isEmpty()) throw new BizException(400, "请填写" + label);
        if (clean.length() > limit) throw new BizException(400, label + "不能超过 " + limit + " 字");
        return clean.isEmpty() ? null : clean;
    }

    public static void changed(int count) {
        if (count != 1) throw new BizException(409, "记录状态已变化，请刷新后重试");
    }
}
