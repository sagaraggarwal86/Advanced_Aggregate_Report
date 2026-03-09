package com.personal.jmeter.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Builds the two-part AI analysis prompt from already-aggregated JMeter results.
 *
 * <p><b>Standard 21 PromptBuilder contract:</b> {@link #build} returns a
 * {@link PromptContent} containing a static {@code role:"system"} message
 * (the analytical framework with Layers 1–5 and eight mandatory report sections)
 * and a dynamic {@code role:"user"} message containing runtime test data with
 * all Standard 21 placeholders substituted.</p>
 *
 * <h3>JSON sections sent in the user message</h3>
 * <ul>
 *   <li>{@code globalStats}         — TOTAL-row KPIs</li>
 *   <li>{@code anomalyTransactions} — transactions breaching at least one threshold</li>
 *   <li>{@code errorEndpoints}      — transactions with any errors</li>
 *   <li>{@code slowestEndpoints}    — top-5 by Nth-percentile (label + value only)</li>
 * </ul>
 */
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String TOTAL_LABEL = "TOTAL";
    private static final double MEDIAN = 0.50;

    // ── Anomaly thresholds ───────────────────────────────────────
    private static final double THRESHOLD_AVG_MS = 2_000.0;
    private static final double THRESHOLD_PCT_MS = 3_000.0;
    private static final double THRESHOLD_ERROR_PCT = 1.0;
    private static final double THRESHOLD_STD_DEV_RATIO = 0.5;

    /**
     * Static system prompt — Standard 21 analytical framework with Layers 1–5
     * and the eight mandatory report sections.
     * The system prompt is passed as {@code role:"system"} in the Groq request.
     */
    private static final String SYSTEM_PROMPT =
            "You are a senior performance engineer and JMeter specialist conducting\n"
            + "a deep, multi-dimensional load test analysis using a layered decision strategy.\n"
            + "Your analysis must be structured, evidence-based, data-driven, and actionable.\n\n"
            + "Apply the following analytical layers in strict sequence:\n\n"
            + "  Layer 1 — Statistical Triage\n"
            + "    Identify outliers by error rate and 90th-percentile response time.\n"
            + "    Flag any transaction breaching the stated error threshold.\n\n"
            + "  Layer 2 — Bottleneck Isolation\n"
            + "    Rank all transactions by degradation severity.\n"
            + "    Determine whether bottlenecks are throughput-bound, latency-bound, or error-bound.\n\n"
            + "  Layer 3 — Root Cause Classification\n"
            + "    Correlate metrics to likely infrastructure, application, or network causes.\n"
            + "    Reference specific data values from the input — never assert without evidence.\n\n"
            + "  Layer 4 — Advanced Web Diagnostics\n"
            + "    Where JTL data includes connect time, latency, and idle time fields,\n"
            + "    decompose the response time into:\n"
            + "      - DNS resolution estimate\n"
            + "      - TCP connection time  (Connect field)\n"
            + "      - TLS handshake time   (where HTTPS is used)\n"
            + "      - Time to first byte   (Latency minus Connect)\n"
            + "      - Response body transfer time  (Elapsed minus Latency)\n"
            + "    Present findings as a per-transaction Markdown table where data is available.\n\n"
            + "  Layer 5 — Recommendations\n"
            + "    Prioritise fixes by business impact and engineering effort.\n\n"
            + "Your report MUST contain ALL of the following sections. Never omit a section.\n"
            + "Never summarise without citing specific numeric values from the input data.\n\n"
            + "## Executive Summary\n"
            + "  Pass/fail verdict with the specific metric and threshold that determined it.\n"
            + "  Maximum 3 sentences.\n\n"
            + "## Bottleneck Analysis\n"
            + "  A Markdown table with ALL of the following columns:\n"
            + "  | Transaction | Samples | Avg (ms) | 90th% (ms) | 99th% (ms) | Error% | Req/sec |\n"
            + "  Below the table: identify the single worst bottleneck, cross-reference it\n"
            + "  against raw JTL data patterns noted in the input (sample count trends,\n"
            + "  error clustering, throughput drops), and explain its most likely root cause.\n\n"
            + "## Error Analysis\n"
            + "  HTTP error code distribution as a Markdown table:\n"
            + "  | Transaction | Error Code | Count | % of Total Errors |\n"
            + "  Flag every transaction exceeding the stated error threshold.\n"
            + "  If no errors exist, state \"No errors recorded\" — do not omit the section.\n\n"
            + "## Advanced Web Diagnostics\n"
            + "  Per-transaction timing decomposition table (where JTL fields permit):\n"
            + "  | Transaction | Connect (ms) | TTFB (ms) | Transfer (ms) | Total (ms) |\n"
            + "  Interpret the dominant time phase for the worst-performing transactions.\n\n"
            + "## Chart References\n"
            + "  List the charts that should be generated to visualise these findings.\n"
            + "  For each chart specify: chart type, metric on Y-axis, time/transactions on X-axis.\n"
            + "  Required candidates:\n"
            + "    - Response Time Over Time     (flag which transactions)\n"
            + "    - Throughput Over Time\n"
            + "    - Error Rate Over Time\n"
            + "    - Percentile Distribution     (if p90/p99 gap exceeds 2x)\n"
            + "    - Active Threads Over Time    (if thread ramp data is available)\n\n"
            + "## Root Cause Hypotheses\n"
            + "  Numbered list, ranked most-likely first.\n"
            + "  Each hypothesis must cite at least one specific data value as evidence.\n\n"
            + "## Recommendations\n"
            + "  | Priority | Action | Expected Impact | Estimated Effort |\n"
            + "  At minimum 3 recommendations. At most 7.\n\n"
            + "## Verdict\n"
            + "  Single sentence: PASS or FAIL — state the exact metric and threshold value\n"
            + "  that determined the result.\n\n"
            + "Format in Markdown. Use tables for metrics. Be concise.";

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * Safely extracts a {@code double} from a map value without unchecked casts.
     * Returns {@code 0.0} when the value is absent or not a {@link Number}.
     *
     * @param value map value to convert
     * @return numeric value, or 0.0
     */
    private static double asDouble(Object value) {
        return (value instanceof Number n) ? n.doubleValue() : 0.0;
    }

    /**
     * Builds the two-part AI analysis prompt from aggregated JMeter results.
     *
     * <p>SIGNATURE-CHANGE: return type changed from {@code String} to
     * {@link PromptContent} — required to implement the Standard 21 system/user
     * message split. Caller updated: {@code AiReportCoordinator.buildPrompt()}.</p>
     *
     * @param results    per-label aggregated statistics; must not be null
     * @param percentile percentile to report (1–99)
     * @param request    scenario context (users, name, description, timing); must not be null
     * @return {@link PromptContent} with system prompt and user message suitable for Groq API
     */
    public PromptContent build(Map<String, SamplingStatCalculator> results,
                               int percentile,
                               PromptRequest request) {
        Objects.requireNonNull(results, "results must not be null");
        Objects.requireNonNull(request, "request must not be null");
        log.info("build: building prompt. labels={}, percentile={}", results.size(), percentile);

        String userMessage = buildUserMessage(results, percentile, request);
        return new PromptContent(SYSTEM_PROMPT, userMessage);
    }

    // ─────────────────────────────────────────────────────────────
    // User message assembly (Standard 21 dynamic substitution)
    // ─────────────────────────────────────────────────────────────

    private String buildUserMessage(Map<String, SamplingStatCalculator> results,
                                    int percentile, PromptRequest request) {
        String json = GSON.toJson(buildSummary(results, percentile));
        return "Scenario    : " + orNotProvided(request.scenarioName()) + "\n"
                + "Description : " + orNotProvided(request.scenarioDesc()) + "\n"
                + "Users       : " + orNotProvided(request.users()) + "\n"
                + "Duration    : " + orNotProvided(request.duration()) + "\n"
                + "Start Time  : " + orNotProvided(request.startTime()) + "\n"
                + "Error Threshold: " + THRESHOLD_ERROR_PCT + "%\n\n"
                + "Global Statistics (JSON):\n" + json;
    }

    private static String orNotProvided(String value) {
        return (value == null || value.isBlank()) ? "Not provided" : value.trim();
    }

    // ─────────────────────────────────────────────────────────────
    // JSON summary
    // ─────────────────────────────────────────────────────────────

    private Map<String, Object> buildSummary(Map<String, SamplingStatCalculator> results,
                                             int percentile) {
        final double pFraction = percentile / 100.0;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("globalStats", buildGlobalStats(results, percentile, pFraction));
        summary.put("anomalyTransactions", buildAnomalyTransactions(results, percentile, pFraction));
        summary.put("errorEndpoints", buildErrorEndpointList(results));
        summary.put("slowestEndpoints", buildSlowestList(results, pFraction, 5));
        return summary;
    }

    private Map<String, Object> buildGlobalStats(Map<String, SamplingStatCalculator> results,
                                                 int percentile, double pFraction) {
        Map<String, Object> global = new LinkedHashMap<>();
        SamplingStatCalculator total = results.get(TOTAL_LABEL);
        if (total == null || total.getCount() == 0) return global;

        final long totalCount = total.getCount();
        final long failedCount = Math.round(total.getErrorPercentage() * totalCount);

        global.put("totalRequests", totalCount);
        global.put("totalPassed", totalCount - failedCount);
        global.put("totalFailed", failedCount);
        global.put("avgResponseMs", round2(total.getMean()));
        global.put("medianResponseMs", round2(total.getPercentPoint(MEDIAN).doubleValue()));
        global.put("minResponseMs", total.getMin().longValue());
        global.put("maxResponseMs", total.getMax().longValue());
        global.put(percentile + "thPctMs", round2(total.getPercentPoint(pFraction).doubleValue()));
        global.put("stdDevMs", round2(total.getStandardDeviation()));
        global.put("errorRatePct", round2(total.getErrorPercentage() * 100.0));
        global.put("throughputTPS", round2(total.getRate()));
        global.put("receivedBandwidthKBps", round2(total.getKBPerSecond()));
        return global;
    }

    private List<Map<String, Object>> buildAnomalyTransactions(
            Map<String, SamplingStatCalculator> results, int percentile, double pFraction) {

        List<Map<String, Object>> anomalies = new ArrayList<>();
        final String pKey = percentile + "thPctMs";

        for (Map.Entry<String, SamplingStatCalculator> entry : results.entrySet()) {
            if (TOTAL_LABEL.equals(entry.getKey())) continue;
            SamplingStatCalculator c = entry.getValue();
            if (c.getCount() == 0) continue;

            final double avg = c.getMean();
            final double pVal = c.getPercentPoint(pFraction).doubleValue();
            final double errPct = c.getErrorPercentage() * 100.0;
            final double stdDev = c.getStandardDeviation();

            boolean isAnomaly = avg > THRESHOLD_AVG_MS
                    || pVal > THRESHOLD_PCT_MS
                    || errPct > THRESHOLD_ERROR_PCT
                    || (avg > 0 && stdDev / avg > THRESHOLD_STD_DEV_RATIO);
            if (!isAnomaly) continue;

            final long cnt = c.getCount();
            final long failed = Math.round(c.getErrorPercentage() * cnt);

            Map<String, Object> ep = new LinkedHashMap<>();
            ep.put("label", entry.getKey());
            ep.put("count", cnt);
            ep.put("failed", failed);
            ep.put("avgMs", round2(avg));
            ep.put("medianMs", round2(c.getPercentPoint(MEDIAN).doubleValue()));
            ep.put(pKey, round2(pVal));
            ep.put("stdDevMs", round2(stdDev));
            ep.put("errorRatePct", round2(errPct));
            ep.put("throughputTPS", round2(c.getRate()));
            ep.put("receivedBandwidthKBps", round2(c.getKBPerSecond()));
            ep.put("breachedThresholds", buildBreachList(avg, pVal, errPct, stdDev, percentile));
            anomalies.add(ep);
        }

        anomalies.sort((a, b) ->
                Double.compare(asDouble(b.get(pKey)), asDouble(a.get(pKey))));
        return anomalies;
    }

    private List<String> buildBreachList(double avg, double pVal, double errPct,
                                         double stdDev, int percentile) {
        List<String> breaches = new ArrayList<>();
        if (avg > THRESHOLD_AVG_MS) breaches.add("avgMs > " + (int) THRESHOLD_AVG_MS + "ms");
        if (pVal > THRESHOLD_PCT_MS) breaches.add(percentile + "thPct > " + (int) THRESHOLD_PCT_MS + "ms");
        if (errPct > THRESHOLD_ERROR_PCT) breaches.add("errorRate > " + THRESHOLD_ERROR_PCT + "%");
        if (avg > 0 && stdDev / avg > THRESHOLD_STD_DEV_RATIO)
            breaches.add("highVariability (stdDev/avg=" + round2(stdDev / avg) + ")");
        return breaches;
    }

    private List<Map<String, Object>> buildErrorEndpointList(
            Map<String, SamplingStatCalculator> results) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Map.Entry<String, SamplingStatCalculator> entry : results.entrySet()) {
            if (TOTAL_LABEL.equals(entry.getKey())) continue;
            SamplingStatCalculator c = entry.getValue();
            if (c.getCount() == 0 || c.getErrorPercentage() <= 0) continue;

            final long cnt = c.getCount();
            final long failed = Math.round(c.getErrorPercentage() * cnt);
            final double errPct = c.getErrorPercentage() * 100.0;

            Map<String, Object> ep = new LinkedHashMap<>();
            ep.put("label", entry.getKey());
            ep.put("errorCount", failed);
            ep.put("totalCount", cnt);
            ep.put("errorRatePct", round2(errPct));
            errors.add(ep);
        }
        errors.sort((a, b) ->
                Double.compare(asDouble(b.get("errorRatePct")), asDouble(a.get("errorRatePct"))));
        return errors;
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private List<String> buildSlowestList(Map<String, SamplingStatCalculator> results,
                                          double pFraction, int topN) {
        List<Map.Entry<String, Double>> ranked = new ArrayList<>();
        for (Map.Entry<String, SamplingStatCalculator> entry : results.entrySet()) {
            if (TOTAL_LABEL.equals(entry.getKey())) continue;
            SamplingStatCalculator c = entry.getValue();
            if (c.getCount() == 0) continue;
            ranked.add(Map.entry(entry.getKey(), c.getPercentPoint(pFraction).doubleValue()));
        }
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<String> top = new ArrayList<>();
        for (int i = 0; i < Math.min(topN, ranked.size()); i++) {
            Map.Entry<String, Double> e = ranked.get(i);
            top.add(e.getKey() + " (" + round2(e.getValue()) + " ms)");
        }
        return top;
    }
}
