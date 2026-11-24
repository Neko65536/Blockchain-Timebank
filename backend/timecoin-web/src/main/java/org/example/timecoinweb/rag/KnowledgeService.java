package org.example.timecoinweb.rag;

import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.web3j.crypto.Hash;

@Service
public class KnowledgeService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EmbeddingService embedding;
    private final ObjectMapper json;
    /** MQ 关闭时同步处理（本地演示无需 RabbitMQ）；开启时走 RagMessaging 异步。 */
    @Value("${ai.rag.mq-enabled:true}") private boolean mqEnabled;
    public KnowledgeService(JdbcTemplate jdbc, PlatformTransactionManager manager, EmbeddingService embedding, ObjectMapper json) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.embedding=embedding; this.json=json;
    }
    public long version() { return jdbc.queryForObject("select version_value from rag_revision where id=1", Long.class); }
    public List<Map<String,Object>> list() {
        return jdbc.queryForList("select id,title,audience,source_type,state,attempts,last_error,created_at from rag_document where state<>'DELETED' order by created_at desc limit 500");
    }
    public String submit(String requestKey, String title, String content, int audience, String type) {
        if (requestKey==null || requestKey.isBlank() || requestKey.length()>128 || title==null || title.isBlank() || title.length()>200
                || content==null || content.isBlank() || content.length()>16000 || audience<0 || audience>3
                || type==null || !Set.of("TEXT","FAQ","NOTICE").contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"知识文档参数不合法");
        String key=Hash.sha3String(requestKey), hash=Hash.sha3String(title+"\u0000"+content+"\u0000"+audience+"\u0000"+type);
        String id=tx.execute(status -> {
            jdbc.queryForObject("select version_value from rag_revision where id=1 for update",Long.class);
            List<Map<String,Object>> existing=jdbc.queryForList("select id,payload_hash from rag_document where request_key=?",key);
            if (!existing.isEmpty()) {
                if (!hash.equals(existing.get(0).get("payload_hash"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"幂等键已用于其他文档");
                return (String)existing.get(0).get("id");
            }
            if (jdbc.queryForObject("select count(*) from rag_document where state<>'DELETED'",Integer.class)>=500)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"本地知识库最多支持500份文档");
            String docId=UUID.randomUUID().toString();
            jdbc.update("insert into rag_document(id,request_key,payload_hash,title,content,audience,source_type,embedding_key,state) values(?,?,?,?,?,?,?,?,'PENDING')",
                    docId,key,hash,title.trim(),content,audience,type,embedding.key());
            if (mqEnabled) {
                jdbc.update("insert into rag_outbox(document_id) values(?)",docId);
            }
            return docId;
        });
        if (!mqEnabled) {
            process(id);
        }
        return id;
    }
    public void disable(String id) {
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("select version_value from rag_revision where id=1 for update",Long.class);
            int changed=jdbc.update("update rag_document set state='DELETED',lease_token=null,lease_until=null where id=? and state<>'DELETED'",id);
            if (changed>0) { jdbc.update("delete from rag_chunk where document_id=?",id); jdbc.update("update rag_revision set version_value=version_value+1 where id=1"); }
        });
    }
    public void retry(String id) {
        int n=jdbc.update("update rag_document set state='RETRY',attempts=0,next_attempt=CURRENT_TIMESTAMP,last_error=null where id=? and state='REVIEW' and embedding_key=?",id,embedding.key());
        if(n==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"仅可重试当前Embedding配置下的待处理文档");
    }
    public void process(String id) {
        String token=UUID.randomUUID().toString();
        int claimed=jdbc.update("update rag_document set state='PROCESSING',lease_token=?,lease_until=?,attempts=attempts+1 where id=? and "
                + "((state in ('PENDING','RETRY') and next_attempt<=CURRENT_TIMESTAMP) or (state='PROCESSING' and lease_until<CURRENT_TIMESTAMP))",
                token,Timestamp.from(Instant.now().plusSeconds(180)),id);
        if(claimed==0) return;
        Map<String,Object> doc=jdbc.queryForMap("select * from rag_document where id=?",id);
        try {
            if(!embedding.key().equals(doc.get("embedding_key"))) throw new IllegalStateException("Embedding配置已变更");
            List<String> chunks=split((String)doc.get("content"));
            List<double[]> vectors=new ArrayList<>();
            for(int start=0;start<chunks.size();start+=16) vectors.addAll(embedding.embed(chunks.subList(start,Math.min(start+16,chunks.size()))));
            List<String> encoded=new ArrayList<>();
            for(double[] vector:vectors) encoded.add(json.writeValueAsString(vector));
            tx.executeWithoutResult(status -> {
                // 统一锁序：版本行→文档行，与禁用操作一致，且提交时校验租约栅栏。
                jdbc.queryForObject("select version_value from rag_revision where id=1 for update",Long.class);
                int owned=jdbc.update("update rag_document set state='READY',lease_token=null,lease_until=null,last_error=null where id=? and state='PROCESSING' and lease_token=? and lease_until>CURRENT_TIMESTAMP",id,token);
                if(owned==0) return;
                jdbc.update("delete from rag_chunk where document_id=?",id);
                for(int i=0;i<chunks.size();i++) jdbc.update("insert into rag_chunk(id,document_id,ordinal_value,content,vector_json) values(?,?,?,?,?)",id+":"+i,id,i,chunks.get(i),encoded.get(i));
                jdbc.update("update rag_revision set version_value=version_value+1 where id=1");
            });
        } catch(Exception failed) {
            // 不保存模型响应、凭据或内部异常文本。
            int attempts=((Number)doc.get("attempts")).intValue();
            jdbc.update("update rag_document set state=?,lease_token=null,lease_until=null,last_error=?,next_attempt=? where id=? and state='PROCESSING' and lease_token=?",
                    attempts>=5?"REVIEW":"RETRY","索引失败，请检查模型配置与服务状态",Timestamp.from(Instant.now().plusSeconds(Math.min(300,5L*attempts))),id,token);
        }
    }
    static List<String> split(String text) {
        int[] points=text.codePoints().toArray(); List<String> chunks=new ArrayList<>();
        for(int start=0;start<points.length;start+=520) {
            int length=Math.min(600,points.length-start); chunks.add(new String(points,start,length));
            if(start+length==points.length) break;
        }
        return chunks;
    }
}
