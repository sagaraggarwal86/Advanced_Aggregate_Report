package com.personal.jmeter.listener;

import org.apache.jmeter.reporters.ResultCollector;

/**
 * Backend collector for the Configurable Aggregate Report plugin.
 *
 * <p>This plugin does NOT capture live metrics. It processes JTL files
 * uploaded via the GUI's Browse button. This collector only stores
 * the configuration properties persisted in the .jmx file.</p>
 *
 * <h3>Property keys:</h3>
 * <ul>
 *   <li>{@code startOffset} — seconds from test start to begin aggregating</li>
 *   <li>{@code endOffset}   — seconds from test start to stop aggregating</li>
 *   <li>{@code percentile}  — display percentile value</li>
 * </ul>
 */
public class ListenerCollector extends ResultCollector {

    public static final String PROP_START_OFFSET = "startOffset";
    public static final String PROP_END_OFFSET = "endOffset";
    public static final String PROP_PERCENTILE = "percentile";
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public ListenerCollector() {
        super();
    }
}