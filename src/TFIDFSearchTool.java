import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.*;

public class TFIDFSearchTool implements AutoCloseable {

    public record SearchHit(String text, double similarity) {
        @Override
        public String toString() {
            return "SearchHit{text='%s', similarity=%.4f}".formatted(text, similarity);
        }
    }

    private final Directory directory;
    private final IndexWriter writer;
    private final FieldType fieldType;
    private final Analyzer analyzer;
    private final Map<Integer, String> docMap;
    private int currentDocId;

    // 新增字段来缓存构建结果
    private final Map<String, Integer> vocabulary;
    private final Map<Integer, double[]> documentVectors;
    private boolean needRebuild;  // 标记是否需要重建向量

    public TFIDFSearchTool() throws IOException {
        this.analyzer = new StandardAnalyzer();
        this.directory = new ByteBuffersDirectory();
        this.docMap = new HashMap<>();
        this.vocabulary = new HashMap<>();
        this.documentVectors = new HashMap<>();
        this.currentDocId = 0;
        this.needRebuild = false;

        var config = new IndexWriterConfig(analyzer);
        this.writer = new IndexWriter(directory, config);

        this.fieldType = new FieldType();
        fieldType.setIndexOptions(IndexOptions.DOCS_AND_FREQS);
        fieldType.setStored(true);
        fieldType.setStoreTermVectors(true);
        fieldType.setTokenized(true);
    }

    public void addDoc(String text) throws IOException {
        var doc = new Document();
        doc.add(new Field("content", text, fieldType));
        writer.addDocument(doc);
        writer.commit();
        docMap.put(currentDocId, text);

        // 更新词汇表和文档向量
        updateVocabularyAndVectors(text, currentDocId);

        currentDocId++;
    }

    private void updateVocabularyAndVectors(String text, int docId) throws IOException {
        // 创建临时索引来获取文档的词项统计
        try (var tempDir = new ByteBuffersDirectory();
             var tempWriter = new IndexWriter(tempDir, new IndexWriterConfig(analyzer))) {

            var tempDoc = new Document();
            tempDoc.add(new Field("content", text, fieldType));
            tempWriter.addDocument(tempDoc);
            tempWriter.commit();

            try (var reader = DirectoryReader.open(tempWriter)) {
                var terms = reader.getTermVector(0, "content");
                if (terms == null) return;

                // 更新词汇表
                var termsEnum = terms.iterator();
                BytesRef term;
                while ((term = termsEnum.next()) != null) {
                    var termText = term.utf8ToString();
                    vocabulary.putIfAbsent(termText, vocabulary.size());
                }

                // 重新计算所有文档向量
                // 注意：这里我们需要重新计算所有向量，因为IDF值可能发生变化
                needRebuild = true;
            }
        }
    }

    private void rebuildDocumentVectorsIfNeeded() throws IOException {
        if (!needRebuild) return;

        try (var reader = DirectoryReader.open(writer)) {
            documentVectors.clear();
            for (int i = 0; i < reader.maxDoc(); i++) {
                documentVectors.put(i, getTermVector(reader, i, vocabulary));
            }
        }
        needRebuild = false;
    }

    public List<SearchHit> search(String queryText, int limit) throws IOException {
        // 确保文档向量是最新的
        rebuildDocumentVectorsIfNeeded();

        // 构建查询向量
        var queryVector = buildTFIDFVector(queryText, vocabulary);
        if (queryVector == null) return List.of();

        return documentVectors.entrySet().stream()
                .map(entry -> new SearchHit(
                        docMap.get(entry.getKey()),
                        cosineSimilarity(queryVector, entry.getValue())
                ))
                .sorted(Comparator.comparingDouble(SearchHit::similarity).reversed())
                .limit(limit)
                .toList();
    }

    public List<String> getAllDocs() {
        return new ArrayList<>(docMap.values());
    }

    public List<SearchHit> search(String queryText) throws IOException {
        return search(queryText, Integer.MAX_VALUE);
    }

