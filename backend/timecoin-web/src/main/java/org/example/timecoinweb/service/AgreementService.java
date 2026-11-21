package org.example.timecoinweb.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.web3j.crypto.Hash;

import java.util.*;

/** 用户协议版本留痕：注册事务内记录「版本号 + 时间戳 + 来源哈希」，不存协议正文与个人信息。 */
@Service
@RequiredArgsConstructor
public class AgreementService {
    private final JdbcTemplate jdbc;

    /** 当前生效版本；客户端必须提交匹配的版本号，防止静默接受旧协议。 */
    public Map<String, Object> current() {
        var rows = jdbc.query("select version,title,content_hash,effective_at from agreement_version "
                + "where active=1 order by effective_at desc,version desc limit 1", (rs, n) -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("version", rs.getString("version"));
            view.put("title", rs.getString("title"));
            view.put("contentHash", rs.getString("content_hash"));
            view.put("effectiveAt", String.valueOf(rs.getTimestamp("effective_at")));
            return view;
        });
        if (rows.isEmpty()) throw new IllegalStateException("未配置生效的用户协议版本");
        return rows.get(0);
    }

    /** 由注册事务调用；同一用户同一版本幂等，接受标志不可从 true 改 false。 */
    public void recordAcceptance(int userId, String version, boolean accepted, String source) {
        // 兼容未声明协议的历史注册调用：无版本号则不落同意记录。
        if (version == null || version.isBlank()) return;
        Map<String, Object> v = jdbc.queryForList("select version,content_hash as contentHash from agreement_version where version=? and active=1", version).stream().findFirst().orElse(null);
        if (v == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "协议版本不存在或已停用");
        String sourceHash = Hash.sha3String(version + ":" + (source == null ? "" : source));
        var existing = jdbc.queryForList("select accepted from user_agreement_accept where user_id=? and version=?", userId, version);
        if (!existing.isEmpty()) {
            if (((Number) existing.get(0).get("accepted")).intValue() != (accepted ? 1 : 0))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该协议版本的同意记录不可变更");
            return;
        }
        jdbc.update("insert into user_agreement_accept(id,user_id,version,accepted,source_hash) values(?,?,?,?,?)",
                UUID.randomUUID().toString(), userId, version, accepted ? 1 : 0, sourceHash);
    }

    /** 用户同意历史：仅本人可见，服务端接口用。 */
    public List<Map<String, Object>> history(int userId) {
        return jdbc.query("select version,accepted,accepted_at,source_hash from user_agreement_accept "
                + "where user_id=? order by accepted_at desc,version desc", (rs, n) -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("version", rs.getString("version"));
            view.put("accepted", rs.getInt("accepted"));
            view.put("acceptedAt", String.valueOf(rs.getTimestamp("accepted_at")));
            view.put("sourceHash", rs.getString("source_hash"));
            return view;
        }, userId);
    }

    public boolean hasAccepted(int userId, String version) {
        Integer n = jdbc.queryForObject("select count(*) from user_agreement_accept where user_id=? and version=? and accepted=1", Integer.class, userId, version);
        return n != null && n > 0;
    }
}
