package org.example.timecoinweb;

import org.example.timecoinweb.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.example.utils.JwtUtils;
import org.example.pojo.Activity;
import org.example.pojo.VolActivity;
import java.util.Map;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.service.TimeCoinChainService;
import org.example.timecoinweb.service.ChainTransactionService;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("dev")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p0;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "blockchain.enabled=false", "ai.chat.enabled=false", "app.upload.image-dir=./target/test-images"
        , "security.jwt-secret=test-only-secret-for-p0-regression-32-bytes", "settlement.retry-ms=0", "settlement.mq-enabled=false", "app.redis-enabled=false"
})
class TimecoinWebApplicationTests {
    @Autowired UserService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired BlockchainProperties blockchain;
    @Autowired TimeCoinChainService chain;
    @Autowired ChainTransactionService submissions;
    @Autowired org.example.timecoinweb.config.ShardRouting routing;
    @Autowired org.example.timecoinweb.service.SettlementService settlements;
    @Autowired org.example.timecoinweb.agent.PublishAgent publishAgent;
    @Autowired org.example.timecoinweb.agent.PublishConfirmation publishConfirmation;
    @Autowired org.example.timecoinweb.agent.ReviewAgent reviewAgent;
    @Autowired org.example.timecoinweb.agent.AnalyticsAgent analyticsAgent;
    @org.springframework.boot.test.mock.mockito.SpyBean org.example.timecoinweb.service.AiChatService agentModel;
    @MockBean(answer = org.mockito.Answers.RETURNS_DEEP_STUBS) Web3j web3j;

    @TestConfiguration
    static class SigningConfig {
        @Bean Credentials testCredentials() { return Credentials.create("1"); }
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("delete from agent_audit");
        jdbc.update("delete from agent_task");
        jdbc.update("delete from agent_review");
        jdbc.update("delete from agent_review_batch");
        blockchain.setEnabled(false);
        jdbc.update("delete from settlement_outbox");
        jdbc.update("delete from settlement_order");
        jdbc.update("delete from chain_submission");
        jdbc.update("delete from chain_tx_log");
        jdbc.update("delete from activity_volunteer");
        jdbc.update("delete from activity");
        jdbc.update("delete from volunteer");
        jdbc.update("delete from old");
        jdbc.update("delete from administrator");
        jdbc.update("delete from `user`");
        jdbc.update("insert into `user` (id,username,password,role) values (1,'admin','test',3),(2,'elder','test',1),(3,'other','test',1)");
        jdbc.update("insert into administrator (id,user_id) values (1,1)");
        jdbc.update("insert into old (id,user_id) values (1,2),(2,3)");
        for (int id = 10; id < 30; id++) {
            jdbc.update("insert into `user` (id,username,password,role) values (?,?,'test',2)", id, "vol" + id);
            jdbc.update("insert into volunteer (id,user_id) values (?,?)", id, id);
        }
        jdbc.update("insert into activity (id,title,quota,remain,deadline,date,`begin`,`end`,address,old_id,administrator_id,status) "
                + "values (1,'test',5,5,DATEADD('DAY',1,CURRENT_TIMESTAMP),DATEADD('DAY',2,CURRENT_DATE),'09:00:00','10:00:00','test',1,1,2)");
    }

