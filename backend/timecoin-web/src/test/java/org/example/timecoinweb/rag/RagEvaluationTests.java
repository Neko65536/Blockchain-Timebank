package org.example.timecoinweb.rag;

import org.example.timecoinweb.service.AiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.*;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 固定标注驱动真实入库、检索、权限过滤、回答及引用校验，不mock检索命中。 */
class RagEvaluationTests {
    private final ObjectMapper json=new ObjectMapper();

    @Test void evaluateAnnotatedQuestions() throws Exception {
        byte[] bytes;
        try(var input=new ClassPathResource("rag/evaluation-v1.json").getInputStream()){bytes=input.readAllBytes();}
        JsonNode dataset=json.readTree(bytes);
        String answerMode=System.getProperty("rag.eval.answer-mode","extractive");
        String embeddingMode=System.getProperty("rag.eval.embedding-mode","local-hash");
        assertTrue(Set.of("extractive","model").contains(answerMode));
        assertTrue(Set.of("local-hash","remote").contains(embeddingMode));
        double minimum=Double.parseDouble(System.getProperty("rag.eval.minimum-score","0.2"));
        assertTrue(Double.isFinite(minimum) && minimum>=0 && minimum<=1);
        var ds=new DriverManagerDataSource("jdbc:h2:mem:rag-eval-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema-rag.sql")).execute(ds);
        var jdbc=new JdbcTemplate(ds);
        var embedding=new EmbeddingService(json);
        configure(embedding,"mode",embeddingMode);
        boolean remote="remote".equals(embeddingMode);
        configure(embedding,"dimensions",remote?Integer.parseInt(env("AI_RAG_EMBEDDING_DIMENSIONS","512")):512);
        configure(embedding,"model",remote?env("AI_RAG_EMBEDDING_MODEL","text-embedding-v3"):"offline-evaluation-v1");
        configure(embedding,"url",remote?env("AI_RAG_EMBEDDING_URL",""):"");
        configure(embedding,"apiKey",remote?env("AI_RAG_EMBEDDING_KEY",""):"");
        var knowledge=new KnowledgeService(jdbc,new DataSourceTransactionManager(ds),embedding,json);
        var index=new InMemoryVectorIndex(jdbc,json);
        var model=new AiChatService();
        configure(model,"enabled","model".equals(answerMode));
        configure(model,"apiKey",env("AI_CHAT_API_KEY",""));
        configure(model,"baseUrl",env("AI_CHAT_BASE_URL",""));
        configure(model,"apiUrl",env("AI_CHAT_API_URL","/chat/completions"));
        configure(model,"model",env("AI_CHAT_MODEL","unconfigured"));
        configure(model,"temperature",0.2);configure(model,"maxTokens",2048);
        configure(model,"topP",0.8);configure(model,"repetitionPenalty",1.05);
        if("model".equals(answerMode)) {
            assertFalse(env("AI_CHAT_API_KEY","").isBlank(),"显式model评测需要AI_CHAT_API_KEY");
            assertFalse(env("AI_CHAT_BASE_URL","").isBlank(),"显式model评测需要AI_CHAT_BASE_URL");
            assertFalse(env("AI_CHAT_MODEL","").isBlank(),"显式model评测需要AI_CHAT_MODEL");
        }
        var answers=new RagAnswerService(knowledge,embedding,index,model,json,mock(StringRedisTemplate.class));
        configure(answers,"mode",answerMode);configure(answers,"minimum",minimum);

