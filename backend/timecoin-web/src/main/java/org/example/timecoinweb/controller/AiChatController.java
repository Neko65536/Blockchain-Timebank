package org.example.timecoinweb.controller;

import org.example.pojo.Result;
import org.example.timecoinweb.service.AiChatService;
import org.example.timecoinweb.rag.AiTaskExecutor;
import org.example.timecoinweb.rag.RagAnswerService;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/users")
public class AiChatController {
    @Autowired
    private AiChatService aiChatService;
    @Autowired private AiTaskExecutor executor;
    @Autowired private RagAnswerService rag;

    @PostMapping("/old/ai/chat")
    public DeferredResult<Result> oldChat(@RequestBody AiChatRequest request) {
        return executor.submit(() -> rag.answer(1, request.getMessage()));
    }

    @PostMapping("/vol/ai/chat")
    public DeferredResult<Result> volunteerChat(@RequestBody AiChatRequest request) {
        return executor.submit(() -> rag.answer(2, request.getMessage()));
    }

    @PostMapping("/old/ai/activity-draft")
    public DeferredResult<Result> oldActivityDraft(@RequestBody AiActivityRequest request) {
        return executor.submit(() -> aiChatService.generateActivityDraft(request == null ? null : request.getText()));
    }

    @PostMapping("/vol/ai/activity-prepare")
    public DeferredResult<Result> volunteerActivityPrepare(@RequestBody AiActivityRequest request) {
        return executor.submit(() -> aiChatService.generateVolunteerPreparation(request == null ? null : request.getActivity()));
    }

    @PostMapping("/administrator/ai/activity-review")
    public DeferredResult<Result> administratorActivityReview(@RequestBody AiActivityRequest request) {
        return executor.submit(() -> aiChatService.generateActivityReview(request == null ? null : request.getActivity()));
    }

    public static class AiChatRequest {
        private String message;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class AiActivityRequest {
        private String text;
        private Map<String, Object> activity;

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public Map<String, Object> getActivity() {
            return activity;
        }

        public void setActivity(Map<String, Object> activity) {
            this.activity = activity;
        }
    }
}
