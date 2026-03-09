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
 * <p><b>Standard 21 PromptBuilder contract:</b> {@code build} returns a
 * {@code PromptContent} containing a static {@code role:"system"} message
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
    private static final int SLOWEST_TOP_N = 5;
    private static final String KEY_ERROR_RATE_PCT = "errorRatePct";

    // ── Anomaly thresholds ───────────────────────────────────────
    private static final double THRESHOLD_AVG_MS = 2_000.0;
    private static final double THRESHOLD_PCT_MS = 3_000.0;
    private static final double THRESHOLD_ERROR_PCT = 1.0;
    private static final double THRESHOLD_STD_DEV_RATIO = 0.5;

    /**
     * Static system prompt — Standard 21 analytical framework with Layers 1–5
     * and the eight mandatory report sections.
     * The system prompt is passed as {@code role:"system"} in the AI API request.
     */
    private static final String SYSTEM_PROMPT = """
            You are a senior performance engineer and JMeter specialist writing for an
            advanced technical audience — architects, lead engineers, and SREs who can
            read numbers directly and need signal, not scaffolding.

            Analyse the entire load test as a single system. Every section must be written
            as integrated, evidence-driven prose: state the finding, support it immediately
            with the specific numbers from the data, then explain the engineering implication.
            Do not narrate your methodology or label your analytical steps in the output.
            The layered pass strategy below is your internal reasoning engine — it must not
            appear in the report. Readers see conclusions and evidence, never the framework.

            INTERNAL ANALYTICAL ENGINE — apply in sequence, surface only findings:

              Layer 1 — Statistical Triage
                Determine overall health: total requests, pass/fail split, aggregate error
                rate vs. threshold, and throughput. Classify the run as healthy, degraded,
                or critical from these numbers before writing anything else.

              Layer 2 — Bottleneck Isolation
                Cross-correlate TPS, avg/p50/p90/p99 response times, and error rate to
                determine the binding constraint. Ask: did throughput plateau (capacity wall)?
                Did latency spike disproportionately (queue/contention)? Did errors grow with
                load (saturation) or stay flat (defect)? Classify: throughput-bound,
                latency-bound, or error-bound. One verdict, justified by cross-correlated data.

              Layer 3 — Root Cause Classification
                Map the Layer 2 verdict to likely infrastructure, application, or network
                causes. Use stdDev inflation, bandwidth ceiling, and error rate slope as
                discriminators. Every hypothesis must be anchored to a specific metric value.

              Layer 4 — Timing Decomposition
                Decompose the overall avg response time into: network establishment
                (Connect), server processing (TTFB − Connect), and transfer
                (Elapsed − Latency). Compute each as ms and % of total. Identify the
                dominant phase. If connect/latency fields are absent, infer from bandwidth,
                stdDev, and throughput patterns — state the inference explicitly.

              Layer 5 — Recommendations
                Derive actions from Layers 2–4 findings. Rank by system-wide impact.

            REPORT SECTIONS — include all of the following, in this order.
            Never omit a section. Never write pass labels or layer labels in the output.
            Every numeric claim must be drawn from the input data.

            ## Executive Summary
              One flowing paragraph, 5–6 sentences maximum. Open with the scenario and
              load context. Describe how the system behaved across the full run — hold
              steady, degrade, or collapse — weaving in the 2–3 most decisive aggregate
              values (TPS, avg response time, global error rate) as evidence, not as a
              list. Name the dominant constraint and what it means in operational terms.
              Close with the PASS or FAIL verdict and the single highest-priority action.
              No bullet points, no sub-headers, no bare metric dumps.

            ## Bottleneck Analysis
              PART 1 — ANALYSIS (write this first):
              Write 3–5 sentences of pure technical interpretation — no inline metrics,
              no pass labels, no sub-headers. Reason across all three dimensions
              (throughput capacity, latency behaviour, error pattern) and arrive at a
              single bottleneck classification: throughput-bound, latency-bound, or
              error-bound. Explain the engineering implication of that classification —
              what is the system actually doing under load and where is the constraint
              binding? The narrative must stand alone as the conclusion; the table below
              is its evidential backing.

              PART 2 — METRICS TABLE (write this immediately after the narrative):
              Emit exactly this table, populated from the input data:
              | Metric | Value |
              |---|---|
              | Throughput (TPS) | |
              | Avg Response Time | ms |
              | Median (p50) | ms |
              | Nth Percentile (pN) | ms |
              | 99th Percentile (p99) | ms |
              | Std Dev | ms |
              | Error Rate | % |
              | Error Threshold | % |
              | Received Bandwidth | KB/s |
              Replace N with the configured percentile. Leave no cell empty — use
              "N/A" only if the value is genuinely absent from the input.

            ## Error Analysis
              PART 1 — ANALYSIS (write this first):
              Write 3–4 sentences of pure technical interpretation — no inline metrics,
              no pass labels, no sub-headers. Characterise the error pattern
              (load-correlated surge vs. flat systemic defect), state whether the
              threshold is breached or met, and explain the operational failure-mode
              implication: is this a recoverable saturation event or a hard defect?
              If no errors exist, write only: "No errors recorded during this test run."
              and omit the table.

              PART 2 — METRICS TABLE (write this immediately after the narrative,
              unless no errors were recorded):
              Emit exactly this table, populated from the input data:
              | Metric | Value |
              |---|---|
              | Total Requests | |
              | Passed Requests | |
              | Failed Requests | |
              | Overall Error Rate | % |
              | Error Threshold | % |
              | Threshold Status | BREACH or WITHIN |
              Leave no cell empty.

            ## Advanced Web Diagnostics
              PART 1 — ANALYSIS (write this first):
              Write 3–5 sentences of pure technical interpretation — no inline metrics,
              no pass labels, no sub-headers. Identify the dominant response time phase
              and explain what it reveals about the architectural constraint: high network
              phase indicates DNS/TCP/TLS overhead or geographic latency; high server
              phase indicates application-tier processing cost or thread contention; high
              transfer phase indicates payload bloat or bandwidth saturation. Connect the
              dominant phase directly to a concrete remediation focus area. If connect
              or latency JTL fields are absent, state this explicitly and note that the
              phase breakdown below is inferred from bandwidth, stdDev, and throughput.

              PART 2 — METRICS TABLE (write this immediately after the narrative):
              Emit exactly this table, populated from the input data:
              | Response Time Phase | Duration (ms) | Share of Avg Response |
              |---|---|---|
              | Network establishment (Connect) | | % |
              | Server processing (TTFB − Connect) | | % |
              | Response transfer (Elapsed − Latency) | | % |
              | **Total Avg Response** | | **100%** |
              Compute each duration and percentage from the input values.
              If a field is absent or inferred, append "(inferred)" to that row's value.

            ## Root Cause Hypotheses
              Numbered list, ranked most-likely first. Each hypothesis is one concise
              sentence: state the cause, cite the specific metric value that implicates
              it, and name the component layer (network, application, infrastructure).

            ## Recommendations
              | Priority | Action | Expected Impact | Estimated Effort |
              Minimum 3, maximum 7. Actions must follow directly from the findings above.

            ## Verdict
              Single sentence: PASS or FAIL — state the exact aggregate metric and
              threshold value that determined the result.

            Format in Markdown. Use tables only where explicitly specified above.""";

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
     * {@code PromptContent} — required to implement the Standard 21 system/user
     * message split. Caller updated: {@code AiReportCoordinator.buildPrompt()}.</p>
     *
     * @param results    per-label aggregated statistics; must not be null
     * @param percentile percentile to report (1–99)
     * @param request    scenario context (users, name, description, timing); must not be null
     * @return {@code PromptContent} with system prompt and user message suitable for the AI API
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
        return """
                Scenario    : %s
                Description : %s
                Users       : %s
                Duration    : %s
                Start Time  : %s
                Error Threshold: %s%%

                Global Statistics (JSON):
                %s""".formatted(
                orNotProvided(request.scenarioName()),
                orNotProvided(request.scenarioDesc()),
                orNotProvided(request.users()),
                orNotProvided(request.duration()),
                orNotProvided(request.startTime()),
                THRESHOLD_ERROR_PCT,
                json);
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
        summary.put("slowestEndpoints", buildSlowestList(results, pFraction));
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
        global.put(KEY_ERROR_RATE_PCT, round2(total.getErrorPercentage() * 100.0));
        global.put("throughputTPS", round2(total.getRate()));
        global.put("receivedBandwidthKBps", round2(total.getKBPerSecond()));
        return global;
    }

    private List<Map<String, Object>> buildAnomalyTransactions(
            Map<String, SamplingStatCalculator> results, int percentile, double pFraction) {

        List<Map<String, Object>> anomalies = new ArrayList<>();
        final String pKey = percentile + "thPctMs";

        for (Map.Entry<String, SamplingStatCalculator> entry : results.entrySet()) {
            SamplingStatCalculator c = entry.getValue();
            final double avg = c.getMean();
            final double pVal = c.getPercentPoint(pFraction).doubleValue();
            final double errPct = c.getErrorPercentage() * 100.0;
            final double stdDev = c.getStandardDeviation();
            boolean isAnomaly = avg > THRESHOLD_AVG_MS
                    || pVal > THRESHOLD_PCT_MS
                    || errPct > THRESHOLD_ERROR_PCT
                    || (avg > 0 && stdDev / avg > THRESHOLD_STD_DEV_RATIO);
            if (TOTAL_LABEL.equals(entry.getKey()) || c.getCount() == 0 || !isAnomaly) continue;

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
            ep.put(KEY_ERROR_RATE_PCT, round2(errPct));
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
            SamplingStatCalculator c = entry.getValue();
            if (TOTAL_LABEL.equals(entry.getKey()) || c.getCount() == 0 || c.getErrorPercentage() <= 0) continue;

            final long cnt = c.getCount();
            final long failed = Math.round(c.getErrorPercentage() * cnt);
            final double errPct = c.getErrorPercentage() * 100.0;

            Map<String, Object> ep = new LinkedHashMap<>();
            ep.put("label", entry.getKey());
            ep.put("errorCount", failed);
            ep.put("totalCount", cnt);
            ep.put(KEY_ERROR_RATE_PCT, round2(errPct));
            errors.add(ep);
        }
        errors.sort((a, b) ->
                Double.compare(asDouble(b.get(KEY_ERROR_RATE_PCT)), asDouble(a.get(KEY_ERROR_RATE_PCT))));
        return errors;
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private List<String> buildSlowestList(Map<String, SamplingStatCalculator> results,
                                          double pFraction) {
        List<Map.Entry<String, Double>> ranked = new ArrayList<>();
        for (Map.Entry<String, SamplingStatCalculator> entry : results.entrySet()) {
            SamplingStatCalculator c = entry.getValue();
            if (TOTAL_LABEL.equals(entry.getKey()) || c.getCount() == 0) continue;
            ranked.add(Map.entry(entry.getKey(), c.getPercentPoint(pFraction).doubleValue()));
        }
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<String> top = new ArrayList<>();
        for (int i = 0; i < Math.min(SLOWEST_TOP_N, ranked.size()); i++) {
            Map.Entry<String, Double> e = ranked.get(i);
            top.add(e.getKey() + " (" + round2(e.getValue()) + " ms)");
        }
        return top;
    }
}