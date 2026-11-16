package org.example.timecoinweb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 路由目录与业务记录使用同一数据源、同一事务；UUID和幂等键不携带时间。 */
@Component
public class ShardRouting {
    private final JdbcTemplate jdbc;
    @Value("${app.sharding.enabled:false}") private boolean enabled;
    @Value("${app.sharding.start:2026-01}") private String start;
    @Value("${app.sharding.end:2027-01}") private String end;
    private Clock clock=Clock.systemDefaultZone();
    private static final DateTimeFormatter SQL_TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public ShardRouting(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Timestamp now() {
        LocalDateTime time=LocalDateTime.now(clock).withNano(0);
        if(enabled) validate(time);
        return Timestamp.valueOf(time);
    }
    public void register(String kind,String key,Timestamp time) {
        if(!enabled) return;
        validate(time.toLocalDateTime());
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("分片目录必须与业务记录同事务写入");
        jdbc.update("insert into shard_locator(kind,lookup_key,created_at) values(?,?,?)",kind,key,time);
    }
    public String at(String kind,String key) {
        if(!enabled) return "1=1";
        List<Timestamp> times=jdbc.queryForList("select created_at from shard_locator where kind=? and lookup_key=?",Timestamp.class,kind,key);
        if(times.isEmpty()) return "("+all("created_at")+") and 1=0";
        LocalDateTime time=times.get(0).toLocalDateTime();validate(time);
        return "created_at='"+SQL_TIME.format(time)+"'";
    }
    public String all(String column) {
        if(!enabled) return "1=1";
        if(!List.of("created_at","s.created_at","o.created_at").contains(column)) throw new IllegalArgumentException("非法时间列");
        return column+">='"+SQL_TIME.format(lower())+"' and "+column+"<'"+SQL_TIME.format(upper())+"'";
    }
    public LocalDateTime lower() { return YearMonth.parse(start).atDay(1).atStartOfDay(); }
    public String bindingCondition() { return enabled ? " and s.created_at=o.created_at " : " "; }
    public LocalDateTime upper() { return YearMonth.parse(end).atDay(1).atStartOfDay(); }
    private void validate(LocalDateTime time) {
        if(time.isBefore(lower()) || !time.isBefore(upper())) throw new IllegalStateException("记录月份超出已配置分片范围，请先预建并扩展分片，禁止另建业务重试");
    }
}