    private Map<String, Integer> buildVocabulary() throws IOException {
        var vocabulary = new HashMap<String, Integer>();
        try (var reader = DirectoryReader.open(writer)) {
            // 遍历所有文档收集词汇
            for (int i = 0; i < reader.maxDoc(); i++) {
                var terms = reader.getTermVector(i, "content");
                if (terms != null) {
                    var termsEnum = terms.iterator();
                    BytesRef term;
                    while ((term = termsEnum.next()) != null) {
                        vocabulary.putIfAbsent(term.utf8ToString(), vocabulary.size());
                    }
                }
            }
        }
        return vocabulary;
    }

    private double[] buildTFIDFVector(String text, Map<String, Integer> vocabulary) throws IOException {
        try (var tempDir = new ByteBuffersDirectory();
             var tempWriter = new IndexWriter(tempDir, new IndexWriterConfig(analyzer))) {

            var doc = new Document();
            doc.add(new Field("content", text, fieldType));
            tempWriter.addDocument(doc);
            tempWriter.commit();

            try (var reader = DirectoryReader.open(tempWriter)) {
                return getTermVector(reader, 0, vocabulary);
            }
        }
    }

    private Map<Integer, double[]> getAllDocumentVectors(Map<String, Integer> vocabulary) throws IOException {
        var vectors = new HashMap<Integer, double[]>();
        try (var reader = DirectoryReader.open(writer)) {
            for (int i = 0; i < reader.maxDoc(); i++) {
                var vector = getTermVector(reader, i, vocabulary);
                if (vector != null) {
                    vectors.put(i, vector);
                }
            }
        }
        return vectors;
    }

    private double[] getTermVector(IndexReader reader, int docId, Map<String, Integer> vocabulary) throws IOException {
        var terms = reader.getTermVector(docId, "content");
        if (terms == null) return null;

        // 使用全局词汇表的维度初始化向量
        var vector = new double[vocabulary.size()];

        // 计算文档中每个词的TF
        var termsEnum = terms.iterator();
        BytesRef term;
        while ((term = termsEnum.next()) != null) {
            var termText = term.utf8ToString();
            var idx = vocabulary.getOrDefault(termText, -1);
            if (idx != -1) {  // 词在词汇表中存在
                // TF计算
                var tf = (double) termsEnum.totalTermFreq() / terms.size();

                // IDF计算
                var idf = Math.log((double) reader.numDocs() /
                        (reader.docFreq(new Term("content", term)) + 1)) + 1.0;

                vector[idx] = tf * idf;
            }
        }

        return vector;
    }

    private double cosineSimilarity(double[] vector1, double[] vector2) {
        if (vector1.length != vector2.length) {
            throw new IllegalArgumentException("Vectors must have the same dimension: " +
                    "vector1.length=" + vector1.length + ", vector2.length=" + vector2.length);
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < vector1.length; i++) {
            dotProduct += vector1[i] * vector2[i];
            norm1 += vector1[i] * vector1[i];
            norm2 += vector2[i] * vector2[i];
        }

        if (norm1 == 0.0 || norm2 == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    @Override
    public void close() throws IOException {
        writer.close();
        directory.close();
    }

    // 测试代码
    public static void main(String[] args) throws IOException {
        try (var tool = new TFIDFSearchTool()) {
            // 添加示例文档
            tool.addDoc("[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.8.1:compile");
            tool.addDoc("[ERROR] Could not resolve dependencies for project com.example:demo:jar:1.0-SNAPSHOT");
            tool.addDoc("[ERROR] The plugin org.springframework.boot:spring-boot-maven-plugin:2.5.0 requires Maven version 3.6.0");

            var query = "[ERROR] Failed to execute goal maven-compiler-plugin compile";

            // 搜索所有相似文档
            System.out.println("All similar documents:");
            tool.search(query).forEach(hit ->
                    System.out.printf("Similarity: %.4f - %s%n", hit.similarity(), hit.text()));

            // 只搜索最相似的2个文档
            System.out.println("\nTop 2 similar documents:");
            tool.search(query, 2).forEach(hit ->
                    System.out.printf("Similarity: %.4f - %s%n", hit.similarity(), hit.text()));
        }
    }
}
