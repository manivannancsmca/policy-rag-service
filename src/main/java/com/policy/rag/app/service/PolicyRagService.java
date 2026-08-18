package com.policy.rag.app.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.policy.rag.app.dto.QueryRequest;
import com.policy.rag.app.dto.QueryResponse;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolicyRagService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    @Value("${rag.retrieval.similarity-threshold}")
    private double similarityThreshold;

    @Value("${rag.retrieval.top-k}")
    private int topK;

    private static final String NOT_FOUND_TOKEN = "INFORMATION_NOT_AVAILABLE";

    private static final String SYSTEM_PROMPT = """
        You are an official enterprise AI policy assistant. 
        Answer the employee's question strictly using the provided Context documents below.
        
        Strict Constraints:
        1. If the exact information needed to answer the question is not present in the provided context, respond ONLY with "INFORMATION_NOT_AVAILABLE".
        2. Do NOT use outside knowledge or make assumptions beyond the text provided.
        3. Keep your response professional, precise, and concise.

        Context:
        {context}
        """;

    public QueryResponse answerQuestion(QueryRequest request) {
        log.info("Executing RAG search for query: '{}' under conversation [{}]", 
                request.question(), request.conversationId());

        // 1. Vector Search with Thresholding
        List<Document> similarDocuments = vectorStore.similaritySearch(
                SearchRequest.query(request.question())
                        .withTopK(topK)
                        .withSimilarityThreshold(similarityThreshold)
        );

        // 2. Short-circuit if no relevant contexts survive distance thresholding
        if (similarDocuments.isEmpty()) {
            log.warn("No context chunks matched similarity threshold >= {} for query", similarityThreshold);
            return createFallbackResponse(request);
        }

        // 3. Assemble Grounding Context Block
        String contextBlock = similarDocuments.stream()
                .map(doc -> String.format("[Source: %s | Page: %s]\n%s",
                        doc.getMetadata().getOrDefault("file_name", "Unknown"),
                        doc.getMetadata().getOrDefault("page_number", "N/A"),
                        doc.getContent()))
                .collect(Collectors.joining("\n---\n"));

        // 4. Construct System Prompt & Call LLM
        SystemPromptTemplate promptTemplate = new SystemPromptTemplate(SYSTEM_PROMPT);
        String formattedSystemPrompt = promptTemplate.createMessage(Map.of("context", contextBlock)).getContent();

        String rawLlmResponse = chatClient.prompt()
                .system(formattedSystemPrompt)
                .user(request.question())
                .advisors(a -> a.param(MessageChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, request.conversationId()))
                .call()
                .content();

        // 5. Output Validation against Anti-Hallucination Sentinel Token
        if (rawLlmResponse == null || rawLlmResponse.contains(NOT_FOUND_TOKEN)) {
            return createFallbackResponse(request);
        }

        // 6. Map Metadata Citations
        List<QueryResponse.Citation> citations = similarDocuments.stream()
                .map(doc -> new QueryResponse.Citation(
                        (String) doc.getMetadata().getOrDefault("file_name", "Policy Document"),
                        parsePageNumber(doc.getMetadata().get("page_number"))
                ))
                .distinct()
                .toList();

        return new QueryResponse(
                request.conversationId(),
                request.question(),
                rawLlmResponse.trim(),
                true,
                citations
        );
    }

    private QueryResponse createFallbackResponse(QueryRequest request) {
        return new QueryResponse(
                request.conversationId(),
                request.question(),
                "The requested information is not available in the company policy documents.",
                false,
                List.of()
        );
    }

    private int parsePageNumber(Object pageMeta) {
        if (pageMeta instanceof Integer i) return i;
        if (pageMeta instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
