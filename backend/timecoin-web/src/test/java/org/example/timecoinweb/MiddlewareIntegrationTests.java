package org.example.timecoinweb;

import org.example.pojo.Notice;
import org.example.timecoinweb.config.SettlementMessaging;
import org.example.timecoinweb.interceptor.RedisRateLimitInterceptor;
import org.example.timecoinweb.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 必须使用独立测试中间件；不会清空Redis数据库或操作现有业务库。 */
@EnabledIfSystemProperty(named = "p1.integration", matches = "true")
@ActiveProfiles("dev")
@SpringBootTest(properties = {
        "spring.datasource.url=${test.datasource-url:jdbc:h2:mem:p1middleware;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.driver-class-name=${test.datasource-driver:org.h2.Driver}",
        "spring.datasource.username=${test.datasource-user:sa}", "spring.datasource.password=${test.datasource-password:}",
        "spring.sql.init.schema-locations=${test.schema:classpath:schema-h2.sql},classpath:schema-chain-submission.sql,classpath:schema-settlement.sql",
        "security.jwt-secret=test-only-p1-middleware-secret-32-bytes", "ai.chat.enabled=false",
        "settlement.mq-enabled=true", "settlement.dispatch-initial-ms=3600000", "settlement.dispatch-ms=3600000",
        "spring.rabbitmq.port=${test.rabbit-port:5673}", "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.redis.port=${test.redis-port:6380}", "app.redis-enabled=true", "spring.redis.password=",
        "settlement.retry-ms=0", "app.upload.image-dir=./target/test-images"
})
class MiddlewareIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired SettlementDispatcher dispatcher;
    @Autowired SettlementService settlements;
    @Autowired NoticeService notices;
    @MockBean TimeCoinChainService chain;

    @BeforeEach
    void prepare() throws Exception {
        listeners.stop();
        RabbitAdmin admin = new RabbitAdmin(rabbit);
        admin.purgeQueue(SettlementMessaging.QUEUE, false);
        admin.purgeQueue(SettlementMessaging.QUEUE + ".dead", false);
        jdbc.update("delete from settlement_outbox"); jdbc.update("delete from settlement_order");
        jdbc.update("delete from chain_tx_log"); jdbc.update("delete from chain_submission");
        jdbc.update("delete from `user` where id=991");
        jdbc.update("insert into `user`(id,username,password,role,coin_balance) values (991,'middleware','test',3,0)");
        when(chain.isChainReady()).thenReturn(true);
        when(chain.confirmMint(anyString(), any(), anyString(), anyString())).thenAnswer(call -> {
            String hash = Hash.sha3String(call.getArgument(3));
            jdbc.update("insert into chain_submission(request_key,scope_key,nonce_value,payload_hash,tx_hash,raw_tx,state) values(?,?,?,?,?,?,'CONFIRMED')",
                    hash, "middleware-test", 1, hash, hash, "mock-chain-receipt");
            return hash;
        });
    }
    @AfterEach void stop() { listeners.stop(); }

    @Test
    void realBrokerConfirmsDuplicateDeliveryAndCommitsOneLedgerEntry() throws Exception {
        String id = (String) settlements.submitTransfer(991, "broker-test-123", "ADMIN_MINT", null, "991", BigInteger.TEN).get("settlementId");
        assertEquals(0, jdbc.queryForObject("select send_count from settlement_outbox", Integer.class));
        dispatcher.dispatch(); dispatcher.publish(id);
        assertEquals(1, jdbc.queryForObject("select send_count from settlement_outbox", Integer.class));
        listeners.start();
        await(() -> "SUCCEEDED".equals(settlements.get(id, 991, false).get("state")));
        assertEquals(10L, jdbc.queryForObject("select coin_balance from `user` where id=991", Long.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from chain_tx_log", Integer.class));
        verify(chain, times(1)).confirmMint(anyString(), any(), anyString(), anyString());
    }

    @Test
    void returnedUnroutableMessageKeepsOutboxPending() {
        settlements.submitTransfer(991, "returned-test-123", "ADMIN_MINT", null, "991", BigInteger.ONE);
        RabbitAdmin admin = new RabbitAdmin(rabbit);
        admin.deleteQueue(SettlementMessaging.QUEUE);
        try {
            dispatcher.dispatch();
            assertEquals(0, jdbc.queryForObject("select send_count from settlement_outbox", Integer.class));
            assertNull(jdbc.queryForObject("select confirmed_at from settlement_outbox", java.sql.Timestamp.class));
            assertEquals("PENDING", jdbc.queryForObject("select state from settlement_order", String.class));
        } finally {
            // 不操作其他应用队列，只恢复本测试删除的队列和绑定。
            org.springframework.amqp.core.Queue work = org.springframework.amqp.core.QueueBuilder.durable(SettlementMessaging.QUEUE)
                    .singleActiveConsumer().deadLetterExchange(SettlementMessaging.EXCHANGE + ".dead").deadLetterRoutingKey("dead").build();
            admin.declareQueue(work);
            admin.declareBinding(new org.springframework.amqp.core.Binding(work.getName(), org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                    SettlementMessaging.EXCHANGE, SettlementMessaging.ROUTING_KEY, null));
        }
    }

    @Test
    void malformedMessageReachesRealDeadLetterQueue() throws Exception {
        rabbit.send(SettlementMessaging.EXCHANGE, SettlementMessaging.ROUTING_KEY, new Message("invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8), new MessageProperties()));
        listeners.start();
        final Message[] dead = new Message[1];
        await(() -> { dead[0] = rabbit.receive(SettlementMessaging.QUEUE + ".dead"); return dead[0] != null; });
        assertEquals("invalid", new String(dead[0].getBody(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void twoInstancesShareAtomicRedisRateLimitAndExpiry() throws Exception {
        RedisRateLimitInterceptor one = new RedisRateLimitInterceptor(redis);
        RedisRateLimitInterceptor two = new RedisRateLimitInterceptor(redis);
        for (RedisRateLimitInterceptor limiter : List.of(one, two)) {
            ReflectionTestUtils.setField(limiter, "enabled", true);
            ReflectionTestUtils.setField(limiter, "authLimit", 10);
        }
        String ip = "p1-test-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                RedisRateLimitInterceptor instance = i % 2 == 0 ? one : two;
                calls.add(pool.submit(() -> {
                    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/login"); req.setRemoteAddr(ip);
                    MockHttpServletResponse res = new MockHttpServletResponse();
                    instance.preHandle(req, res, new Object()); return res.getStatus();
                }));
            }
            int allowed = 0, limited = 0;
            for (Future<Integer> call : calls) { int status = call.get(5, TimeUnit.SECONDS); if (status == 200) allowed++; if (status == 429) limited++; }
            assertEquals(10, allowed); assertEquals(10, limited);
            String key = "timebank:rate:auth:ip:" + Hash.sha3String(ip);
            assertTrue(redis.getExpire(key) > 0 && redis.getExpire(key) <= 60);
            redis.delete(key);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void noticeVersionPreventsStaleRefillAndCachesEmptyResults() {
        jdbc.update("delete from notice");
        jdbc.update("update cache_revision set version_value=version_value+1000 where cache_name='notices'");
        notices.add(new Notice(null, "first", "one", null));
        long version = jdbc.queryForObject("select version_value from cache_revision where cache_name='notices'", Long.class);
        int id = notices.listRecent(1).get(0).getId();
        assertNotNull(redis.opsForValue().get("timebank:notices:v" + version));
        notices.update(new Notice(id, "updated", "two", null));
        redis.opsForValue().set("timebank:notices:v" + version, "[]", java.time.Duration.ofSeconds(5));
        assertEquals("updated", notices.listRecent(1).get(0).getTitle());
        notices.deleteById(id);
        assertTrue(notices.listRecent(100).isEmpty());
        long current = jdbc.queryForObject("select version_value from cache_revision where cache_name='notices'", Long.class);
        assertEquals("[]", redis.opsForValue().get("timebank:notices:v" + current));
        assertTrue(redis.getExpire("timebank:notices:v" + current) <= 5);
    }

    @Test
    void concurrentAcceptanceUsesOneDatabaseOrderAndOutboxRow() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) calls.add(pool.submit(() -> (String) settlements.submitTransfer(991,
                    "db-concurrency-123", "ADMIN_MINT", null, "991", BigInteger.ONE).get("settlementId")));
            Set<String> ids = new HashSet<>();
            for (Future<String> call : calls) ids.add(call.get(10, TimeUnit.SECONDS));
            assertEquals(1, ids.size());
            assertEquals(1, jdbc.queryForObject("select count(*) from settlement_outbox", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test
    @EnabledIfSystemProperty(named = "p1.broker-restart", matches = "true")
    void durableMessageSurvivesBrokerRestartWithoutOutboxResend() throws Exception {
        String id = (String) settlements.submitTransfer(991, "restart-broker-123", "ADMIN_MINT", null, "991", BigInteger.ONE).get("settlementId");
        dispatcher.dispatch();
        CountDownLatch connectionClosed = new CountDownLatch(1);
        rabbit.execute(channel -> {
            channel.getConnection().addShutdownListener(signal -> {
                if (!signal.isInitiatedByApplication()) connectionClosed.countDown();
            });
            return null;
        });
        // 外部测试协调者看到此标记后重启独立broker；测试不控制宿主机Docker。
        System.out.println("P1_BROKER_RESTART_READY");
        assertTrue(connectionClosed.await(45, TimeUnit.SECONDS), "需要在45秒内重启测试broker");
        listeners.start();
        await(() -> "SUCCEEDED".equals(settlements.get(id, 991, false).get("state")));
        assertEquals(1, jdbc.queryForObject("select send_count from settlement_outbox", Integer.class));
        assertEquals(1L, jdbc.queryForObject("select coin_balance from `user` where id=991", Long.class));
    }

    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) { if (condition.getAsBoolean()) return; Thread.sleep(100); }
        fail("中间件处理未在15秒内完成");
    }
}
