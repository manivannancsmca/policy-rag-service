package com.policy.rag.app.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.policy.rag.app.dto.IngestionResponse;
import com.policy.rag.app.service.DocumentIngestionService;

@RestController
@RequestMapping("/api/v1/admin")
public class IngestionController {

    private final DocumentIngestionService ingestionService;

    public IngestionController(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<IngestionResponse> ingestDocuments(@RequestParam("path") String directoryPath) {
        IngestionResponse response = ingestionService.ingestPdfDirectory(directoryPath);
        return ResponseEntity.ok(response);
    }
}