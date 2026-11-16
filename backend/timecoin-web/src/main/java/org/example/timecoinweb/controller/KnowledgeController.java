package org.example.timecoinweb.controller;
import lombok.Data;
import org.example.pojo.Result;
import org.example.timecoinweb.rag.KnowledgeService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/administrator/knowledge")
public class KnowledgeController {
    private final KnowledgeService knowledge;
    public KnowledgeController(KnowledgeService knowledge) { this.knowledge=knowledge; }
    @GetMapping public Result list() { return Result.success(knowledge.list()); }
    @PostMapping public Result submit(@RequestHeader("Idempotency-Key") String key,@RequestBody Document document) {
        return Result.success(knowledge.submit(key,document.title,document.content,document.audience,document.type));
    }
    @DeleteMapping("/{id}") public Result disable(@PathVariable String id) { knowledge.disable(id);return Result.success(); }
    @PostMapping("/{id}/retry") public Result retry(@PathVariable String id) { knowledge.retry(id);return Result.success(); }
    @Data public static class Document { private String title;private String content;private int audience;private String type; }
}
