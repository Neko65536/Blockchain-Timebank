package org.example.timecoinweb.rag;
import com.fasterxml.jackson.databind.*;
import org.example.timecoinweb.service.AiChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.web3j.crypto.Hash;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class RagAnswerService {
    private final KnowledgeService knowledge; private final EmbeddingService embedding; private final VectorIndex index;
    private final AiChatService model; private final ObjectMapper json; private final StringRedisTemplate redis;
    @Value("${app.redis-enabled:false}") private boolean cached;
    @Value("${ai.rag.answer-mode:model}") private String mode;
    @Value("${ai.rag.minimum-score:0.2}") private double minimum;
    private static final DefaultRedisScript<Long> UNLOCK=new DefaultRedisScript<>("if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end",Long.class);
    public RagAnswerService(KnowledgeService k,EmbeddingService e,VectorIndex i,AiChatService m,ObjectMapper j,StringRedisTemplate r) {
        knowledge=k;embedding=e;index=i;model=m;json=j;redis=r;
    }
    public Map<String,Object> answer(int role,String question) {
        if(role<1 || role>3 || question==null || question.isBlank() || question.length()>1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"问题不能为空且最多1000字");
        String q=question.trim(), roleName=role==1?"old":"volunteer";
        String guard=model.guardrailReply(roleName,q);
        if(guard!=null) return response(guard,"GUARDRAIL",Collections.emptyList());
        long version=knowledge.version();
        String key="timebank:rag:v1:"+Hash.sha3String(version+":"+role+":"+embedding.key()+":"+model.groundingKey()+":"+mode+":"+minimum+":"+q);
        String token=UUID.randomUUID().toString(); boolean locked=false;
        try {
            if(cached) {
                String value=redis.opsForValue().get(key);
                if(value!=null) { checkVersion(version); return json.readValue(value,Map.class); }
                locked=Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key+":lock",token,Duration.ofSeconds(150)));
                if(!locked) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"相同问题正在生成，请稍后重试");
                // 加锁后再读，防止前一请求刚好完成造成重复生成。
                value=redis.opsForValue().get(key);
                if(value!=null) { checkVersion(version); return json.readValue(value,Map.class); }
            }
            List<VectorIndex.Hit> hits=index.search(version,embedding.key(),role,embedding.embed(List.of(q)).get(0),5,minimum);
            Map<String,Object> result;
            if(hits.isEmpty()) result=response("知识库暂无足够依据，请补充问题或联系社区工作人员核实。","NO_EVIDENCE",List.of());
            else if("extractive".equals(mode)) {
                VectorIndex.Hit hit=hits.get(0);
                result=response(hit.getText(),"EXTRACTIVE",List.of(citation(hit,hit.getText())));
            } else if("model".equals(mode)) {
                result=validate(model.groundedAnswer(q,json.writeValueAsString(hits)),hits);
                String outputGuard=model.guardrailReplyForModelOutput(roleName,(String)result.get("reply"));
                if(outputGuard!=null) result=response(outputGuard,"GUARDRAIL",List.of());
            } else throw new IllegalStateException("问答模式无效");
            checkVersion(version);
            if(cached) redis.opsForValue().set(key,json.writeValueAsString(result),Duration.ofSeconds("NO_EVIDENCE".equals(result.get("mode"))?10:120+ThreadLocalRandom.current().nextInt(31)));
            return result;
        } catch(ResponseStatusException e) { throw e; }
        catch(Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"知识问答暂不可用，请检查模型或缓存服务",e); }
        finally { if(locked) { try { redis.execute(UNLOCK,List.of(key+":lock"),token); } catch(Exception ignored) { /* 有界租约自动回收 */ } } }
    }
    private void checkVersion(long version) { if(knowledge.version()!=version) throw new ResponseStatusException(HttpStatus.CONFLICT,"知识库已更新，请重新提问"); }
    Map<String,Object> validate(String raw,List<VectorIndex.Hit> hits) throws Exception {
        JsonNode root=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
        JsonNode answer=root.path("answer"), refs=root.path("citations");
        if(!answer.isTextual() || answer.asText().isBlank() || answer.asText().length()>4000 || !refs.isArray() || refs.size()==0 || refs.size()>hits.size())
            throw new IllegalStateException("模型没有提供有效依据");
        List<Map<String,Object>> citations=new ArrayList<>(); Set<String> used=new HashSet<>();
        for(JsonNode ref:refs) {
            String id=ref.path("chunkId").asText(), quote=ref.path("quote").asText();
            VectorIndex.Hit hit=hits.stream().filter(h->h.getChunkId().equals(id)).findFirst().orElseThrow(()->new IllegalStateException("引用不在检索范围"));
            if(!used.add(id) || quote.length()<12 || quote.length()>500 || !hit.getText().contains(quote)) throw new IllegalStateException("引用不是原文");
            citations.add(citation(hit,quote));
        }
        return response(answer.asText(),"MODEL",citations);
    }
    private Map<String,Object> citation(VectorIndex.Hit hit,String quote) { return Map.of("chunkId",hit.getChunkId(),"documentId",hit.getDocumentId(),"title",hit.getTitle(),"ordinal",hit.getOrdinal(),"quote",quote); }
    private Map<String,Object> response(String reply,String mode,List<?> citations) { return Map.of("reply",reply,"mode",mode,"citations",citations); }
}
