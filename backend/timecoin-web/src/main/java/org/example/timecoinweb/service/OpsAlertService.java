package org.example.timecoinweb.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** N6：运维告警落表。卡单巡检与对账不一致统一入口，支持幂等与恢复。 */
@Service
@RequiredArgsConstructor
public class OpsAlertService {
    private final JdbcTemplate jdbc;

    /** 同一 (kind,ref) 未恢复时不重复告警；恢复过可重新告警。 */
    public void raise(String kind, String ref, String severity, String detail) {
        Integer open = jdbc.queryForObject(
                "select count(*) from ops_alert where kind=? and ref=? and resolved=0", Integer.class, kind, ref);
        if (open != null && open > 0) return;
        // 已恢复的同键记录先清理，再插入新的未恢复告警（跨 H2/MySQL 兼容，避免 MERGE 方言差异）
        jdbc.update("delete from ops_alert where kind=? and ref=? and resolved=1", kind, ref);
        jdbc.update("insert into ops_alert(id,kind,ref,severity,detail,resolved,created_at) "
                        + "values(?,?,?,?,?,0,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), kind, ref, severity, detail);
    }

    public void resolve(String kind, String ref) {
        jdbc.update("update ops_alert set resolved=1,resolved_at=CURRENT_TIMESTAMP where kind=? and ref=? and resolved=0",
                kind, ref);
    }

    public List<java.util.Map<String, Object>> openAlerts(int limit) {
        return jdbc.queryForList("select kind,ref,severity,detail,created_at from ops_alert "
                + "where resolved=0 order by created_at desc limit ?", limit);
    }
}
