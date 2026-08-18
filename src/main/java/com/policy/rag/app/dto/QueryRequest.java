package com.policy.rag.app.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QueryRequest(
    @NotBlank(message = "Conversation ID cannot be empty")
    String conversationId,

    @NotBlank(message = "Question query cannot be empty")
    @Size(min = 3, max = 1000, message = "Query length must be between 3 and 1000 characters")
    String question
) {}
