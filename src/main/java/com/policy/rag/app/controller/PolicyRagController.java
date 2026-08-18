package com.policy.rag.app.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.policy.rag.app.dto.QueryRequest;
import com.policy.rag.app.dto.QueryResponse;
import com.policy.rag.app.service.PolicyRagService;

@RestController
@RequestMapping("/api/v1/policy")
// @RequiredArgsConstructor
public class PolicyRagController {

    private final PolicyRagService ragService;

    public PolicyRagController(PolicyRagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/ask")
    public ResponseEntity<QueryResponse> askQuestion(@Valid @RequestBody QueryRequest request) {
        QueryResponse response = ragService.answerQuestion(request);
        return ResponseEntity.ok(response);
    }
}
