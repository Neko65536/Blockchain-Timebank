package org.example.timecoinweb.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.config.algorithm.AlgorithmConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Configuration
@Profile("sharding")
public class ShardingDataSourceConfig {
    @Bean(destroyMethod="close")
    public DataSource dataSource(@Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String user,@Value("${spring.datasource.password}") String password,
            @Value("${app.sharding.start}") String start,@Value("${app.sharding.end}") String end,
            @Value("${app.sharding.initialize:false}") boolean initialize) throws Exception {
        YearMonth first=YearMonth.parse(start), last=YearMonth.parse(end);
        long months=java.time.temporal.ChronoUnit.MONTHS.between(first,last);
        if(months<1 || months>120) throw new IllegalArgumentException("分片范围必须为1至120个月，end不包含在内");
        HikariDataSource physical=new HikariDataSource();
        physical.setJdbcUrl(url);physical.setUsername(user);physical.setPassword(password);physical.setMaximumPoolSize(10);
        try {
            JdbcTemplate jdbc=new JdbcTemplate(physical);
            if(initialize) {
                ResourceDatabasePopulator init=new ResourceDatabasePopulator();
                for(String file:List.of("schema-mysql.sql","schema-chain-submission.sql","schema-settlement.sql","schema-rag.sql","schema-agent.sql","schema-query-indexes-mysql.sql")) init.addScript(new ClassPathResource(file));
                init.execute(physical);
                jdbc.execute("create table if not exists shard_locator(kind varchar(24) not null,lookup_key varchar(100) not null,created_at datetime not null,primary key(kind,lookup_key))");
            }
            // 不自动搬迁或虚构历史凭据时间，避免切换后遗漏已付款记录。
            for(String table:List.of("settlement_order","settlement_outbox","chain_submission"))
                if(jdbc.queryForObject("select count(*) from "+table,Long.class)>0) throw new IllegalStateException("分片模式仅接受空逻辑模板表；已有数据须离线核验迁移，不能直接切换profile");
            Long outside=jdbc.queryForObject("select count(*) from shard_locator where created_at<? or created_at>=?",Long.class,
                    first.atDay(1).atStartOfDay(),last.atDay(1).atStartOfDay());
            if(outside>0) throw new IllegalStateException("配置范围遗漏路由目录中的历史月份");
            ShardingRuleConfiguration rule=new ShardingRuleConfiguration();
            for(String table:List.of("settlement_order","settlement_outbox","chain_submission")) {
                List<String> nodes=new ArrayList<>();
                for(YearMonth month=first;month.isBefore(last);month=month.plusMonths(1)) {
                    String name=table+"_"+month.format(DateTimeFormatter.ofPattern("yyyyMM"));
                    if(initialize) jdbc.execute("create table if not exists "+name+" like "+table);
                    if(initialize && "chain_submission".equals(table)
                            && jdbc.queryForObject("select count(*) from information_schema.COLUMNS where TABLE_SCHEMA=DATABASE() and TABLE_NAME=? and COLUMN_NAME='nonce_value' and IS_NULLABLE='NO'",Integer.class,name)>0)
                        jdbc.execute("alter table "+name+" modify column nonce_value decimal(20,0) null");
                    jdbc.queryForList("select created_at from "+name+" limit 0");
                    nodes.add("ds."+name);
                }
                ShardingTableRuleConfiguration tableRule=new ShardingTableRuleConfiguration(table,String.join(",",nodes));
                tableRule.setTableShardingStrategy(new StandardShardingStrategyConfiguration("created_at","monthly"));
                rule.getTables().add(tableRule);
            }
            rule.getBindingTableGroups().add(new org.apache.shardingsphere.sharding.api.config.rule.ShardingTableReferenceRuleConfiguration("settlements","settlement_order,settlement_outbox"));
            // 当前只有一个物理数据源；不宣称多库广播一致性或XA。
            rule.getBroadcastTables().addAll(List.of("user","activity"));
            Properties interval=new Properties();
            interval.setProperty("datetime-pattern","yyyy-MM-dd HH:mm:ss");
            interval.setProperty("datetime-lower",first.atDay(1)+" 00:00:00");
            interval.setProperty("datetime-upper",last.atDay(1).minusDays(1)+" 23:59:59");
            interval.setProperty("sharding-suffix-pattern","yyyyMM");
            interval.setProperty("datetime-interval-amount","1");interval.setProperty("datetime-interval-unit","MONTHS");
            rule.getShardingAlgorithms().put("monthly",new AlgorithmConfiguration("INTERVAL",interval));
            Properties props=new Properties();props.setProperty("sql-show","false");
            return ShardingSphereDataSourceFactory.createDataSource(Map.of("ds",physical),List.of(rule),props);
        } catch(Exception failure) { physical.close();throw failure; }
    }
}
