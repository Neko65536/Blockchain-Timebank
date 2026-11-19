package org.example.timecoinweb;

import org.example.timecoinweb.config.ShardRouting;
import org.example.timecoinweb.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.*;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 独立MySQL物理表验证，不以H2模拟ShardingSphere路由。 */
@EnabledIfSystemProperty(named="sharding.integration",matches="true")
@ActiveProfiles("sharding")
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:mysql://localhost:3307/timebank_sharding_test?allowPublicKeyRetrieval=true",
    "spring.datasource.username=root","spring.datasource.password=p1-local-test-only",
    "app.sharding.initialize=true","app.sharding.start=2026-01","app.sharding.end=2027-01",
    "settlement.mq-enabled=true","settlement.dispatch-initial-ms=3600000","settlement.dispatch-ms=3600000",
    "spring.rabbitmq.port=5673","spring.rabbitmq.listener.simple.auto-startup=false","ai.rag.mq-enabled=false",
    "app.redis-enabled=false","ai.chat.enabled=false","blockchain.enabled=false",
    "security.jwt-secret=test-only-sharding-secret-32-bytes-long","settlement.retry-ms=0"
})
class ShardingIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired ShardRouting routing;
    @Autowired SettlementService settlements;
    @Autowired ChainTransactionService submissions;
    @Autowired PlatformTransactionManager manager;
    @Autowired SettlementDispatcher dispatcher;
    @Autowired org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;
    @Autowired org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry listeners;
    @MockitoBean TimeCoinChainService chain;
    @MockitoBean(answers=org.mockito.Answers.RETURNS_DEEP_STUBS) Web3j web3j;
    JdbcTemplate physical;
    @TestConfiguration static class Signing { @Bean Credentials credentials() {return Credentials.create("1");} }
    @BeforeEach void setup() {
        listeners.stop();
        new org.springframework.amqp.rabbit.core.RabbitAdmin(rabbit).purgeQueue(org.example.timecoinweb.config.SettlementMessaging.QUEUE,false);
        physical=new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://localhost:3307/timebank_sharding_test?allowPublicKeyRetrieval=true","root","p1-local-test-only"));
        for(int month=1;month<=12;month++) for(String table:List.of("settlement_outbox","settlement_order","chain_submission"))
            physical.update("delete from "+table+"_2026"+String.format("%02d",month));
        physical.update("delete from shard_locator");physical.update("delete from chain_signer");physical.update("delete from chain_tx_log");
        physical.update("delete from `user` where id=991");physical.update("insert into `user`(id,username,password,role,coin_balance) values(991,'sharding-test','test',3,0)");
        when(chain.isChainReady()).thenReturn(true);month("2026-01-31T23:59:59");
    }
    @AfterEach void stop() {listeners.stop();}
    void month(String time) {ReflectionTestUtils.setField(routing,"clock",Clock.fixed(LocalDateTime.parse(time).atZone(ZoneId.systemDefault()).toInstant(),ZoneId.systemDefault()));}
    String submit(String key) {return (String)settlements.submitTransfer(991,key,"ADMIN_MINT",null,"991",BigInteger.TEN).get("settlementId");}
    @Test void monthBoundaryKeepsUuidIdempotencyAndBindingJoin() {
        String jan=submit("month-boundary-1");month("2026-02-01T00:00:00");
        assertEquals(jan,submit("month-boundary-1"));String feb=submit("month-boundary-2");assertNotEquals(jan,feb);
        assertEquals(1,physical.queryForObject("select count(*) from settlement_order_202601",Integer.class));
        assertEquals(1,physical.queryForObject("select count(*) from settlement_order_202602",Integer.class));
        assertEquals(2,jdbc.queryForList("select o.order_id from settlement_outbox o join settlement_order s on s.id=o.order_id and s.created_at=o.created_at where o.created_at>='2026-01-01 00:00:00' and o.created_at<'2026-03-01 00:00:00' and s.created_at>='2026-01-01 00:00:00' and s.created_at<'2026-03-01 00:00:00'").size());
        assertEquals(2,settlements.list(991,false).size());assertEquals(jan,settlements.get(jan,991,false).get("settlementId"));
    }
    @Test void transactionRollbackRemovesDirectoryAndBothPhysicalRows() {
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(status->{submit("rollback-order-1");throw new IllegalStateException("rollback");}));
        assertEquals(0,physical.queryForObject("select count(*) from shard_locator",Integer.class));
        assertEquals(0,physical.queryForObject("select count(*) from settlement_order_202601",Integer.class));
        assertEquals(0,physical.queryForObject("select count(*) from settlement_outbox_202601",Integer.class));
    }
    @Test void oldMonthConsumerCommitsOnlyOneLedgerEntry() throws Exception {
        String id=submit("old-month-order-1");month("2026-02-01T00:00:00");
        when(chain.confirmMint(anyString(),any(),anyString(),anyString())).thenAnswer(call->{
            String hash=Hash.sha3String("receipt:"+id);
            new TransactionTemplate(manager).executeWithoutResult(status->{
                java.sql.Timestamp created=routing.now();routing.register("chain-hash",hash,created);routing.register("chain-request",hash,created);
                jdbc.update("insert into chain_submission(request_key,scope_key,nonce_value,payload_hash,tx_hash,raw_tx,state,created_at) values(?,?,?,?,?,?,'CONFIRMED',?)",hash,"test",0,hash,hash,"test",created);
            });return hash;
        });
        dispatcher.dispatch();assertFalse(listeners.getListenerContainers().isEmpty()); listeners.getListenerContainers().forEach(org.springframework.amqp.rabbit.listener.MessageListenerContainer::start);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while(!"SUCCEEDED".equals(settlements.get(id,991,false).get("state")) && System.nanoTime()<deadline) Thread.sleep(100);
        settlements.process(id);
        assertEquals("SUCCEEDED",settlements.get(id,991,false).get("state"));
        assertEquals(10L,physical.queryForObject("select coin_balance from `user` where id=991",Long.class));
        assertEquals(1,physical.queryForObject("select ledger_applied from chain_submission_202602",Integer.class));
        assertEquals(1,physical.queryForObject("select count(*) from chain_tx_log",Integer.class));
        assertTrue(physical.queryForObject("select send_count from settlement_outbox_202601",Integer.class)>0);
    }
    @Test void signedTransactionAndNonceSurviveMonthChange() throws Exception {
        EthChainId chainId=new EthChainId();chainId.setResult("0x7a69");when(web3j.ethChainId().send()).thenReturn(chainId);
        EthGetTransactionCount nonce=new EthGetTransactionCount();nonce.setResult("0x0");when(web3j.ethGetTransactionCount(anyString(),eq(DefaultBlockParameterName.PENDING)).send()).thenReturn(nonce);
        when(web3j.ethGetTransactionReceipt(anyString())).thenAnswer(call->{
            org.web3j.protocol.core.Request request=mock(org.web3j.protocol.core.Request.class);
            TransactionReceipt receipt=new TransactionReceipt();receipt.setTransactionHash(call.getArgument(0));receipt.setBlockNumber("0x1");receipt.setStatus("0x1");
            EthGetTransactionReceipt response=new EthGetTransactionReceipt();response.setResult(receipt);when(request.send()).thenReturn(response);return request;
        });
        String first=submissions.send("0x","same-business");month("2026-02-01T00:00:00");
        assertEquals(first,submissions.send("0x","same-business"));assertEquals(first,submissions.resume(first));
        assertNotEquals(first,submissions.send("0x","another-business"));
        assertEquals(0L,physical.queryForObject("select nonce_value from chain_submission_202601",Long.class));
        assertEquals(1L,physical.queryForObject("select nonce_value from chain_submission_202602",Long.class));
    }
    @Test void outOfRangeMonthRejectsWithoutCreatingPartialOrder() {
        month("2027-01-01T00:00:00");assertThrows(IllegalStateException.class,()->submit("outside-range-1"));
        assertEquals(0,physical.queryForObject("select count(*) from shard_locator",Integer.class));
    }
    @Test void restartRejectsShrinkingHistoryOrUnmigratedLogicalRows() {
        submit("history-guard-1");
        org.example.timecoinweb.config.ShardingDataSourceConfig config=new org.example.timecoinweb.config.ShardingDataSourceConfig();
        String url="jdbc:mysql://localhost:3307/timebank_sharding_test?allowPublicKeyRetrieval=true";
        assertThrows(IllegalStateException.class,()->config.dataSource(url,"root","p1-local-test-only","2026-02","2027-01",false));
        physical.update("insert into settlement_order(id,request_key,payload_hash,owner_id,biz_type,to_user,amount,state) values('legacy','legacy','legacy',991,'ADMIN_MINT','991',1,'PENDING')");
        try {assertThrows(IllegalStateException.class,()->config.dataSource(url,"root","p1-local-test-only","2026-01","2027-01",false));}
        finally {physical.update("delete from settlement_order where id='legacy'");}
    }
}
