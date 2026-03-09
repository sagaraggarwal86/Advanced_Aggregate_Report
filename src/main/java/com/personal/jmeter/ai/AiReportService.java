package com.personal.jmeter.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Calls the Groq AI API and returns the generated report in Markdown format.
 *
 * <p>The {@link HttpClient} is a shared singleton — it manages a connection
 * pool internally and must not be recreated per request.</p>
 *
 * <p>Retry behaviour: HTTP 429 and HTTP 5xx responses trigger up to
 * {@value #MAX_ATTEMPTS} total attempts with a {@value #RETRY_DELAY_MS} ms
 * minimum delay between retries. Non-retryable 4xx errors are thrown immediately.
 * After all attempts are exhausted, {@link AiServiceException} is thrown.</p>
 *
 * <p>API key resolution order:
 * <ol>
 *   <li>Exact env-var match: {@value #ENV_VAR_NAME}</li>
 *   <li>Case-insensitive scan of all env vars (handles Windows normalised env blocks)</li>
 *   <li>JVM system property {@code -DGROQ_API_KEY=...}</li>
 * </ol>
 */
public class AiReportService {

    /**
     * Name of the environment variable holding the Groq API key.
     */
    public static final String ENV_VAR_NAME = "GROQ_API_KEY";

    // DESIGN: Groq model IDs are versioned and subject to deprecation.
    // Update this constant when Groq releases a newer recommended model.
    // Verify available models at: GET https://api.groq.com/openai/v1/models
    // Current recommended model as of the last review — verify before deploying.
    private static final String GROQ_DEFAULT_MODEL = "llama-3.3-70b-versatile";

    private static final Logger log = LoggerFactory.getLogger(AiReportService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    /**
     * Maximum output tokens per Groq response.
     * 4096 accommodates full reports with multiple Markdown tables without truncation.
     */
    private static final int MAX_TOKENS = 4096;

    /** Total number of attempts (1 initial + 2 retries). */
    private static final int MAX_ATTEMPTS = 3;

    /** Minimum delay in milliseconds between retry attempts. */
    private static final long RETRY_DELAY_MS = 2_000L;

    /**
     * Long-lived singleton — reusing one client allows connection pooling and
     * avoids the overhead of a new TLS handshake per request.
     */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    private final String apiKey;

    /**
     * Constructs the service with the given API key.
     *
     * @param apiKey Groq API key; must not be null or blank
     * @throws IllegalArgumentException if the key is blank after trimming
     */
    public AiReportService(String apiKey) {
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        final String trimmed = apiKey.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        this.apiKey = trimmed;
    }

    // ─────────────────────────────────────────────────────────────
    // API key resolution
    // ─────────────────────────────────────────────────────────────

    /**
     * Reads the Groq API key from the environment.
     *
     * @return the trimmed key, or {@code null} if absent or blank
     */
    public static String readApiKeyFromEnv() {
        String key = System.getenv(ENV_VAR_NAME);
        if (isPresent(key)) {
            return key.trim();
        }

        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (ENV_VAR_NAME.equalsIgnoreCase(entry.getKey()) && isPresent(entry.getValue())) {
                return entry.getValue().trim();
            }
        }

        String prop = System.getProperty(ENV_VAR_NAME);
        return isPresent(prop) ? prop.trim() : null;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    // ─────────────────────────────────────────────────────────────
    // Report generation
    // ─────────────────────────────────────────────────────────────

    /**
     * Sends the two-part prompt to Groq and returns the AI-generated Markdown report.
     *
     * <p>SIGNATURE-CHANGE: parameter changed from {@code String prompt} to
     * {@link PromptContent} — required to implement the Standard 21 system/user
     * message split. Caller updated: {@code AiReportCoordinator.executeReport()}.</p>
     *
     * @param promptContent fully assembled analysis prompt; must not be null
     * @return AI-generated report text in Markdown
     * @throws AiServiceException       if the API returns an error, empty response,
     *                                   or all retry attempts are exhausted
     * @throws IOException              on network timeout or interrupted request
     * @throws IllegalArgumentException if promptContent is null
     */
    public String generateReport(PromptContent promptContent) throws IOException {
        Objects.requireNonNull(promptContent, "promptContent must not be null");
        log.info("generateReport: sending prompt. systemLength={}, userLength={}",
                promptContent.systemPrompt().length(), promptContent.userMessage().length());
        return callGroq(promptContent);
    }

    // ─────────────────────────────────────────────────────────────
    // Private implementation
    // ─────────────────────────────────────────────────────────────

    /**
     * Executes the Groq API call with exponential back-off retry.
     * Retries on HTTP 429 and HTTP 5xx only; 4xx errors are thrown immediately.
     */
    private String callGroq(PromptContent content) throws IOException {
        final String requestBody = buildRequestBody(content);
        AiServiceException lastEx = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<String> response = sendRequest(requestBody);
            int status = response.statusCode();

            if (status == 200) {
                return extractAndValidateContent(response.body());
            }

            boolean retryable = (status == 429 || (status >= 500 && status < 600));
            lastEx = new AiServiceException(String.format(
                    "Groq API returned HTTP %d. Body: %s", status, response.body()));

            if (!retryable || attempt == MAX_ATTEMPTS) {
                throw lastEx;
            }

            log.warn("callGroq: attempt {}/{} failed with HTTP {}. Retrying in {}ms.",
                    attempt, MAX_ATTEMPTS, status, RETRY_DELAY_MS);
            sleepBeforeRetry();
        }

        throw Objects.requireNonNull(lastEx); // unreachable; satisfies compiler
    }

    private HttpResponse<String> sendRequest(String requestBody) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_URL))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        try {
            return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("sendRequest: request interrupted. reason={}", e.getMessage(), e);
            throw new AiServiceException("Groq API request was interrupted", e);
        }
    }

    private void sleepBeforeRetry() throws AiServiceException {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiServiceException("Groq API retry sleep interrupted", e);
        }
    }

    /**
     * Builds the Groq request body with a {@code role:"system"} message containing
     * the analytical framework and a {@code role:"user"} message containing the
     * runtime test data, as required by the Standard 21 PromptBuilder contract.
     */
    private String buildRequestBody(PromptContent content) {
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", content.systemPrompt());

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", content.userMessage());

        JsonArray messages = new JsonArray();
        messages.add(systemMsg);
        messages.add(userMsg);

        JsonObject body = new JsonObject();
        body.addProperty("model", GROQ_DEFAULT_MODEL);
        body.addProperty("temperature", 0.3);
        body.addProperty("max_tokens", MAX_TOKENS);
        body.add("messages", messages);

        return body.toString();
    }

    /**
     * Extracts the AI-generated text from a Groq success response body and
     * validates that it is non-blank before returning.
     *
     * <p>The null/blank guard fires before any disk I/O downstream — a truncated
     * or empty response must never reach the HTML renderer.</p>
     */
    private String extractAndValidateContent(String responseBody) throws AiServiceException {
        final String aiText;
        try {
            aiText = JsonParser.parseString(responseBody)
                    .getAsJsonObject()
                    .getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        } catch (JsonParseException | IllegalStateException | IndexOutOfBoundsException e) {
            log.error("extractAndValidateContent: failed to parse response. reason={}", e.getMessage(), e);
            throw new AiServiceException("Failed to parse Groq API response: " + e.getMessage(), e);
        }

        // GUARD: must precede all disk I/O — do not open .tmp file before this check
        if (aiText == null || aiText.isBlank()) {
            throw new AiServiceException(
                    "Groq API returned an empty response. "
                    + "Check model ID, API key, and response parsing format. "
                    + "No file was written.");
        }

        return aiText;
    }
}
