package com.personal.jmeter.listener;

import com.personal.jmeter.data.AggregateResult;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SamplePluginListener extends ResultCollector {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(SamplePluginListener.class);

    // Thread-safe map to store aggregated results by label
    private final Map<String, AggregateResult> aggregatedResults = new ConcurrentHashMap<>();

    @Override
    public void sampleOccurred(SampleEvent event) {
        super.sampleOccurred(event);

        SampleResult result = event.getResult();

        // Aggregate the sample result
        String label = result.getSampleLabel();
        aggregatedResults.computeIfAbsent(label, k -> {
            AggregateResult ar = new AggregateResult();
            ar.setLabel(k);
            return ar;
        }).addSampleResult(result);

        log.debug("[SamplePlugin] {} | Success={} | {}ms",
                result.getSampleLabel(), result.isSuccessful(), result.getTime());
    }

    /**
     * Get the aggregated results
     */
    public Map<String, AggregateResult> getAggregatedResults() {
        return new ConcurrentHashMap<>(aggregatedResults);
    }

    /**
     * Clear all aggregated results
     */
    @Override
    public void clearData() {
        super.clearData();
        aggregatedResults.clear();
        log.info("Cleared all aggregated data");
    }
}
