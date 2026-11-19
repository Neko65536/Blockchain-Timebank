package org.example.timecoinweb.rag;

import tools.jackson.databind.ObjectMapper;
import org.example.timecoinweb.service.AiChatService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagTests {
    JdbcTemplate jdbc; KnowledgeService knowledge; EmbeddingService embedding; ObjectMapper json=new ObjectMapper();
    InMemoryVectorIndex index; AiChatService model; RagAnswerService answers;
    @BeforeEach void setup() {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:rag"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema-rag.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);embedding=spy(new EmbeddingService(json));
        ReflectionTestUtils.setField(embedding,"mode","local-hash");ReflectionTestUtils.setField(embedding,"url","");
        ReflectionTestUtils.setField(embedding,"model","test");ReflectionTestUtils.setField(embedding,"dimensions",512);
        knowledge=new KnowledgeService(jdbc,new DataSourceTransactionManager(ds),embedding,json);
        index=new InMemoryVectorIndex(jdbc,json);model=mock(AiChatService.class);when(model.groundingKey()).thenReturn("test");
        answers=new RagAnswerService(knowledge,embedding,index,model,json,mock(StringRedisTemplate.class));
        ReflectionTestUtils.setField(answers,"mode","extractive");ReflectionTestUtils.setField(answers,"minimum",0.2);
    }
    String submit(int role) { return knowledge.submit(UUID.randomUUID().toString(),"活动报名", "志愿者请进入活动列表选择可报名活动，阅读活动说明后点击报名按钮。",role,"FAQ"); }
    String state(String id) { return jdbc.queryForObject("select state from rag_document where id=?",String.class,id); }
    @Test void outboxIdempotencyAndPayloadConflict() {
        String id=knowledge.submit("same","标题","完整知识正文用于测试",0,"TEXT");
        assertEquals(id,knowledge.submit("same","标题","完整知识正文用于测试",0,"TEXT"));
        assertThrows(ResponseStatusException.class,()->knowledge.submit("same","标题","不同内容",0,"TEXT"));
        assertEquals(1,jdbc.queryForObject("select count(*) from rag_outbox",Integer.class));assertEquals("PENDING",state(id));
    }
    @Test void repeatedConsumerAndRoleIsolation() {
        String id=submit(2);knowledge.process(id);knowledge.process(id);
        assertEquals("READY",state(id));assertEquals(1,knowledge.version());
        assertEquals("NO_EVIDENCE",answers.answer(1,"活动报名").get("mode"));
        Map<String,Object> result=answers.answer(2,"活动报名");assertEquals("EXTRACTIVE",result.get("mode"));assertEquals(1,((List<?>)result.get("citations")).size());
    }
    @Test void disableInvalidatesSnapshotAndCannotRevive() {
        String id=submit(0);knowledge.process(id);assertEquals("EXTRACTIVE",answers.answer(1,"活动报名").get("mode"));
        knowledge.disable(id);knowledge.process(id);assertEquals("DELETED",state(id));assertEquals("NO_EVIDENCE",answers.answer(1,"活动报名").get("mode"));
    }
    @Test void disableDuringEmbeddingFencesConsumer() {
        String id=submit(0);
        doAnswer(call->{knowledge.disable(id);return call.callRealMethod();}).when(embedding).embed(anyList());
        knowledge.process(id);assertEquals("DELETED",state(id));assertEquals(0,jdbc.queryForObject("select count(*) from rag_chunk",Integer.class));
    }
    @Test void failureHasNoPartialIndexAndCanRetry() {
        String id=submit(0);doThrow(new IllegalStateException("private upstream response")).when(embedding).embed(anyList());
        for(int i=0;i<5;i++) { jdbc.update("update rag_document set next_attempt=CURRENT_TIMESTAMP where id=?",id);knowledge.process(id); }
        assertEquals("REVIEW",state(id));assertEquals(0,knowledge.version());assertEquals(0,jdbc.queryForObject("select count(*) from rag_chunk",Integer.class));
        assertFalse(jdbc.queryForObject("select last_error from rag_document where id=?",String.class,id).contains("private"));
        knowledge.retry(id);doCallRealMethod().when(embedding).embed(anyList());knowledge.process(id);assertEquals("READY",state(id));
    }
    @Test void expiredLeaseIsRecovered() {
        String id=submit(0);jdbc.update("update rag_document set state='PROCESSING',lease_until=DATEADD('SECOND',-1,CURRENT_TIMESTAMP),lease_token='old' where id=?",id);
        knowledge.process(id);assertEquals("READY",state(id));
    }
    @Test void unicodeChunkOverlapDoesNotSplitSurrogates() {
        String text="服务😀".repeat(500);List<String> chunks=KnowledgeService.split(text);
        assertTrue(chunks.size()>1);
        StringBuilder recovered=new StringBuilder(chunks.get(0));
        for(int i=1;i<chunks.size();i++) { String c=chunks.get(i);recovered.append(c.substring(c.offsetByCodePoints(0,80))); }
        assertEquals(text,recovered.toString());
    }
    @Test void forgedOrAbsentCitationIsRejected() throws Exception {
        VectorIndex.Hit hit=new VectorIndex.Hit("id","doc","标题","志愿者请进入活动列表选择可报名活动",0,1);
        List<VectorIndex.Hit> hits=List.of(hit);
        assertThrows(Exception.class,()->answers.validate("{\"answer\":\"答案\",\"citations\":[]}",hits));
        assertThrows(Exception.class,()->answers.validate("{\"answer\":\"答案\",\"citations\":[{\"chunkId\":\"fake\",\"quote\":\"志愿者请进入活动列表选择可报名活动\"}]}",hits));
        assertThrows(Exception.class,()->answers.validate("{\"answer\":\"答案\",\"citations\":[{\"chunkId\":\"id\",\"quote\":\"这是一段来源资料里面并不存在的引文\"}]}",hits));
        String valid="{\"answer\":\"请前往活动列表\",\"citations\":[{\"chunkId\":\"id\",\"quote\":\"志愿者请进入活动列表选择可报名活动\"}]}";
        assertEquals("MODEL",answers.validate(valid,hits).get("mode"));assertThrows(Exception.class,()->answers.validate(valid+" {}",hits));
    }
    @Test void inputGuardAvoidsModelAndEmbedding() {
        when(model.guardrailReply("old","客服电话")).thenReturn("没有客服电话");
        assertEquals("GUARDRAIL",answers.answer(1,"客服电话").get("mode"));verify(embedding,never()).embed(anyList());
    }
    @Test void generatedAnswerUsesRetrievedEvidenceAndOutputGuard() throws Exception {
        String id=submit(0);knowledge.process(id);ReflectionTestUtils.setField(answers,"mode","model");
        when(model.groundedAnswer(anyString(),anyString())).thenAnswer(call -> {
            tools.jackson.databind.JsonNode evidence=json.readTree((String)call.getArgument(1)).get(0);
            return json.writeValueAsString(Map.of("answer","请进入活动列表报名。","citations",List.of(Map.of("chunkId",evidence.path("chunkId").asText(),"quote",evidence.path("text").asText()))));
        });
        assertEquals("MODEL",answers.answer(1,"活动报名").get("mode"));
        when(model.guardrailReplyForModelOutput(anyString(),anyString())).thenReturn("输出已阻止");
        assertEquals("GUARDRAIL",answers.answer(1,"活动报名").get("mode"));
    }
    @Test void revisionChangeDuringGenerationRejectsStaleAnswer() throws Exception {
        String id=submit(0);knowledge.process(id);ReflectionTestUtils.setField(answers,"mode","model");
        when(model.groundedAnswer(anyString(),anyString())).thenAnswer(call -> {
            tools.jackson.databind.JsonNode evidence=json.readTree((String)call.getArgument(1)).get(0);knowledge.disable(id);
            return json.writeValueAsString(Map.of("answer","旧资料回答","citations",List.of(Map.of("chunkId",evidence.path("chunkId").asText(),"quote",evidence.path("text").asText()))));
        });
        assertEquals(409,assertThrows(ResponseStatusException.class,()->answers.answer(1,"活动报名")).getStatusCode().value());
    }
    @Test void boundedExecutorRejectsExcessWork() throws Exception {
        AiTaskExecutor executor=new AiTaskExecutor();CountDownLatch running=new CountDownLatch(4), release=new CountDownLatch(1);
        try {
            for(int i=0;i<4;i++) executor.submit(()->{running.countDown();try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }return "done";});
            assertTrue(running.await(2,TimeUnit.SECONDS));for(int i=0;i<16;i++) executor.submit(()->"queued");
            Object result=executor.submit(()->"overflow").getResult();assertTrue(result instanceof ResponseStatusException);assertEquals(503,((ResponseStatusException)result).getStatusCode().value());
        } finally { release.countDown();executor.close(); }
    }
    @Test void queuedTimeoutRemovesTaskWithoutRunningIt() throws Exception {
        AiTaskExecutor executor=new AiTaskExecutor();CountDownLatch running=new CountDownLatch(4),release=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean executed=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            for(int i=0;i<4;i++) executor.submit(()->{running.countDown();try {release.await();} catch(InterruptedException e){Thread.currentThread().interrupt();}return "done";});
            assertTrue(running.await(2,TimeUnit.SECONDS));
            org.springframework.web.context.request.async.DeferredResult<?> result=executor.submit(()->{executed.set(true);return "late";});
            ((Runnable)ReflectionTestUtils.getField(result,"timeoutCallback")).run();
            assertEquals(504,((ResponseStatusException)result.getResult()).getStatusCode().value());
            ThreadPoolExecutor pool=(ThreadPoolExecutor)ReflectionTestUtils.getField(executor,"pool");assertEquals(0,pool.getQueue().size());assertFalse(executed.get());
        } finally {release.countDown();executor.close();}
    }
}
