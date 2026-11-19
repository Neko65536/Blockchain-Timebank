package org.example.timecoinweb.agent;
import tools.jackson.databind.ObjectMapper;
import org.example.timecoinweb.service.AiChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅连接固定本机专用测试库；业务样本事务回滚，模型为替身。 */
@EnabledIfSystemProperty(named="agent.mysql.integration",matches="true")
class AgentMysqlTests {
    @Test void migrationAndWhitelistedAggregatesWorkOnMysql() throws Exception {
        String password=System.getenv("AGENT_MYSQL_PASSWORD");assertNotNull(password,"显式提供本地测试MySQL密码");
        var ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3307/timebank_agent_test?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",password);
        var init=new ResourceDatabasePopulator(new ClassPathResource("schema-agent.sql"));init.execute(ds);init.execute(ds);
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("create table if not exists activity(id int primary key,title varchar(255))");
        jdbc.execute("create table if not exists activity_volunteer(activity_id int,create_time datetime)");
        jdbc.execute("create table if not exists chain_tx_log(tx_type varchar(16),amount bigint,create_time datetime)");
        var json=new ObjectMapper();var model=mock(AgentRuntime.class);var auth=mock(ReviewAgent.class);var audit=mock(PublishAgent.class);
        var agent=new AnalyticsAgent(model,auth,jdbc,audit,json);
        LocalDate end=LocalDate.now(),start=end.minusDays(2);
        new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(tx->{
            try {
                jdbc.update("insert into activity(id,title) values(-9001,'合成测试')");
                jdbc.update("insert into activity_volunteer(activity_id,create_time) values(-9001,?),(-9001,?)",start.atStartOfDay(),end.atStartOfDay());
                var registrationPlan=json.readTree("{\"tool\":\"activity_registration_top\",\"start\":\""+start+"\",\"end\":\""+end+"\"}");
                var result=agent.query(1,registrationPlan,"mysql-test");var rows=(List<Map<String,Object>>)result.get("rows");assertEquals("1",rows.get(0).get("value"));
                jdbc.update("insert into chain_tx_log(tx_type,amount,create_time) values('TRANSFER',17,?),('MINT',100,?),('TRANSFER',99,?)",start.atStartOfDay(),start.atStartOfDay(),end.atStartOfDay());
                var flowPlan=json.readTree("{\"tool\":\"coin_daily_flow\",\"start\":\""+start+"\",\"end\":\""+end+"\"}");
                rows=(List<Map<String,Object>>)agent.query(1,flowPlan,"mysql-test").get("rows");
                assertEquals("17",rows.get(0).get("value"));assertEquals("0",rows.get(1).get("value"));
                assertEquals(0,jdbc.queryForObject("select count(*) from agent_review",Integer.class));
            }catch(Exception e){throw new RuntimeException(e);}finally{tx.setRollbackOnly();}
            return null;
        });
        assertEquals(0,jdbc.queryForObject("select count(*) from activity where id=-9001",Integer.class));
    }
}
