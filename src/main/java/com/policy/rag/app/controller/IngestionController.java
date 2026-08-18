package com.policy.rag.app.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.policy.rag.app.dto.DeletionResponse;
import com.policy.rag.app.dto.IngestionResponse;
import com.policy.rag.app.service.DocumentIngestionService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/documents")
@RequiredArgsConstructor
public class IngestionController {

    private final DocumentIngestionService ingestionService;

    /**
     * Upload new PDF document(s)
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionResponse> uploadAndIngest(
            @RequestPart("files") List<MultipartFile> files) {
        
        IngestionResponse response = ingestionService.ingestUploadedFiles(files);
        return ResponseEntity.ok(response);
    }

    /**
     * Update an existing PDF policy document (Deletes old chunks and inserts new ones)
     */
    @PutMapping(value = "/update", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionResponse> updateDocument(
            @RequestPart("file") MultipartFile file) {
        
        IngestionResponse response = ingestionService.updateDocument(file);
        return ResponseEntity.ok(response);
    }

    /**
     * Delete an entire PDF document's vectors from the store by file name
     */
    @DeleteMapping
    public ResponseEntity<DeletionResponse> deleteDocument(@RequestParam("fileName") String fileName) {
        DeletionResponse response = ingestionService.deleteDocumentByFileName(fileName);
        return ResponseEntity.ok(response);
    }
}