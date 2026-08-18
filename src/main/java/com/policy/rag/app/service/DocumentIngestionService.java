package com.policy.rag.app.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.policy.rag.app.dto.IngestionResponse;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final VectorStore vectorStore;

    @Value("${rag.ingestion.chunk-size}")
    private int chunkSize;

    @Value("${rag.ingestion.chunk-overlap}")
    private int chunkOverlap;

    public IngestionResponse ingestPdfDirectory(String directoryPath) {
        long startTime = System.currentTimeMillis();
        Path path = Paths.get(directoryPath);

        if (!Files.exists(path) || !Files.isDirectory(path)) {
            throw new DocumentProcessingException("Invalid directory path: " + directoryPath);
        }

        List<Document> allChunks = new ArrayList<>();
        int fileCount = 0;

        try (Stream<Path> paths = Files.walk(path)) {
            List<Path> pdfFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                    .toList();

            fileCount = pdfFiles.size();
            log.info("Starting ingestion for {} PDF documents from path {}", fileCount, directoryPath);

            TokenTextSplitter splitter = new TokenTextSplitter(chunkSize, chunkOverlap, 5, 10000, true);

            for (Path pdfPath : pdfFiles) {
                log.debug("Processing file: {}", pdfPath.getFileName());
                
                PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(
                        pdfPath.toUri().toString(),
                        PdfDocumentReaderConfig.builder()
                                .withPageTopMargin(0)
                                .withPageBottomMargin(0)
                                .build()
                );

                List<Document> documents = pdfReader.get();
                List<Document> chunks = splitter.apply(documents);
                
                // Enhance metadata
                chunks.forEach(chunk -> 
                    chunk.getMetadata().put("file_name", pdfPath.getFileName().toString())
                );
                
                allChunks.addAll(chunks);
            }

            log.info("Batch writing {} total vector embeddings into Pgvector database...", allChunks.size());
            vectorStore.accept(allChunks);

        } catch (Exception e) {
            log.error("Ingestion failed due to unhandled processing error: ", e);
            throw new DocumentProcessingException("Failed to process PDF documents: " + e.getMessage());
        }

        long duration = System.currentTimeMillis() - startTime;
        return new IngestionResponse(fileCount, allChunks.size(), duration, "SUCCESS");
    }
}
