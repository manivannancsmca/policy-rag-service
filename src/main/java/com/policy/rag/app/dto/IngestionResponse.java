package com.policy.rag.app.dto;

public record IngestionResponse(
    int totalFilesProcessed,
    int totalChunksCreated,
    long durationMs,
    String status
) {}