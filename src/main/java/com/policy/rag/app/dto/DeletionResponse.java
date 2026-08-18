package com.policy.rag.app.dto;

public record DeletionResponse(
    String fileName,
    int deletedChunksCount,
    String status,
    String message
) {}