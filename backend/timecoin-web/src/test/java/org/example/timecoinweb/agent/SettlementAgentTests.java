package org.example.timecoinweb.agent;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import org.example.pojo.Activity;
import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.mapper.*;
import org.example.timecoinweb.service.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 结算答谢Agent：模型只预检组装提案，发放必须人工确认并复用既有批量链路。 */
class SettlementAgentTests {
    final ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder().configureForJackson2().build();
    final AiChatService model = mock(AiChatService.class);
    final ActivityMapper activities = mock(ActivityMapper.class);
    final OldMapper olds = mock(OldMapper.class);
    final UserMapper users = mock(UserMapper.class);
    final VolMapper vols = mock(VolMapper.class);
    final SettlementService settlements = mock(SettlementService.class);
    final UserService userService = mock(UserService.class);
    JdbcTemplate jdbc; SettlementAgent agent; SettlementConfirmation confirmation; BlockchainProperties chain;

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:settle" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema-agent.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table `user`(id int primary key, role int, coin_balance bigint default 0)");
        jdbc.execute("create table volunteer(id int primary key, user_id int)");
        jdbc.execute("create table activity_volunteer(activity_id int, volunteer_id int, sign int default 0, status int default 0, reward_paid int default 0)");
        jdbc.execute("create table settlement_order(id varchar(36), activity_id int, state varchar(16))");
        jdbc.update("insert into `user` values(1,1,1000),(2,1,0),(3,2,0)");
        jdbc.update("insert into volunteer values(10,3)");
        jdbc.update("insert into activity_volunteer values(100,10,1,0,0)");

        chain = new BlockchainProperties();
        chain.setBatchRewardMaxVolunteers(200);
        chain.setBatchRewardTotalCap(java.math.BigInteger.valueOf(100000));
        var audit = new PublishAgent(jdbc, json, new AgentRuntime(model, json, jdbc),
                mock(ActivityDraftValidator.class), new DataSourceTransactionManager(ds), chain);
        agent = new SettlementAgent(jdbc, json, new AgentRuntime(model, json, jdbc), activities, olds, users,
                vols, settlements, chain, audit, new DataSourceTransactionManager(ds));
        confirmation = new SettlementConfirmation(agent, jdbc, userService, audit, new DataSourceTransactionManager(ds));

        when(olds.selectOldId(1)).thenReturn(7);
        when(users.selectById(anyInt())).thenAnswer(i -> {
            var u = new org.example.pojo.User(); u.setId(i.getArgument(0)); return u;
        });
        Activity activity = activity(100, 7);
        when(activities.selectByActId(100)).thenReturn(activity);
        when(model.reactModel()).thenAnswer(call -> new ToolScriptModel(json));
    }

    private Activity activity(int id, int oldId) {
        Activity a = new Activity();
        a.setId(id); a.setOldId(oldId); a.setVolunteerReward(5);
        a.setDate(LocalDate.now().minusDays(1));
        a.setBegin(LocalTime.of(9, 0)); a.setEnd(LocalTime.of(10, 0));
        return a;
    }

    @Test void prepareProducesServerAuthoritativeProposalWithoutPaying() throws Exception {
        var view = agent.prepare(1, "settle-key-001", 100);
        assertEquals("PREPARED", view.get("state"));
        tools.jackson.databind.JsonNode proposal = (tools.jackson.databind.JsonNode) view.get("proposal");
        assertEquals(100, proposal.path("activityId").intValue());
        assertEquals(5, proposal.path("rewardPerVolunteer").intValue());
        assertEquals(1L, proposal.path("signedPending").asLong());   // 仅已签到者计入
        assertEquals(5L, proposal.path("totalCost").asLong());
        assertEquals(1000L, proposal.path("balance").asLong());
        assertEquals(true, proposal.path("eligible").asBoolean());
        verifyNoInteractions(userService);                 // 预检不发放
        assertEquals(1, jdbc.queryForObject("select count(*) from agent_audit where task_id=? and tool='SETTLEMENT_PRECHECK'",
                Integer.class, view.get("id")));
    }

    @Test void prepareIsIdempotentAndRejectsForeignActivity() throws Exception {
        var first = agent.prepare(1, "settle-key-002", 100);
        assertEquals(first.get("id"), agent.prepare(1, "settle-key-002", 100).get("id"));
        assertThrows(Exception.class, () -> agent.prepare(2, "settle-key-003", 100));     // 非老人
        when(activities.selectByActId(101)).thenReturn(activity(101, 99));
        assertThrows(Exception.class, () -> agent.prepare(1, "settle-key-004", 101));     // 非本人活动
    }

    @Test void confirmRequiresExplicitFlagAndCallsBatchRewardOnce() throws Exception {
        var view = agent.prepare(1, "settle-key-005", 100);
        String id = (String) view.get("id");
        int revision = ((Number) view.get("revision")).intValue();
        assertThrows(Exception.class, () -> confirmation.confirm(1, id, revision, false));
        when(userService.batchCompleteVolunteers(1, 100)).thenReturn(Map.of("successCount", 1));
        confirmation.confirm(1, id, revision, true);
        verify(userService, times(1)).batchCompleteVolunteers(1, 100);
        assertEquals("CONFIRMED", jdbc.queryForObject("select state from agent_settlement where id=?", String.class, id));
        confirmation.confirm(1, id, revision, true);                                     // 终态幂等
        verify(userService, times(1)).batchCompleteVolunteers(1, 100);
    }

    @Test void confirmRefusesStaleRevision() throws Exception {
        var view = agent.prepare(1, "settle-key-006", 100);
        assertThrows(Exception.class, () -> confirmation.confirm(1, (String) view.get("id"), 99, true));
        verifyNoInteractions(userService);
    }

    @Test void ineligibleProposalCannotBeConfirmed() throws Exception {
        // 将志愿者签到改为未签到，使提案 eligibility=false
        jdbc.update("update activity_volunteer set sign=0 where activity_id=100");
        var view = agent.prepare(1, "settle-key-007", 100);
        tools.jackson.databind.JsonNode proposal = (tools.jackson.databind.JsonNode) view.get("proposal");
        assertEquals(false, proposal.path("eligible").asBoolean());
        assertThrows(Exception.class, () -> confirmation.confirm(1, (String) view.get("id"), 0, true));
        verifyNoInteractions(userService);
    }

    /** 按脚本依次调用四个 inspect 工具，最后 complete(note)。 */
    static final class ToolScriptModel implements Model {
        private final ObjectMapper json; private final AtomicInteger step = new AtomicInteger();
        ToolScriptModel(ObjectMapper json) { this.json = json; }
        @Override public String getModelName() { return "settlement-test"; }
        @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int i = step.getAndIncrement();
            String tool = switch (i) { case 0 -> "inspect_activity"; case 1 -> "inspect_volunteers";
                case 2 -> "inspect_balance"; case 3 -> "inspect_pending"; default -> "complete"; };
            Map<String, Object> args = "inspect_activity".equals(tool) ? Map.of("activityId", 100)
                    : "complete".equals(tool) ? Map.of("note", "只读预检") : Map.of();
            return Flux.just(ChatResponse.builder().id(UUID.randomUUID().toString())
                    .content(List.of(new ToolUseBlock(UUID.randomUUID().toString(), tool, args))).build());
        }
    }
}