    @Test
    void agentConfirmationUsesRealSettlementTransactionAndRejectsOtherOwners() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var draft=json.readTree("{\"title\":\"购物\",\"description\":\"陪同\",\"quota\":1,\"phone\":\"123\",\"address\":\"一号门\",\"date\":\"2099-11-20\",\"begin\":\"09:00:00\",\"end\":\"10:00:00\",\"deadline\":\"2099-11-19T18:00:00\",\"volunteerReward\":0,\"serviceType\":\"other_service\",\"extraJson\":\"{\\\"customCategory\\\":\\\"购物\\\",\\\"serviceDetails\\\":\\\"陪同\\\"}\"}");
        doReturn(draft).when(agentModel).planAgentTool(anyString(),anyString());
        String id=(String)publishAgent.create(2,"agent-integration-001","陪同购物").get("id");
        assertEquals(0,jdbc.queryForObject("select count(*) from settlement_order",Integer.class));
        mvc.perform(get("/users/old/agent/publish/"+id).header("token",token(3,1))).andExpect(status().isNotFound());
        mvc.perform(get("/users/old/agent/publish/"+id).header("token",token(10,2))).andExpect(status().isForbidden());
        assertThrows(Exception.class,()->publishConfirmation.confirm(2,id,0,"0",false));
        publishConfirmation.confirm(2,id,0,"0",true);
        publishConfirmation.confirm(2,id,0,"0",true);
        assertEquals(1,jdbc.queryForObject("select count(*) from settlement_order where owner_id=2",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from activity where title='购物' and old_id=1",Integer.class));
        assertEquals("SUBMITTED",publishAgent.get(2,id).get("state"));
    }

    private void reviewPrediction(int id) throws Exception {
        jdbc.update("update activity set status=1,address='附近',description='请提供验证码' where id=1");
        doReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"reviews\":[{\"activityId\":"+id+",\"risk\":\"LOW\",\"tags\":[],\"reason\":\"模型建议人工核对\"}]}"))
                .when(agentModel).planAgentTool(anyString(),anyString());
    }
    @Test void reviewRulesCannotBeDowngradedByModelAndHumanDecisionIsAudited() throws Exception {
        reviewPrediction(1);
        var rows=reviewAgent.screen(1,"review-key-001",List.of(1));
        String id=(String)rows.get(0).get("id");
        assertEquals("HIGH",rows.get(0).get("risk"));
        assertEquals(1,jdbc.queryForObject("select status from activity where id=1",Integer.class));
        assertEquals(id,reviewAgent.screen(1,"review-key-001",List.of(1)).get(0).get("id"));
        assertThrows(Exception.class,()->reviewAgent.decide(1,id,"APPROVE","已核实",false));
        reviewAgent.decide(1,id,"REJECT","信息不足，需重新补充",true);
        reviewAgent.decide(1,id,"REJECT","信息不足，需重新补充",true);
        assertEquals(4,jdbc.queryForObject("select status from activity where id=1",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from agent_audit where task_id=? and tool='HUMAN_REVIEW'",Integer.class,id));
        assertThrows(Exception.class,()->reviewAgent.decide(1,id,"APPROVE","改为通过",true));
    }
    @Test void staleReviewAndUnauthorizedAccessAreRejected() throws Exception {
        reviewPrediction(1);String id=(String)reviewAgent.screen(1,"review-key-002",List.of(1)).get(0).get("id");
        jdbc.update("update activity set address='已修改的社区东门' where id=1");
        assertThrows(Exception.class,()->reviewAgent.decide(1,id,"APPROVE","已核实",true));
        assertEquals(1,jdbc.queryForObject("select status from activity where id=1",Integer.class));
        assertThrows(Exception.class,()->reviewAgent.screen(2,"review-key-003",List.of(1)));
        mvc.perform(get("/administrator/agent/reviews").header("token",token(2,1))).andExpect(status().isForbidden());
    }
    @Test void completeReviewedActivityCanBeApprovedWithoutChangingItsForm() throws Exception {
        reviewPrediction(1);
        jdbc.update("update activity set address='社区东区一号门',description='陪同购物',phone='123',extra_json=? where id=1","{\"customCategory\":\"购物\",\"serviceDetails\":\"陪同\"}");
        var rows=reviewAgent.screen(1,"review-approve-001",List.of(1));
        assertEquals("LOW",rows.get(0).get("risk"));
        reviewAgent.decide(1,(String)rows.get(0).get("id"),"APPROVE","已逐项核对资料",true);
        assertEquals(2,jdbc.queryForObject("select status from activity where id=1",Integer.class));
        assertEquals("陪同购物",jdbc.queryForObject("select description from activity where id=1",String.class));
    }
    @Test void revokedRoleDuringModelCallCannotWriteOrReadBusinessData() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        doAnswer(invocation->{jdbc.update("update `user` set role=2 where id=2");return json.readTree("{\"title\":\"买菜\"}");}).when(agentModel).planAgentTool(anyString(),anyString());
        assertThrows(Exception.class,()->publishAgent.create(2,"revoked-publish-001","买菜"));
        assertEquals(0,jdbc.queryForObject("select count(*) from agent_task",Integer.class));
        jdbc.update("update activity set status=1 where id=1");
        doAnswer(invocation->{jdbc.update("update `user` set role=2 where id=1");return json.readTree("{\"reviews\":[{\"activityId\":1,\"risk\":\"LOW\",\"tags\":[],\"reason\":\"核对\"}]}");}).when(agentModel).planAgentTool(anyString(),anyString());
        assertThrows(Exception.class,()->reviewAgent.screen(1,"revoked-review-001",List.of(1)));
        assertEquals(0,jdbc.queryForObject("select count(*) from agent_review",Integer.class));
        jdbc.update("update `user` set role=3 where id=1");
        doAnswer(invocation->{jdbc.update("update `user` set role=2 where id=1");return json.readTree("{\"tool\":\"coin_daily_flow\",\"start\":\"2026-01-01\",\"end\":\"2026-01-02\"}");}).when(agentModel).planAgentTool(anyString(),anyString());
        assertThrows(Exception.class,()->analyticsAgent.answer(1,"时间币趋势"));
        assertEquals(0,jdbc.queryForObject("select count(*) from agent_audit",Integer.class));
    }
    @Test void forgedModelActivityCannotCreateReviewOrModifyAnything() throws Exception {
        reviewPrediction(999);
        assertThrows(Exception.class,()->reviewAgent.screen(1,"review-key-004",List.of(1)));
        assertEquals(0,jdbc.queryForObject("select count(*) from agent_review",Integer.class));
        assertEquals(1,jdbc.queryForObject("select status from activity where id=1",Integer.class));
    }

    private void analyticsPlan(String tool,java.time.LocalDate start,java.time.LocalDate end) throws Exception {
        doReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"tool\":\""+tool+"\",\"start\":\""+start+"\",\"end\":\""+end+"\"}"))
                .when(agentModel).planAgentTool(anyString(),anyString());
    }
    @Test void analyticsWhitelistAndDateBoundsRejectUnsafeQueries() throws Exception {
        var end=java.time.LocalDate.now();var start=end.minusDays(7);
        analyticsPlan("select * from user",start,end);
        assertThrows(Exception.class,()->analyticsAgent.answer(1,"查询账号"));
        analyticsPlan("coin_daily_flow",start.minusDays(32),end);
        assertThrows(Exception.class,()->analyticsAgent.answer(1,"查流量"));
        analyticsPlan("coin_daily_flow",start,end);
        assertThrows(Exception.class,()->analyticsAgent.answer(2,"查流量"));
        doReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"tool\":\"coin_daily_flow\",\"start\":\""+start+"\",\"end\":\""+end+"\",\"sql\":\"delete from user\"}"))
                .when(agentModel).planAgentTool(anyString(),anyString());
        assertThrows(Exception.class,()->analyticsAgent.answer(1,"查流量"));
        assertEquals(0,jdbc.queryForObject("select count(*) from agent_audit",Integer.class));
        mvc.perform(post("/administrator/agent/analytics").header("token",token(2,1)).contentType("application/json").content("{\"question\":\"查流量\"}")).andExpect(status().isForbidden());
    }
    @Test void analyticsCountsOnlyInRangeRetainedRegistrations() throws Exception {
        var end=java.time.LocalDate.now();var start=end.minusDays(7);
        jdbc.update("insert into activity_volunteer(activity_id,volunteer_id,create_time) values(1,10,?),(1,11,?),(1,12,?)",start.atStartOfDay(),start.plusDays(1).atStartOfDay(),end.atStartOfDay());
        analyticsPlan("activity_registration_top",start,end);
        var result=analyticsAgent.answer(1,"上周哪个活动报名最多");
        var rows=(List<Map<String,Object>>)result.get("rows");
        assertEquals(1,rows.size());assertEquals("2",rows.get(0).get("value"));
        assertEquals(3,jdbc.queryForObject("select count(*) from activity_volunteer",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from agent_audit where tool='ANALYTICS_READ'",Integer.class));
    }
    @Test void analyticsFlowExcludesMintAndEndBoundaryAndFillsMissingDays() throws Exception {
        var end=java.time.LocalDate.now();var start=end.minusDays(2);
        jdbc.update("insert into chain_tx_log(tx_type,to_account,amount,create_time) values('TRANSFER','1',7,?),('TRANSFER','1',8,?),('MINT','1',100,?),('TRANSFER','1',99,?)",start.atStartOfDay(),start.atTime(12,0),start.atTime(13,0),end.atStartOfDay());
        analyticsPlan("coin_daily_flow",start,end);
        var result=analyticsAgent.answer(1,"时间币流转趋势");
        var rows=(List<Map<String,Object>>)result.get("rows");
        assertEquals(2,rows.size());assertEquals("15",rows.get(0).get("value"));assertEquals("0",rows.get(1).get("value"));
        assertTrue(result.get("answer").toString().contains("15时间币"));
        assertEquals(4,jdbc.queryForObject("select count(*) from chain_tx_log",Integer.class));
    }

    @Test
    void knowledgeManagementRequiresAdminAndChatUsesAsyncDispatch() throws Exception {
        String elder=JwtUtils.generateJwt(Map.of("id",2,"role",1));
        mvc.perform(get("/administrator/knowledge").header("token",elder)).andExpect(status().isForbidden());
        org.springframework.test.web.servlet.MvcResult result=mvc.perform(post("/users/old/ai/chat").header("token",elder)
                .contentType("application/json").content("{\"message\":\"客服电话\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("GUARDRAIL"));
    }

    @Test
    void repeatedCancellationReturnsOnlyOneSlot() {
        assertEquals("1", users.volAcceptActivity(1, 10));
        users.volCancel(10, 1);
        users.volCancel(10, 1);
        assertInventory(5, 0);
    }

    @Test
    void cancellingWithoutRegistrationDoesNotCreateSlots() {
        users.volCancel(10, 1);
        assertInventory(5, 0);
    }

    @Test
    void registrationAndCancellationRejectExpiredDeadline() {
        users.volAcceptActivity(1, 10);
        jdbc.update("update activity set deadline=DATEADD('DAY',-1,CURRENT_TIMESTAMP)");
        assertThrows(IllegalStateException.class, () -> users.volAcceptActivity(1, 11));
        assertThrows(IllegalStateException.class, () -> users.volCancel(10, 1));
        assertInventory(4, 1);
    }

    @Test
    void unapprovedAndFinishedActivitiesRejectRegistration() {
        for (int status : new int[]{1, 4, 5}) {
            jdbc.update("update activity set status=?", status);
            assertThrows(IllegalStateException.class, () -> users.volAcceptActivity(1, 10));
        }
        assertInventory(5, 0);
    }

    @Test
    void competingRegistrationsCannotOversell() throws Exception {
        concurrently(i -> users.volAcceptActivity(1, 10 + i));
        assertInventory(0, 5);
    }

    @Test
    void concurrentDuplicateRegistrationConsumesOnlyOneSlot() throws Exception {
        concurrently(i -> users.volAcceptActivity(1, 10));
        assertInventory(4, 1);
    }

    @Test
    void concurrentCancellationReturnsOnlyOneSlot() throws Exception {
        users.volAcceptActivity(1, 10);
        concurrently(i -> users.volCancel(10, 1));
        assertInventory(5, 0);
    }

    @Test
    void inconsistentInventoryRollsBackCancellation() {
        users.volAcceptActivity(1, 10);
        jdbc.update("update activity set remain=quota");
        assertThrows(IllegalStateException.class, () -> users.volCancel(10, 1));
        assertInventory(5, 1);
    }

    private void assertInventory(int remaining, int registrations) {
        assertEquals(remaining, jdbc.queryForObject("select remain from activity where id=1", Integer.class));
        assertEquals(registrations, jdbc.queryForObject("select count(*) from activity_volunteer", Integer.class));
    }

    @Test
    void statsRequireAdminButNoticesRemainPublic() throws Exception {
        mvc.perform(get("/dashboard/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/dashboard/stats").header("token", token(2, 1))).andExpect(status().isForbidden());
        mvc.perform(get("/dashboard/stats").header("token", token(1, 3))).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
        mvc.perform(get("/dashboard/notices")).andExpect(status().isOk());
        mvc.perform(get("/administrator/notices/cache-metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/administrator/notices/cache-metrics").header("token", token(2, 1))).andExpect(status().isForbidden());
        mvc.perform(get("/administrator/notices/cache-metrics").header("token", token(1, 3)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.requests").isNumber());
    }

    @Test
    void publicRegistrationCannotCreateAdminOrUnknownRole() throws Exception {
        for (int role : new int[]{0, 3, 4}) {
            mvc.perform(post("/register").contentType("application/json")
                    .content("{\"username\":\"intruder\",\"password\":\"test\",\"role\":" + role + "}"))
                    .andExpect(jsonPath("$.code").value(0));
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from `user` where username='intruder'", Integer.class));
    }

    @Test
    void authenticatedAdminCanCreateAnotherAdmin() throws Exception {
        mvc.perform(post("/administrator/users").header("token", token(1, 3)).contentType("application/json")
                .content("{\"username\":\"newadmin\",\"password\":\"test-password\",\"role\":3}"))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(1, jdbc.queryForObject("select count(*) from administrator a join `user` u on a.user_id=u.id where u.username='newadmin'", Integer.class));
    }

    @Test
    void invalidOrStaleIdentityIsRejected() throws Exception {
        mvc.perform(get("/info").header("token", token(2, 3))).andExpect(status().isUnauthorized());
        mvc.perform(get("/info").header("token", token(9999, 1))).andExpect(status().isUnauthorized());
        mvc.perform(get("/info").header("token", "invalid")).andExpect(status().isUnauthorized());
        mvc.perform(get("/administrator/users").header("token", token(10, 2))).andExpect(status().isForbidden());
    }

    @Test
    void profileNeverSerializesPassword() throws Exception {
        mvc.perform(get("/info").header("token", token(2, 1)))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void otherElderCannotReadEditDeleteOrCompleteActivity() throws Exception {
        String other = token(3, 1);
        mvc.perform(get("/users/old/status").param("id", "1").header("token", other))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(put("/users/old").header("token", other).contentType("application/json").content("{\"id\":1,\"title\":\"stolen\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(delete("/users/old/1").header("token", other)).andExpect(jsonPath("$.code").value(0));
        mvc.perform(put("/users/old/status").header("token", other).contentType("application/json")
                .content("{\"activityId\":1,\"userId\":10,\"status\":1}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("test", jdbc.queryForObject("select title from activity where id=1", String.class));
    }

    @Test
    void editingCannotChangeOwnerApprovalOrRemainingSlots() {
        jdbc.update("update activity set status=1");
        Activity change = new Activity();
        change.setId(1);
        change.setTitle("updated");
        change.setOldId(2);
        change.setAdministratorId(999);
        change.setStatus((short) 2);
        change.setRemain((short) 99);
        users.update(change, 2);
        assertEquals(1, jdbc.queryForObject("select old_id from activity", Integer.class));
        assertEquals(1, jdbc.queryForObject("select status from activity", Integer.class));
        assertInventory(5, 0);
    }

    @Test
    void registeredActivityCannotBeEditedOrDeleted() {
        users.volAcceptActivity(1, 10);
        Activity change = new Activity();
        change.setId(1);
        assertThrows(IllegalStateException.class, () -> users.update(change, 2));
        assertThrows(IllegalStateException.class, () -> users.oldDeleteActivity(List.of(1), 2));
        assertInventory(4, 1);
    }

    @Test
    void completionBeforeActivityEndIsRejected() {
        users.volAcceptActivity(1, 10);
        VolActivity change = volunteerChange();
        change.setStatus((short) 1);
        assertThrows(IllegalStateException.class, () -> users.updateVolActivity(change, 2));
    }

    @Test
    void signOnlyUpdateDoesNotResetPaymentAndPaidCompletionCannotBeRevoked() {
        users.volAcceptActivity(1, 10);
        jdbc.update("update activity_volunteer set status=1,reward_paid=1");
        VolActivity change = volunteerChange();
        change.setSign((short) 1);
        change.setRewardPaid((short) 0);
        users.updateVolActivity(change, 2);
        assertEquals(1, jdbc.queryForObject("select reward_paid from activity_volunteer", Integer.class));
        change.setStatus((short) 0);
        assertThrows(IllegalStateException.class, () -> users.updateVolActivity(change, 2));
    }

    @Test
    void clientCannotForgePaymentFlag() {
        users.volAcceptActivity(1, 10);
        VolActivity change = volunteerChange();
        change.setSign((short) 1);
        change.setRewardPaid((short) 1);
        users.updateVolActivity(change, 2);
        assertEquals(0, jdbc.queryForObject("select reward_paid from activity_volunteer", Integer.class));
    }

    private VolActivity volunteerChange() {
        VolActivity value = new VolActivity();
        value.setActivityId(1);
        value.setUserId(10);
        return value;
    }

    private String token(int id, int role) {
        return JwtUtils.generateJwt(Map.of("id", id, "role", role));
    }

    @Test
    void confirmedReceiptUpdatesLedgerOnlyOnce() throws Exception {
        mockChain(new AtomicBoolean(true), false);
        jdbc.update("update `user` set coin_balance=20 where id=2");
        String hash = chain.transfer("2", "10", BigInteger.TEN, "VOLUNTEER_REWARD", "activity:1,vol:10");
        assertEquals(hash, chain.transfer("2", "10", BigInteger.TEN, "VOLUNTEER_REWARD", "activity:1,vol:10"));
        assertEquals(10L, balance(2));
        assertEquals(10L, balance(10));
        assertEquals(1, jdbc.queryForObject("select count(*) from chain_tx_log", Integer.class));
        verify(web3j, times(1)).ethSendRawTransaction(anyString());
    }

    @Test
    void revertedReceiptDoesNotPayOrCompleteService() throws Exception {
        mockChain(new AtomicBoolean(true), true);
        prepareReward();
        VolActivity change = volunteerChange();
        change.setStatus((short) 1);
        assertEquals("RETRY", completeAndProcess(change));
        assertEquals(20L, balance(2));
        assertEquals(0L, balance(10));
        assertEquals(0, jdbc.queryForObject("select reward_paid from activity_volunteer", Integer.class));
        assertEquals("FAILED", jdbc.queryForObject("select state from chain_submission", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from chain_tx_log", Integer.class));
    }

    @Test
    void timedOutRewardResumesOriginalTransactionAfterServiceRecreation() throws Exception {
        AtomicBoolean mined = new AtomicBoolean(false);
        mockChain(mined, false);
        prepareReward();
        VolActivity change = volunteerChange();
        change.setStatus((short) 1);
        assertEquals("RETRY", completeAndProcess(change));
        String hash = jdbc.queryForObject("select tx_hash from chain_submission", String.class);
        assertEquals(20L, balance(2));
        assertEquals(0, jdbc.queryForObject("select reward_paid from activity_volunteer", Integer.class));
        mined.set(true);
        ChainTransactionService restarted = new ChainTransactionService(jdbc,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()),
                blockchain, provider(web3j), provider(Credentials.create("1")), routing);
        assertEquals(hash, restarted.resume(hash));
        assertEquals("SUCCEEDED", completeAndProcess(change));
        assertEquals(10L, balance(2));
        assertEquals(10L, balance(10));
        assertEquals(1, jdbc.queryForObject("select reward_paid from activity_volunteer", Integer.class));
        verify(web3j, times(1)).ethSendRawTransaction(anyString());
    }

    @Test
    void ledgerFailureRollsBackAndCanResumeWithoutSecondPayment() throws Exception {
        mockChain(new AtomicBoolean(true), false);
        prepareReward();
        jdbc.update("update `user` set coin_balance=0 where id=2");
        VolActivity change = volunteerChange();
        change.setStatus((short) 1);
        assertEquals("RETRY", completeAndProcess(change));
        assertEquals("CONFIRMED", jdbc.queryForObject("select state from chain_submission", String.class));
        assertEquals(0, jdbc.queryForObject("select ledger_applied from chain_submission", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from chain_tx_log", Integer.class));
        jdbc.update("update `user` set coin_balance=20 where id=2");
        assertEquals("SUCCEEDED", completeAndProcess(change));
        assertEquals(10L, balance(2));
        assertEquals(10L, balance(10));
        verify(web3j, times(1)).ethSendRawTransaction(anyString());
    }

    @Test
    void sameBusinessCannotChangeAmountAndOverflowIsRejected() throws Exception {
        mockChain(new AtomicBoolean(true), false);
        jdbc.update("update `user` set coin_balance=20 where id=2");
        chain.transfer("2", "10", BigInteger.TEN, "VOLUNTEER_REWARD", "activity:1,vol:10");
        assertThrows(IllegalStateException.class, () -> chain.transfer("2", "10", BigInteger.ONE, "VOLUNTEER_REWARD", "activity:1,vol:10"));
        assertThrows(IllegalArgumentException.class, () -> chain.mint("2", BigInteger.ONE.shiftLeft(63)));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void broadcastTransportFailurePreservesCredentialAndReportsHash() throws Exception {
        mockChain(new AtomicBoolean(false), false);
        when(web3j.ethSendRawTransaction(anyString())).thenAnswer(call -> {
            Request request = mock(Request.class);
            when(request.send()).thenThrow(new java.io.IOException("connection lost"));
            return request;
        });
        jdbc.update("update `user` set coin_balance=20 where id=2");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> chain.transfer("2", "10", BigInteger.TEN, "VOLUNTEER_REWARD", "activity:1,vol:10"));
        String hash = jdbc.queryForObject("select tx_hash from chain_submission", String.class);
        assertTrue(failure.getMessage().contains(hash));
        assertEquals("PREPARED", jdbc.queryForObject("select state from chain_submission", String.class));
        assertEquals(20L, balance(2));
        assertEquals(0L, balance(10));
    }

    @Test
    void separateTransactionsReserveDifferentNonces() throws Exception {
        mockChain(new AtomicBoolean(true), false);
        chain.mint("2", BigInteger.TEN);
        chain.mint("2", BigInteger.ONE);
        assertEquals(11L, balance(2));
        assertEquals(2, jdbc.queryForObject("select count(distinct nonce_value) from chain_submission", Integer.class));
        verify(web3j, times(2)).ethSendRawTransaction(anyString());
    }

    @Test
    void administratorRecoveryDoesNotExposeRawSignedTransactions() throws Exception {
        mockChain(new AtomicBoolean(false), false);
        assertThrows(IllegalStateException.class, () -> chain.mint("2", BigInteger.ONE));
        mvc.perform(get("/administrator/chain/transactions").header("token", token(1, 3)))
                .andExpect(jsonPath("$.data[0].state").value("SUBMITTED"))
                .andExpect(jsonPath("$.data[0].raw_tx").doesNotExist());
        mvc.perform(post("/administrator/chain/transactions/anything/resume").header("token", token(2, 1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void whitelistMatchesPathsRatherThanKeywords() throws Exception {
        org.example.timecoinweb.interceptor.LoginCheckInterceptor interceptor =
                new org.example.timecoinweb.interceptor.LoginCheckInterceptor(mock(org.example.timecoinweb.mapper.UserMapper.class));
        for (String path : List.of("/administrator/login", "/administrator/register", "/administrator/image", "/users/old/info")) {
            org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest("GET", path);
            org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request, response, new Object()));
            assertEquals(401, response.getStatus());
        }
    }

    @Test
    void weakJwtSecretIsRejectedAndTamperedTokenIsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> JwtUtils.configureSecret("short"));
        String original = token(2, 1);
        String tampered = original.substring(0, original.lastIndexOf('.') + 1) + "invalid-signature";
        mvc.perform(get("/info").header("token", tampered)).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentCompletionDoesNotExhaustPoolOrPayTwice() throws Exception {
        mockChain(new AtomicBoolean(true), false);
        prepareReward();
        concurrently(i -> {
            VolActivity change = volunteerChange();
            change.setStatus((short) 1);
            users.updateVolActivity(change, 2);
        });
        String order = jdbc.queryForObject("select id from settlement_order", String.class);
        concurrently(i -> settlements.process(order));
        assertEquals(10L, balance(2));
        assertEquals(10L, balance(10));
        assertEquals(1, jdbc.queryForObject("select count(*) from chain_tx_log", Integer.class));
        assertEquals(1, jdbc.queryForObject("select reward_paid from activity_volunteer", Integer.class));
        verify(web3j, times(1)).ethSendRawTransaction(anyString());
    }

    @Test
    void failedCompletionWriteRollsBackLedgerButRetainsConfirmedTransaction() throws Exception {
        mockChain(new AtomicBoolean(true), false);
        prepareReward();
        VolActivity change = volunteerChange();
        change.setStatus((short) 1);
        jdbc.execute("alter table activity_volunteer add constraint p0_reject_completed check (status is null or status <> 1)");
        try {
            assertEquals("RETRY", completeAndProcess(change));
            assertEquals(20L, balance(2));
            assertEquals(0L, balance(10));
            assertEquals(0, jdbc.queryForObject("select count(*) from chain_tx_log", Integer.class));
            assertEquals(0, jdbc.queryForObject("select ledger_applied from chain_submission", Integer.class));
            assertEquals("CONFIRMED", jdbc.queryForObject("select state from chain_submission", String.class));
        } finally {
            jdbc.execute("alter table activity_volunteer drop constraint p0_reject_completed");
        }
        assertEquals("SUCCEEDED", completeAndProcess(change));
        assertEquals(10L, balance(2));
        assertEquals(10L, balance(10));
        verify(web3j, times(1)).ethSendRawTransaction(anyString());
    }

    @Test
    void endedActivityIsRejectedEvenBeforeScheduledStatusRefresh() {
        jdbc.update("update activity set date=DATEADD('DAY',-1,CURRENT_DATE)");
        assertThrows(IllegalStateException.class, () -> users.volAcceptActivity(1, 10));
        assertInventory(5, 0);
    }

    private long balance(int userId) {
        return jdbc.queryForObject("select coin_balance from `user` where id=?", Long.class, userId);
    }

    private String completeAndProcess(VolActivity change) {
        Map<String, Object> accepted = users.updateVolActivity(change, 2);
        String id = (String) accepted.get("settlementId");
        if (id == null) return (String) accepted.get("state");
        settlements.process(id);
        return (String) settlements.get(id, 2, false).get("state");
    }

    @Test
    void transferRequestsAreIdempotentAndPayloadIsImmutable() throws Exception {
        mockChain(new AtomicBoolean(true), false);
        jdbc.update("update `user` set coin_balance=20 where id=2");
        Map<String, Object> first = settlements.submitTransfer(2, "transfer-123", "USER_TRANSFER", "2", "10", BigInteger.TEN);
        concurrently(i -> settlements.submitTransfer(2, "transfer-123", "USER_TRANSFER", "2", "10", BigInteger.TEN));
        assertEquals(1, jdbc.queryForObject("select count(*) from settlement_order", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from settlement_outbox", Integer.class));
        assertThrows(IllegalArgumentException.class, () -> settlements.submitTransfer(2, "transfer-123", "USER_TRANSFER", "2", "10", BigInteger.ONE));
        String id = (String) first.get("settlementId");
        settlements.process(id); settlements.process(id);
        assertEquals("SUCCEEDED", settlements.get(id, 2, false).get("state"));
        assertEquals(10L, balance(2));
        verify(web3j, times(1)).ethSendRawTransaction(anyString());
        assertThrows(IllegalStateException.class, () -> settlements.get(id, 3, false));
    }

    @Test
    void publishFeeAndActivityAreRecoverableWithOneRequestId() throws Exception {
        AtomicBoolean mined = new AtomicBoolean(false);
        mockChain(mined, false);
        jdbc.update("update `user` set coin_balance=20 where id=2");
        Activity activity = new Activity();
        activity.setTitle("publish"); activity.setQuota((short) 2);
        activity.setDeadline(java.time.LocalDateTime.now().plusDays(1));
        activity.setDate(java.time.LocalDate.now().plusDays(2));
        activity.setBegin(java.time.LocalTime.of(9, 0)); activity.setEnd(java.time.LocalTime.of(10, 0));
        activity.setAddress("test");
        activity.setServiceType("other_service"); activity.setExtraJson("{\"customCategory\":\"陪伴\",\"serviceDetails\":\"散步\"}");
        Map<String, Object> accepted = users.oldAddActivity(activity, 2, "publish-123");
        String id = (String) accepted.get("settlementId");
        assertEquals(0, jdbc.queryForObject("select status from activity where id=?", Integer.class, accepted.get("activityId")));
        settlements.process(id);
        assertEquals("RETRY", settlements.get(id, 2, false).get("state"));
        assertEquals(id, users.oldAddActivity(activity, 2, "publish-123").get("settlementId"));
        mined.set(true);
        settlements.process(id);
        assertEquals("SUCCEEDED", settlements.get(id, 2, false).get("state"));
        assertEquals(1, jdbc.queryForObject("select status from activity where id=?", Integer.class, accepted.get("activityId")));
        assertEquals(16L, balance(2));
        assertEquals(2, jdbc.queryForObject("select count(*) from activity", Integer.class));
        verify(web3j, times(1)).ethSendRawTransaction(anyString());
        // 发布结算完成后仍保留P0允许删除待审核活动的行为；结算审计记录不删除。
        users.oldDeleteActivity(List.of((Integer) accepted.get("activityId")), 2);
        assertEquals(1, jdbc.queryForObject("select count(*) from activity", Integer.class));
        assertEquals("SUCCEEDED", settlements.get(id, 2, false).get("state"));
    }

    @Test
    void expiredConsumerLeaseIsRecoveredAndRetriesAreBounded() throws Exception {
        mockChain(new AtomicBoolean(false), false);
        String id = (String) settlements.submitTransfer(1, "mint-test-123", "ADMIN_MINT", null, "10", BigInteger.ONE).get("settlementId");
        jdbc.update("update settlement_order set state='PROCESSING',lease_token='dead-process',lease_until=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) where id=?", id);
        for (int i = 0; i < 8; i++) settlements.process(id);
        assertEquals("REVIEW", settlements.get(id, 1, false).get("state"));
        assertEquals(1, jdbc.queryForObject("select count(*) from chain_submission", Integer.class));
        assertEquals("RETRY", settlements.retry(id).get("state"));
    }

    @Test
    void outboxFailureRollsBackAcceptedOrder() throws Exception {
        mockChain(new AtomicBoolean(true), false);
        jdbc.execute("alter table settlement_outbox add constraint reject_outbox check (send_count<0)");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> settlements.submitTransfer(2, "rollback-request", "USER_TRANSFER", "2", "10", BigInteger.ONE));
            assertEquals(0, jdbc.queryForObject("select count(*) from settlement_order", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from chain_submission", Integer.class));
        } finally { jdbc.execute("alter table settlement_outbox drop constraint reject_outbox"); }
    }

    @Test
    void activeRewardCannotBeChangedDeletedOrRevoked() throws Exception {
        mockChain(new AtomicBoolean(false), false); prepareReward();
        VolActivity change = volunteerChange(); change.setStatus((short) 1);
        users.updateVolActivity(change, 2);
        Activity edited = new Activity(); edited.setId(1); edited.setVolunteerReward(1);
        org.example.timecoinweb.service.ActivityService admin = context.getBean(org.example.timecoinweb.service.ActivityService.class);
        assertThrows(IllegalStateException.class, () -> admin.update(edited, 1));
        assertThrows(IllegalStateException.class, () -> admin.deleteById(List.of(1)));
        assertThrows(IllegalStateException.class, () -> users.volCancel(10, 1));
        change.setStatus((short) 0);
        assertThrows(IllegalStateException.class, () -> users.updateVolActivity(change, 2));
    }

    @Test
    void independentSignerServicesReserveUniqueNonces() throws Exception {
        mockChain(new AtomicBoolean(true), false);
        ChainTransactionService second = new ChainTransactionService(jdbc,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()),
                blockchain, provider(web3j), provider(Credentials.create("1")), routing);
        concurrently(i -> {
            try { (i % 2 == 0 ? submissions : second).send("0x", "multi-instance:" + i); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals(20, jdbc.queryForObject("select count(distinct nonce_value) from chain_submission", Integer.class));
    }

    @Test
    void acceptedApiDoesNotWaitForRpcAndSettlementAccessIsScoped() throws Exception {
        mockChain(new AtomicBoolean(false), false);
        String body = "{\"userId\":\"10\",\"amount\":\"1\"}";
        mvc.perform(post("/administrator/chain/mint").header("token", token(1, 3)).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/administrator/chain/mint").header("token", token(1, 3)).header("Idempotency-Key", "api-request-123")
                .contentType("application/json").content(body)).andExpect(jsonPath("$.data.state").value("PENDING"));
        verify(web3j, never()).ethSendRawTransaction(anyString());
        String id = jdbc.queryForObject("select id from settlement_order", String.class);
        mvc.perform(get("/info/settlements/" + id).header("token", token(2, 1))).andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/administrator/chain/settlements/" + id + "/retry").header("token", token(2, 1))).andExpect(status().isForbidden());
        assertThrows(IllegalArgumentException.class, () -> settlements.submitTransfer(2, "bad-account-123", "USER_TRANSFER", "2", "010", BigInteger.ONE));
    }

    @Autowired org.springframework.context.ApplicationContext context;

    private void prepareReward() {
        users.volAcceptActivity(1, 10);
        jdbc.update("update activity set date=DATEADD('DAY',-1,CURRENT_DATE),volunteer_reward=10");
        jdbc.update("update `user` set coin_balance=20 where id=2");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void mockChain(AtomicBoolean mined, boolean reverted) throws Exception {
        blockchain.setEnabled(true);
        blockchain.setContractAddress("0x0000000000000000000000000000000000000001");
        blockchain.setReceiptAttempts(2);
        blockchain.setReceiptPollIntervalMs(0);
        EthChainId chainId = new EthChainId();
        chainId.setResult("0x7a69");
        when(web3j.ethChainId().send()).thenReturn(chainId);
        EthGetTransactionCount nonce = new EthGetTransactionCount();
        nonce.setResult("0x0");
        when(web3j.ethGetTransactionCount(anyString(), eq(DefaultBlockParameterName.PENDING)).send()).thenReturn(nonce);
        java.util.Set<String> broadcastHashes = ConcurrentHashMap.newKeySet();
        when(web3j.ethSendRawTransaction(anyString())).thenAnswer(call -> {
            Request request = mock(Request.class);
            EthSendTransaction sent = new EthSendTransaction();
            sent.setResult(Hash.sha3((String) call.getArgument(0)));
            when(request.send()).thenAnswer(x -> { broadcastHashes.add(sent.getTransactionHash()); return sent; });
            return request;
        });
        when(web3j.ethGetTransactionReceipt(anyString())).thenAnswer(call -> {
            Request request = mock(Request.class);
            EthGetTransactionReceipt response = new EthGetTransactionReceipt();
            if (broadcastHashes.contains((String) call.getArgument(0)) && mined.get()) {
                TransactionReceipt receipt = new TransactionReceipt();
                receipt.setTransactionHash(call.getArgument(0));
                receipt.setBlockNumber("0x1");
                receipt.setStatus(reverted ? "0x0" : "0x1");
                response.setResult(receipt);
            }
            when(request.send()).thenReturn(response);
            return request;
        });
    }

    @SuppressWarnings("unchecked")
    private <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        org.springframework.beans.factory.ObjectProvider<T> provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(value);
        return provider;
    }

    private void concurrently(IntConsumer operation) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                int index = i;
                tasks.add(pool.submit(() -> {
                    start.await();
                    operation.accept(index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
