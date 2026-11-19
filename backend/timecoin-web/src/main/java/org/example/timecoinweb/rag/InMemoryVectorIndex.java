package org.example.timecoinweb.rag;

import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** 向量持久化在DB，实例按知识库版本重建内存快照；小规模全量余弦检索，不是ANN。 */
@Component
public class InMemoryVectorIndex implements VectorIndex {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private volatile Snapshot snapshot = new Snapshot(-1, "", Collections.emptyList());
    public InMemoryVectorIndex(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    @Override
    public List<Hit> search(long version, String key, int role, double[] query, int topK, double minimum) {
        Snapshot current = snapshot;
        if (current.version != version || !current.key.equals(key)) current = reload(version, key);
        List<Hit> result = new ArrayList<>();
        for (Entry entry : current.entries) {
            if (entry.audience != 0 && role != 3 && entry.audience != role) continue;
            if (entry.vector.length != query.length) throw new IllegalStateException("查询向量与索引维度不匹配，请重新入库");
            double score = 0;
            for (int i = 0; i < query.length; i++) score += query[i] * entry.vector[i];
            if (score >= minimum) result.add(new Hit(entry.id, entry.document, entry.title, entry.text, entry.ordinal, score));
        }
        result.sort(Comparator.comparingDouble(Hit::getScore).reversed().thenComparing(Hit::getChunkId));
        return new ArrayList<>(result.subList(0, Math.min(topK, result.size())));
    }
    private synchronized Snapshot reload(long version, String key) {
        if (snapshot.version == version && snapshot.key.equals(key)) return snapshot;
        List<Entry> entries = jdbc.query("select c.*,d.title,d.audience from rag_chunk c join rag_document d on d.id=c.document_id "
                + "where d.state='READY' and d.embedding_key=? order by c.id limit 16001", (rs, row) -> {
            try { return new Entry(rs.getString("id"), rs.getString("document_id"), rs.getString("title"), rs.getString("content"),
                    rs.getInt("ordinal_value"), rs.getInt("audience"), json.readValue(rs.getString("vector_json"), double[].class)); }
            catch (Exception e) { throw new IllegalStateException("知识库向量损坏", e); }
        }, key);
        if (entries.size() > 16000) throw new IllegalStateException("知识库超出本地索引容量，请切换检索实现");
        snapshot = new Snapshot(version, key, entries);
        return snapshot;
    }
    private static class Snapshot {
        final long version; final String key; final List<Entry> entries;
        Snapshot(long version, String key, List<Entry> entries) { this.version = version; this.key = key; this.entries = entries; }
    }
    private static class Entry {
        final String id, document, title, text; final int ordinal, audience; final double[] vector;
        Entry(String id, String document, String title, String text, int ordinal, int audience, double[] vector) {
            this.id=id; this.document=document; this.title=title; this.text=text; this.ordinal=ordinal; this.audience=audience; this.vector=vector;
        }
    }
}
