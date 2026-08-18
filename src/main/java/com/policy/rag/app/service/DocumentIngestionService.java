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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.policy.rag.app.dto.DeletionResponse;
import com.policy.rag.app.dto.IngestionResponse;
import com.policy.rag.app.exception.DocumentProcessingException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
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
            var filterExpression = new FilterExpressionBuilder().eq("file_name", fileName).build();

            List<Document> matchingDocs = vectorStore.similaritySearch(
                    SearchRequest.query("*")
                            .withTopK(10000)
                            .withSimilarityThreshold(0.0)
                            .withFilterExpression(filterExpression)
            );

            if (matchingDocs.isEmpty()) {
                log.warn("No chunks found in Pgvector for file_name: {}", fileName);
                return new DeletionResponse(fileName, 0, "NOT_FOUND", "No existing vector chunks found for this document.");
            }

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
     * Batch Ingestion with Metadata-Enriched Chunking & Null-Safe Metadata Sanitization
     */
    public IngestionResponse ingestUploadedFiles(List<MultipartFile> files) {
        long startTime = System.currentTimeMillis();
        List<Document> allChunks = new ArrayList<>();
        int processedCount = 0;

        TokenTextSplitter splitter = new TokenTextSplitter(chunkSize, chunkOverlap, 5, 10000, true);

        for (MultipartFile file : files) {
            if (file.isEmpty() || file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase().endsWith(".pdf")) {
                log.warn("Skipping empty or non-PDF file: {}", file.getOriginalFilename());
                continue;
            }

            try {
                String filename = file.getOriginalFilename();
                log.info("Processing PDF file: {}", filename);

                // Use ByteArrayResource for safer stream handling across multi-page reads
                ByteArrayResource resource = new ByteArrayResource(file.getBytes());
                PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(
                        resource,
                        PdfDocumentReaderConfig.builder()
                                .withPageTopMargin(0)
                                .withPageBottomMargin(0)
                                .build()
                );

                List<Document> rawDocuments = pdfReader.get();

                // Sanitize metadata map to prevent NullPointerException inside TextSplitter
                List<Document> sanitizedDocuments = rawDocuments.stream()
                        .map(doc -> {
                            Map<String, Object> cleanMetadata = new HashMap<>();
                            if (doc.getMetadata() != null) {
                                doc.getMetadata().forEach((k, v) -> {
                                    if (k != null && v != null) {
                                        cleanMetadata.put(k, v);
                                    }
                                });
                            }
                            return new Document(doc.getId(), doc.getContent(), cleanMetadata);
                        })
                        .toList();

                // Perform safe chunking
                List<Document> chunks = splitter.apply(sanitizedDocuments);

                // Enrich every chunk with document metadata
                long now = System.currentTimeMillis();
                chunks.forEach(chunk -> {
                    chunk.getMetadata().put("file_name", filename);
                    chunk.getMetadata().put("ingested_at", now);
                });

                allChunks.addAll(chunks);
                processedCount++;

            } catch (Exception e) {
                log.error("Failed to process uploaded file {}: ", file.getOriginalFilename(), e);
                throw new DocumentProcessingException("Error processing PDF: " + file.getOriginalFilename());
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