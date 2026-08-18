package com.policy.rag.app.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.policy.rag.app.dto.DeletionResponse;
import com.policy.rag.app.dto.IngestionResponse;
import com.policy.rag.app.exception.DocumentProcessingException;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final VectorStore vectorStore;

    @Value("${rag.ingestion.chunk-size:800}")
    private int chunkSize;

    @Value("${rag.ingestion.chunk-overlap:150}")
    private int chunkOverlap;

    /**
     * Delete all vector chunks associated with a specific file name using metadata filtering.
     */
    public DeletionResponse deleteDocumentByFileName(String fileName) {
        log.info("Initiating deletion of all vector chunks for document: {}", fileName);

        try {
            // 1. Build Metadata Filter Expression
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            var filterExpression = b.eq("file_name", fileName).build();

            // 2. Retrieve all chunk IDs matching the file_name filter
            List<Document> matchingDocs = vectorStore.similaritySearch(
                    SearchRequest.query("*")
                            .withTopK(10000) // retrieve all chunks for this file
                            .withSimilarityThreshold(0.0) // bypass distance check to match metadata only
                            .withFilterExpression(filterExpression)
            );

            if (matchingDocs.isEmpty()) {
                log.warn("No chunks found in Pgvector for file_name: {}", fileName);
                return new DeletionResponse(fileName, 0, "NOT_FOUND", "No existing vector chunks found for this document.");
            }

            // 3. Extract IDs and purge from Pgvector
            List<String> documentIds = matchingDocs.stream()
                    .map(Document::getId)
                    .toList();

            vectorStore.delete(documentIds);

            log.info("Successfully deleted {} vector chunks for document: {}", documentIds.size(), fileName);
            return new DeletionResponse(fileName, documentIds.size(), "SUCCESS", "Document vectors purged successfully.");

        } catch (Exception e) {
            log.error("Failed to delete vectors for document {}: ", fileName, e);
            throw new DocumentProcessingException("Error deleting document vectors: " + e.getMessage());
        }
    }

    /**
     * Atomically update an existing document: Delete existing chunks first, then insert new ones.
     */
    @Transactional
    public IngestionResponse updateDocument(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        log.info("Updating document: {}", fileName);

        // Step 1: Remove existing chunks to prevent stale context
        deleteDocumentByFileName(fileName);

        // Step 2: Ingest the new version
        return ingestUploadedFiles(List.of(file));
    }

    /**
     * Batch Ingestion with Metadata Enriched Chunking
     */
    public IngestionResponse ingestUploadedFiles(List<MultipartFile> files) {
        long startTime = System.currentTimeMillis();
        List<Document> allChunks = new ArrayList<>();
        int processedCount = 0;

        TokenTextSplitter splitter = new TokenTextSplitter(chunkSize, chunkOverlap, 5, 10000, true);

        for (MultipartFile file : files) {
            if (file.isEmpty() || !file.getOriginalFilename().toLowerCase().endsWith(".pdf")) {
                log.warn("Skipping empty or non-PDF file: {}", file.getOriginalFilename());
                continue;
            }

            try {
                String filename = file.getOriginalFilename();
                log.info("Processing PDF file: {}", filename);

                InputStreamResource resource = new InputStreamResource(file.getInputStream());
                PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(
                        resource,
                        PdfDocumentReaderConfig.builder()
                                .withPageTopMargin(0)
                                .withPageBottomMargin(0)
                                .build()
                );

                List<Document> documents = pdfReader.get();
                List<Document> chunks = splitter.apply(documents);

                // Enrich every chunk with document metadata
                chunks.forEach(chunk -> {
                    chunk.getMetadata().put("file_name", filename);
                    chunk.getMetadata().put("ingested_at", System.currentTimeMillis());
                });

                allChunks.addAll(chunks);
                processedCount++;

            } catch (Exception e) {
                log.error("Failed to process uploaded file {}: ", file.getOriginalFilename(), e);
                throw new DocumentProcessingException("Error reading PDF stream: " + file.getOriginalFilename());
            }
        }

        if (!allChunks.isEmpty()) {
            log.info("Persisting {} vector chunks into Pgvector...", allChunks.size());
            vectorStore.accept(allChunks);
        }

        long duration = System.currentTimeMillis() - startTime;
        return new IngestionResponse(processedCount, allChunks.size(), duration, "SUCCESS");
    }
}