        Map<String,JsonNode> documents=new LinkedHashMap<>();
        Map<String,String> databaseIds=new HashMap<>();
        for(JsonNode doc:dataset.path("documents")) {
            String id=doc.path("id").asText();assertNull(documents.put(id,doc),"重复文档ID");
            assertFalse(doc.path("source").asText().isBlank());
            assertTrue(Files.isRegularFile(Path.of("src/main/java/org/example/timecoinweb",doc.path("source").asText())),"标注文档必须指向现有源码");
            String databaseId=knowledge.submit("eval-"+id,doc.path("title").asText(),doc.path("text").asText(),doc.path("audience").asInt(),"FAQ");
            knowledge.process(databaseId);databaseIds.put(databaseId,id);
            assertEquals("READY",jdbc.queryForObject("select state from rag_document where id=?",String.class,databaseId),"评测文档入库失败");
        }
        assertTrue(dataset.path("cases").size()>=20 && dataset.path("cases").size()<=30);
        Set<String> ids=new HashSet<>();List<Map<String,Object>> rows=new ArrayList<>();
        for(JsonNode sample:dataset.path("cases")) {
            assertTrue(ids.add(sample.path("id").asText()),"重复问题ID");
            boolean noEvidence="NO_EVIDENCE".equals(sample.path("expected").asText());
            assertTrue(noEvidence || "ANSWER".equals(sample.path("expected").asText()));
            assertEquals(noEvidence,sample.path("evidence").isEmpty());
            assertFalse(sample.path("expectedAnswer").asText().isBlank());
            for(JsonNode label:sample.path("evidence")) {
                JsonNode doc=documents.get(label.path("document").asText());assertNotNull(doc);
                assertFalse(label.path("quote").asText().isBlank());
                assertTrue(doc.path("text").asText().contains(label.path("quote").asText()));
                int audience=doc.path("audience").asInt(),role=sample.path("role").asInt();
                assertTrue(audience==0 || role==3 || audience==role,"正例必须有权访问标注证据");
            }
            Map<String,Object> row=new LinkedHashMap<>();rows.add(row);
            row.put("id",sample.path("id").asText());row.put("role",sample.path("role").asInt());
            row.put("question",sample.path("question").asText());row.put("expected",sample.path("expected").asText());
            row.put("expectedAnswer",sample.path("expectedAnswer").asText());
            long start=System.nanoTime();
            try {
                JsonNode result=json.valueToTree(answers.answer(sample.path("role").asInt(),sample.path("question").asText()));
                row.put("mode",result.path("mode").asText());row.put("reply",result.path("reply").asText());
                List<Map<String,Object>> citations=new ArrayList<>();boolean hit=false;
                for(JsonNode citation:result.path("citations")) {
                    String document=databaseIds.get(citation.path("documentId").asText()),quote=citation.path("quote").asText();
                    boolean grounded=document!=null && !quote.isBlank() && documents.get(document).path("text").asText().contains(quote);
                    citations.add(Map.of("document",document==null?"UNKNOWN":document,"quote",quote,"grounded",grounded));
                    for(JsonNode label:sample.path("evidence")) {
                        if(grounded && label.path("document").asText().equals(document) && quote.contains(label.path("quote").asText()))hit=true;
                    }
                }
                row.put("citations",citations);row.put("citationHit",hit);
            } catch(Exception failure) {
                row.put("mode","ERROR");row.put("errorType",failure.getClass().getSimpleName());
                row.put("citations",List.of());row.put("citationHit",false);
            }
            row.put("durationMs",java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
        }
        Map<String,Object> metrics=summarize(rows);
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("dataset",dataset.path("version").asText());report.put("datasetSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        report.put("generatedAt",Instant.now().toString());report.put("embeddingMode",embeddingMode);report.put("answerMode",answerMode);
        report.put("model","model".equals(answerMode)?env("AI_CHAT_MODEL",""):"none");report.put("embeddingKey",embedding.key());
        report.put("minimumScore",minimum);report.put("topK",5);report.put("cacheEnabled",false);
        report.put("metrics",metrics);report.put("cases",rows);
        Path output=Path.of("target/rag-evaluation-"+embeddingMode+"-"+answerMode+".json");
        Files.createDirectories(output.getParent());Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("RAG_EVALUATION "+json.writeValueAsString(metrics)+" report="+output);
        assertEquals(0L,metrics.get("errors"),"请求错误不能算作正确拒答，详见逐题报告");
        // 初版只建立可复现基线，不把测试通过当作质量阈值达标。
    }

    static Map<String,Object> summarize(List<Map<String,Object>> rows) {
        long positives=0,negatives=0,hits=0,trueNo=0,predictedNo=0,falseNo=0,errors=0;
        for(var row:rows) {
            boolean expectedNo="NO_EVIDENCE".equals(row.get("expected"));
            boolean actualNo="NO_EVIDENCE".equals(row.get("mode")) && ((List<?>)row.get("citations")).isEmpty();
            if(expectedNo)negatives++;else positives++;
            if("ERROR".equals(row.get("mode")))errors++;
            if(!expectedNo && Boolean.TRUE.equals(row.get("citationHit")))hits++;
            if(actualNo){predictedNo++;if(expectedNo)trueNo++;else falseNo++;}
        }
        Map<String,Object> metrics=new LinkedHashMap<>();
        metrics.put("total",rows.size());metrics.put("answerable",positives);metrics.put("unanswerable",negatives);
        metrics.put("citationHits",hits);metrics.put("citationHitRate",rate(hits,positives));
        metrics.put("correctNoEvidence",trueNo);metrics.put("predictedNoEvidence",predictedNo);
        metrics.put("noEvidenceRecall",rate(trueNo,negatives));metrics.put("noEvidencePrecision",rate(trueNo,predictedNo));
        metrics.put("falseAbstentions",falseNo);metrics.put("falseAbstentionRate",rate(falseNo,positives));
        metrics.put("evidenceDecisionAccuracy",rate(hits+trueNo,rows.size()));metrics.put("errors",errors);
        return metrics;
    }

    @Test void scoringDoesNotRewardAlwaysAbstainingOrCountErrorsAsCorrectRejections() {
        List<Map<String,Object>> rows=List.of(
                scoreRow("ANSWER","NO_EVIDENCE",false),scoreRow("ANSWER","NO_EVIDENCE",false),
                scoreRow("NO_EVIDENCE","NO_EVIDENCE",false));
        var metrics=summarize(rows);assertEquals(0.0,metrics.get("citationHitRate"));
        assertEquals(1.0,metrics.get("noEvidenceRecall"));assertEquals(1.0/3,metrics.get("noEvidencePrecision"));
        assertEquals(1.0/3,metrics.get("evidenceDecisionAccuracy"));
        metrics=summarize(List.of(scoreRow("NO_EVIDENCE","ERROR",false)));
        assertEquals(0.0,metrics.get("noEvidenceRecall"));assertNull(metrics.get("noEvidencePrecision"));assertEquals(1L,metrics.get("errors"));
        metrics=summarize(List.of(scoreRow("ANSWER","EXTRACTIVE",true),scoreRow("ANSWER","MODEL",false),scoreRow("NO_EVIDENCE","NO_EVIDENCE",false)));
        assertEquals(0.5,metrics.get("citationHitRate"));assertEquals(2.0/3,metrics.get("evidenceDecisionAccuracy"));
    }
    private static Map<String,Object> scoreRow(String expected,String actual,boolean hit){return Map.of("expected",expected,"mode",actual,"citationHit",hit,"citations",List.of());}
    private static Double rate(long numerator,long denominator){return denominator==0?null:(double)numerator/denominator;}
    private static void configure(Object target,String field,Object value){ReflectionTestUtils.setField(target,field,value);}
    private static String env(String name,String defaultValue){String value=System.getenv(name);return value==null || value.isBlank()?defaultValue:value;}
}
