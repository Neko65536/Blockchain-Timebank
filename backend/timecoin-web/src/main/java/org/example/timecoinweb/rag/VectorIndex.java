package org.example.timecoinweb.rag;

import lombok.Value;
import java.util.List;

/** 仅定义检索契约；尚无Milvus适配器。 */
public interface VectorIndex {
    List<Hit> search(long version, String embeddingKey, int role, double[] query, int topK, double minimumScore);
    @Value
    class Hit {
        String chunkId;
        String documentId;
        String title;
        String text;
        int ordinal;
        double score;
    }
}
