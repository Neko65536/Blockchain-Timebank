package org.example.timecoinweb.agent;
import tools.jackson.databind.ObjectMapper;
import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.service.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishAgentTests {
    final ObjectMapper json=tools.jackson.databind.json.JsonMapper.builder().configureForJackson2().build();
    final AiChatService model=mock(AiChatService.class);
    final UserService users=mock(UserService.class);
    PublishAgent agent; PublishConfirmation confirm; JdbcTemplate jdbc;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:agent"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema-agent.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);jdbc.execute("create table `user`(id int primary key,role int)");jdbc.update("insert into `user` values(1,1),(2,1),(3,3)");
        var config=new BlockchainProperties();config.setEnabled(false);
        var validator=new ActivityDraftValidator(json,config);
        agent=new PublishAgent(jdbc,json,new AgentRuntime(model,json,jdbc),validator,new DataSourceTransactionManager(ds),config);
        confirm=new PublishConfirmation(agent,validator,jdbc,users);
        when(model.reactModel()).thenAnswer(call -> ScriptedAgentModel.forPlan(json.readTree("{\"title\":\"陪同购物\"}")));
    }
    @Test void repeatCreationReusesTaskAndNeverPublishes() throws Exception {
        var first=agent.create(1,"request-001","买菜");
        assertEquals(first.get("id"),first.get("traceId"));
        assertEquals(first.get("id"),agent.create(1,"request-001","买菜").get("id"));
        assertEquals("NEEDS_INPUT",first.get("state"));
        assertThrows(Exception.class,()->agent.create(1,"request-001","其他需求"));
        verify(model,times(1)).reactModel();verifyNoInteractions(users);
    }
    @Test void ownershipAndRevisionAreEnforced() throws Exception {
        String id=(String)agent.create(1,"request-002","买菜").get("id");
        assertThrows(Exception.class,()->agent.get(2,id));
        assertThrows(Exception.class,()->agent.create(3,"request-003","买菜"));
        agent.revise(1,id,0,json.readTree("{\"title\":\"补充\"}"));
        assertThrows(Exception.class,()->agent.revise(1,id,0,json.readTree("{}")));
        assertThrows(Exception.class,()->confirm.confirm(1,id,1,"0",true));
        verifyNoInteractions(users);
    }
    @Test void historyListsOnlyOwnTasksWithPaging() throws Exception {
        agent.create(1,"request-h1","买菜");
        agent.create(1,"request-h2","读报");
        agent.create(2,"request-h3","买菜");
        var page=agent.list(1,1,1);
        assertEquals(2L,page.get("total"));
        assertEquals(1,((java.util.List<?>)page.get("items")).size());
        var all=agent.list(1,1,10);
        assertEquals(2,((java.util.List<?>)all.get("items")).size());
        assertThrows(Exception.class,()->agent.list(3,1,10));   // 管理员非老人身份无权
        assertThrows(Exception.class,()->agent.list(1,0,10));
    }

    @Test void explicitConfirmationIsIdempotentAndBoundToSavedForm() throws Exception {
        String id=(String)agent.create(1,"request-004","买菜").get("id");
        var draft=json.readTree("{\"title\":\"购物\",\"description\":\"陪同\",\"quota\":1,\"phone\":\"123\",\"address\":\"一号门\",\"date\":\"2099-11-20\",\"begin\":\"09:00:00\",\"end\":\"10:00:00\",\"deadline\":\"2099-11-19T18:00:00\",\"volunteerReward\":0,\"serviceType\":\"other_service\",\"extraJson\":\"{\\\"customCategory\\\":\\\"购物\\\",\\\"serviceDetails\\\":\\\"陪同\\\"}\"}");
        assertEquals("READY",agent.revise(1,id,0,draft).get("state"));
        assertThrows(Exception.class,()->confirm.confirm(1,id,1,"0",false));
        assertThrows(Exception.class,()->confirm.confirm(1,id,1,"1",true));
        when(users.oldAddActivity(any(),eq(1),eq("agent:"+id))).thenReturn(Map.of("settlementId","test-order","state","PENDING"));
        confirm.confirm(1,id,1,"0",true);confirm.confirm(1,id,1,"0",true);
        verify(users,times(1)).oldAddActivity(any(),eq(1),eq("agent:"+id));
        assertEquals("SUBMITTED",agent.get(1,id).get("state"));
        assertThrows(Exception.class,()->agent.revise(1,id,1,draft));
    }
}
