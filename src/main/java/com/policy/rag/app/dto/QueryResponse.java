package com.policy.rag.app.dto;

import java.util.List;

public record QueryResponse(
    String conversationId,
    String question,
    String answer,
    boolean informationFound,
    List<Citation> citations
) {
    public record Citation(String sourceDocument, int pageNumber) {}
}
