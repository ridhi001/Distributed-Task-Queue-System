package com.jobcraft.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class GeminiClient {

    private static final Logger logger = LoggerFactory.getLogger(GeminiClient.class);
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api.key:}")
    private String apiKey;

    public GeminiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Agentic Job Router: Uses AI to decide job details based on a natural language prompt.
     */
    public String routeJob(String userPrompt) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            logger.warn("GEMINI_API_KEY is not configured. Falling back to local rules-based routing.");
            return generateMockRouteResponse(userPrompt);
        }

        String systemPrompt = "You are an AI Job Router. Parse the user's natural language request and output a JSON object containing:\n" +
                "1. jobType: MUST be one of 'SEND_EMAIL', 'RESIZE_IMAGE', 'GENERATE_REPORT'\n" +
                "2. priority: MUST be one of 'HIGH', 'MEDIUM', 'LOW'\n" +
                "3. payload: A JSON object with relevant key-value pairs (e.g. { \"recipient\": \"John\", \"template\": \"welcome\" } or { \"imageId\": \"img_123\", \"width\": 800 })\n" +
                "Return ONLY a raw JSON object, without markdown styling (no ```json code blocks), no explanation, and no leading/trailing text.";

        String requestBody = buildGeminiRequestBody(systemPrompt + "\n\nUser request: " + userPrompt);

        try {
            String response = callGeminiApi(requestBody);
            return cleanJsonMarkdown(response);
        } catch (Exception e) {
            logger.error("Error calling Gemini API for Job Routing: ", e);
            return generateMockRouteResponse(userPrompt);
        }
    }

    /**
     * AI Failure Analyzer: Analyzes a dead letter queue (DLQ) job failure and suggests a fix.
     */
    public String analyzeFailure(String jobType, String payload, String errorMessage) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            logger.warn("GEMINI_API_KEY is not configured. Falling back to local failure analyzer.");
            return "AI Analysis Unavailable: Please configure GEMINI_API_KEY. Generic Tip: Check if the payload matches the job type requirements and verify DB state.";
        }

        String systemPrompt = "You are a senior site reliability engineer (SRE). A background job has failed and landed in the Dead Letter Queue (DLQ). " +
                "Analyze the job metadata and error message, and suggest a brief, friendly, actionable fix in plain English. Limit the response to 2 sentences.\n\n" +
                "Job Type: " + jobType + "\n" +
                "Payload: " + payload + "\n" +
                "Error Message: " + errorMessage;

        String requestBody = buildGeminiRequestBody(systemPrompt);

        try {
            return callGeminiApi(requestBody);
        } catch (Exception e) {
            logger.error("Error calling Gemini API for Failure Analysis: ", e);
            return "AI Analysis Error: Failed to consult AI for diagnostic analysis. Please verify your internet connection and API key configuration.";
        }
    }

    private String callGeminiApi(String requestBody) throws Exception {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Gemini API call failed with status: " + response.statusCode() + " and response: " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        return root.path("candidates")
                .get(0)
                .path("content")
                .path("parts")
                .get(0)
                .path("text")
                .asText()
                .trim();
    }

    private String buildGeminiRequestBody(String prompt) {
        // Escaping simple double quotes in prompt for simple JSON construction
        String escapedPrompt = prompt.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");

        return "{"
                + "\"contents\": [{"
                + "  \"parts\": [{"
                + "    \"text\": \"" + escapedPrompt + "\""
                + "  }]"
                + "}]"
                + "}";
    }

    private String cleanJsonMarkdown(String response) {
        if (response == null) return "{}";
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    private String generateMockRouteResponse(String userPrompt) {
        String lower = userPrompt.toLowerCase();
        String jobType = "GENERATE_REPORT";
        String priority = "MEDIUM";
        String payloadJson = "{}";

        if (lower.contains("email") || lower.contains("send to") || lower.contains("mail")) {
            jobType = "SEND_EMAIL";
            priority = lower.contains("urgent") || lower.contains("welcome") ? "HIGH" : "MEDIUM";
            String email = "user@example.com";
            if (lower.contains("john")) email = "john@example.com";
            else if (lower.contains("admin")) email = "admin@example.com";
            payloadJson = String.format("{\"recipient\":\"%s\",\"template\":\"welcome\",\"subject\":\"Welcome to our app!\"}", email);
        } else if (lower.contains("resize") || lower.contains("image") || lower.contains("photo") || lower.contains("crop")) {
            jobType = "RESIZE_IMAGE";
            priority = "LOW";
            payloadJson = "{\"imageId\":\"img_" + System.currentTimeMillis() % 1000 + "\",\"width\":800,\"height\":600}";
        } else {
            // Report logic
            priority = lower.contains("financial") || lower.contains("audit") ? "HIGH" : "MEDIUM";
            payloadJson = String.format("{\"reportName\":\"Monthly_Financials_%d\",\"format\":\"PDF\"}", System.currentTimeMillis() % 1000);
        }

        return String.format("{\"jobType\":\"%s\",\"priority\":\"%s\",\"payload\":%s}", jobType, priority, payloadJson);
    }
}
