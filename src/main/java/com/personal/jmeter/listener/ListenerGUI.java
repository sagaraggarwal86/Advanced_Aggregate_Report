package com.personal.jmeter.listener;

import com.personal.jmeter.parser.JTLParser;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.apache.jmeter.visualizers.gui.AbstractVisualizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GUI for the <b>Configurable Aggregate Report</b> JMeter listener plugin.
 *
 * <p>Processes JTL files uploaded via Browse — no live metrics.
 * Provides offset filtering, configurable percentile, column
 * visibility toggles, and CSV export.</p>
 */
public class ListenerGUI extends AbstractVisualizer {

    private static final Logger log = LoggerFactory.getLogger(ListenerGUI.class);

    // ── Fonts ────────────────────────────────────────────────────
    private static final Font FONT_HEADER  = new Font("Calibri", Font.PLAIN, 13);
    private static final Font FONT_REGULAR = new Font("Calibri", Font.PLAIN, 11);

    // ── Filter settings fields ───────────────────────────────────
    private final JTextField startOffsetField = new JTextField("", 10);
    private final JTextField endOffsetField   = new JTextField("", 10);
    private final JTextField percentileField  = new JTextField("90", 10);

    // ── Column definitions ───────────────────────────────────────
    private static final String[] ALL_COLUMNS = {
            "Transaction Name",
            "Transaction Count",
            "Transaction Passed",
            "Transaction Failed",
            "Avg Response Time(ms)",
            "Min Response Time(ms)",
            "Max Response Time(ms)",
            "90th Percentile",
            "Std. Dev.",
            "Error Rate",
            "TPS"
    };
    /** Index of the percentile column in ALL_COLUMNS. */
    private static final int PERCENTILE_COL_INDEX = 7;

    // ── Results table ────────────────────────────────────────────
    private final DefaultTableModel tableModel = new DefaultTableModel(ALL_COLUMNS, 0) {
        @Override
        public boolean isCellEditable(int row, int col) {
            return false;
        }
    };
    private final JTable resultsTable = new JTable(tableModel);

    // ── Column visibility menu items ───────────────────────────────
    /** One menu item per column. Index matches ALL_COLUMNS. */
    private final JCheckBoxMenuItem[] columnMenuItems = new JCheckBoxMenuItem[ALL_COLUMNS.length];
    /** Saved TableColumn objects for hidden columns (to restore them). */
    private final TableColumn[] allTableColumns = new TableColumn[ALL_COLUMNS.length];

    // ── Bottom controls ──────────────────────────────────────────
    private final JCheckBox saveTableHeaderBox = new JCheckBox("Save Table Header");

    // ── State ────────────────────────────────────────────────────
    private String lastLoadedFilePath = null;
    /** Cached parse results for column toggle re-rendering. */
    private Map<String, SamplingStatCalculator> cachedResults = null;

    // ─────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────

    public ListenerGUI() {
        super();
        initComponents();
        storeOriginalColumns();
        setupFieldListeners();
    }

    // ─────────────────────────────────────────────────────────────
    // Layout
    // ─────────────────────────────────────────────────────────────

    private void initComponents() {
        setLayout(new BorderLayout());

        // Standard JMeter panel: Name + Comments + File browse
        Container titlePanel = makeTitlePanel();
        add(titlePanel, BorderLayout.NORTH);

        // Remove unwanted FilePanel controls & hook Browse button
        hideFilePanelExtras(titlePanel);
        hookBrowseButton(titlePanel);

        // Plugin-specific content
        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Top section: filter panel + column visibility panel
        JPanel topSection = new JPanel(new BorderLayout(5, 5));
        topSection.add(buildFilterPanel(), BorderLayout.NORTH);
        topSection.add(buildColumnVisibilityPanel(), BorderLayout.SOUTH);

        mainPanel.add(topSection, BorderLayout.NORTH);
        mainPanel.add(buildTablePanel(), BorderLayout.CENTER);
        mainPanel.add(buildBottomPanel(), BorderLayout.SOUTH);

        add(mainPanel, BorderLayout.CENTER);
    }

    /**
     * Hide "Log/Display Only", "Errors", "Successes", "Configure" from FilePanel.
     */
    private void hideFilePanelExtras(Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JCheckBox cb) {
                String text = cb.getText();
                if (text != null && (text.contains("Log") || text.contains("Errors")
                        || text.contains("Successes"))) {
                    cb.setVisible(false);
                }
            } else if (comp instanceof JButton btn) {
                String text = btn.getText();
                if (text != null && text.contains("Configure")) {
                    btn.setVisible(false);
                }
            } else if (comp instanceof JLabel lbl) {
                String text = lbl.getText();
                if (text != null && (text.contains("Log") || text.contains("Display"))) {
                    lbl.setVisible(false);
                }
            }
            if (comp instanceof Container c) {
                hideFilePanelExtras(c);
            }
        }
    }

    /**
     * Find the Browse button in the component tree and hook our JTL loader.
     *
     * <p>After the Browse file dialog closes, FilePanel sets the filename text.
     * Our listener fires via {@code invokeLater} (so FilePanel finishes first),
     * reads {@code getFile()}, and loads the JTL.</p>
     */
    private void hookBrowseButton(Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JButton btn) {
                String text = btn.getText();
                // Hook only "Browse..." button, skip "Configure" and unnamed buttons
                if (text != null && text.startsWith("Browse")) {
                    btn.addActionListener(e -> SwingUtilities.invokeLater(() -> {
                        String filename = getFile();
                        if (filename != null && !filename.trim().isEmpty()
                                && new File(filename).exists()) {
                            loadJTLFile(filename);
                        }
                    }));
                }
            }
            if (comp instanceof Container c) {
                hookBrowseButton(c);
            }
        }
    }

    private JPanel buildFilterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        TitledBorder border = new TitledBorder("Filter Settings");
        border.setTitleFont(FONT_HEADER);
        panel.setBorder(border);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;

        c.gridy = 0;
        c.gridx = 0; c.weightx = 0.33;
        addLabel(panel, "Start Offset (Seconds)", c);
        c.gridx = 1; c.weightx = 0.33;
        addLabel(panel, "End Offset (Seconds)", c);
        c.gridx = 2; c.weightx = 0.34;
        addLabel(panel, "Percentile (%)", c);

        c.gridy = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        startOffsetField.setFont(FONT_REGULAR);
        panel.add(startOffsetField, c);
        c.gridx = 1;
        endOffsetField.setFont(FONT_REGULAR);
        panel.add(endOffsetField, c);
        c.gridx = 2;
        percentileField.setFont(FONT_REGULAR);
        panel.add(percentileField, c);

        return panel;
    }

    /**
     * Build a dropdown button that opens a popup menu with checkboxes
     * for toggling column visibility.
     */
    private JPanel buildColumnVisibilityPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));

        JLabel label = new JLabel("Visible Columns:");
        label.setFont(FONT_HEADER);
        panel.add(label);

        JPopupMenu popup = new JPopupMenu();
        for (int i = 0; i < ALL_COLUMNS.length; i++) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(ALL_COLUMNS[i], true);
            item.setFont(FONT_REGULAR);

            if (i == 0) {
                // "Transaction Name" always visible, non-toggleable
                item.setEnabled(false);
            } else {
                final int colIndex = i;
                item.addActionListener(e -> toggleColumnVisibility(colIndex, item.isSelected()));
            }

            columnMenuItems[i] = item;
            popup.add(item);
        }

        JButton dropdownBtn = new JButton("Select Columns ▼");
        dropdownBtn.setFont(FONT_REGULAR);
        dropdownBtn.addActionListener(e -> popup.show(dropdownBtn, 0, dropdownBtn.getHeight()));
        panel.add(dropdownBtn);

        return panel;
    }

    private void addLabel(JPanel panel, String text, GridBagConstraints c) {
        JLabel label = new JLabel(text);
        label.setFont(FONT_REGULAR);
        panel.add(label, c);
    }

    private JScrollPane buildTablePanel() {
        resultsTable.setFont(FONT_REGULAR);
        resultsTable.getTableHeader().setFont(FONT_HEADER);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        resultsTable.getTableHeader().setReorderingAllowed(false);
        resultsTable.setRowHeight(20);
        JScrollPane scrollPane = new JScrollPane(resultsTable);
        scrollPane.setPreferredSize(new Dimension(900, 300));
        return scrollPane;
    }

    private JPanel buildBottomPanel() {
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        JButton saveBtn = new JButton("Save Table Data");
        saveBtn.setFont(FONT_REGULAR);
        saveBtn.addActionListener(e -> saveTableData());
        saveTableHeaderBox.setFont(FONT_REGULAR);
        saveTableHeaderBox.setSelected(true);
        bottom.add(saveBtn);
        bottom.add(saveTableHeaderBox);
        return bottom;
    }

    // ─────────────────────────────────────────────────────────────
    // Column visibility
    // ─────────────────────────────────────────────────────────────

    /**
     * Store original TableColumn objects after table is built.
     * Needed to restore columns when checkbox is re-checked.
     */
    private void storeOriginalColumns() {
        TableColumnModel cm = resultsTable.getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++) {
            allTableColumns[i] = cm.getColumn(i);
        }
    }

    /**
     * Show or hide a column by adding/removing it from the table's column model.
     */
    private void toggleColumnVisibility(int colIndex, boolean visible) {
        TableColumnModel cm = resultsTable.getColumnModel();
        TableColumn col = allTableColumns[colIndex];

        if (visible) {
            // Add column back in the correct position
            // Count how many columns before this one are currently visible
            int insertAt = 0;
            for (int i = 0; i < colIndex; i++) {
                if (columnMenuItems[i].isSelected()) {
                    insertAt++;
                }
            }
            cm.addColumn(col);
            // Move it from the end to the correct position
            int currentPos = cm.getColumnCount() - 1;
            if (insertAt < currentPos) {
                cm.moveColumn(currentPos, insertAt);
            }
        } else {
            cm.removeColumn(col);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Field listeners (percentile + offsets → re-parse JTL)
    // ─────────────────────────────────────────────────────────────

    private void setupFieldListeners() {
        percentileField.getDocument().addDocumentListener(
                (SimpleDocListener) () -> { updatePercentileColumn(); reloadJTL(); });

        SimpleDocListener offsetListener = this::reloadJTL;
        startOffsetField.getDocument().addDocumentListener(offsetListener);
        endOffsetField.getDocument().addDocumentListener(offsetListener);
    }

    private void updatePercentileColumn() {
        String p = percentileField.getText().trim();
        if (p.isEmpty()) p = "90";
        // Update stored column header
        allTableColumns[PERCENTILE_COL_INDEX].setHeaderValue(p + "th Percentile");
        resultsTable.getTableHeader().repaint();
    }

    // ─────────────────────────────────────────────────────────────
    // Table population
    // ─────────────────────────────────────────────────────────────

    private void populateTable(Map<String, SamplingStatCalculator> results,
                               int percentile) {
        tableModel.setRowCount(0);

        DecimalFormat df0 = new DecimalFormat("#");
        DecimalFormat df1 = new DecimalFormat("#.0");
        DecimalFormat df2 = new DecimalFormat("0.00");
        double pFraction = percentile / 100.0;

        for (SamplingStatCalculator calc : results.values()) {
            if (calc.getCount() == 0) continue;

            long totalCount  = calc.getCount();
            long failedCount = Math.round(calc.getErrorPercentage() * totalCount);
            long passedCount = totalCount - failedCount;

            tableModel.addRow(new Object[]{
                    calc.getLabel(),
                    totalCount,
                    passedCount,
                    failedCount,
                    df0.format(calc.getMean()),
                    calc.getMin().intValue(),
                    calc.getMax().intValue(),
                    df0.format(calc.getPercentPoint(pFraction).doubleValue()),
                    df1.format(calc.getStandardDeviation()),
                    df2.format(calc.getErrorPercentage() * 100.0) + "%",
                    df1.format(calc.getRate()) + "/sec"
            });
        }
    }

    // ─────────────────────────────────────────────────────────────
    // AbstractVisualizer contract
    // ─────────────────────────────────────────────────────────────

    @Override
    public String getLabelResource() {
        return "configurable_aggregate_report";
    }

    @Override
    public String getStaticLabel() {
        return "Configurable Aggregate Report";
    }

    @Override
    public TestElement createTestElement() {
        ListenerCollector collector = new ListenerCollector();
        modifyTestElement(collector);
        return collector;
    }

    @Override
    public void modifyTestElement(TestElement el) {
        super.modifyTestElement(el);
        el.setProperty(ListenerCollector.PROP_START_OFFSET,
                startOffsetField.getText().trim());
        el.setProperty(ListenerCollector.PROP_END_OFFSET,
                endOffsetField.getText().trim());
        el.setProperty(ListenerCollector.PROP_PERCENTILE,
                percentileField.getText().trim());
    }

    @Override
    public void configure(TestElement el) {
        super.configure(el);
        startOffsetField.setText(
                el.getPropertyAsString(ListenerCollector.PROP_START_OFFSET, ""));
        endOffsetField.setText(
                el.getPropertyAsString(ListenerCollector.PROP_END_OFFSET, ""));
        String savedPercentile =
                el.getPropertyAsString(ListenerCollector.PROP_PERCENTILE, "90");
        percentileField.setText(savedPercentile.isEmpty() ? "90" : savedPercentile);

        // Auto-load JTL file if configured and not already loaded
        if (el instanceof ListenerCollector collector) {
            String filename = collector.getFilename();
            if (filename != null && !filename.trim().isEmpty()
                    && new File(filename).exists()
                    && !filename.equals(lastLoadedFilePath)) {
                loadJTLFile(filename);
            }
        }
    }

    @Override
    public void clearGui() {
        super.clearGui();
        startOffsetField.setText("");
        endOffsetField.setText("");
        percentileField.setText("90");
        tableModel.setRowCount(0);
        cachedResults = null;
        lastLoadedFilePath = null;
    }

    // ─────────────────────────────────────────────────────────────
    // No live metrics — add() is a no-op
    // ─────────────────────────────────────────────────────────────

    @Override
    public void add(SampleResult sample) {
        // Intentionally empty — this plugin processes JTL files only
    }

    @Override
    public void clearData() {
        tableModel.setRowCount(0);
        cachedResults = null;
        lastLoadedFilePath = null;
    }

    // ─────────────────────────────────────────────────────────────
    // JTL file loading
    // ─────────────────────────────────────────────────────────────

    /**
     * Load and parse a JTL file with current filter settings.
     */
    public boolean loadJTLFile(String filePath) {
        lastLoadedFilePath = filePath;
        try {
            JTLParser.FilterOptions opts = buildFilterOptions();
            JTLParser parser = new JTLParser();
            Map<String, SamplingStatCalculator> results = parser.parse(filePath, opts);

            cachedResults = results;
            populateTable(results, opts.percentile);
            return true;
        } catch (Exception e) {
            log.error("Error loading JTL file: {}", filePath, e);
            return false;
        }
    }

    /**
     * Re-parse the last loaded JTL file when offsets or percentile change.
     */
    private void reloadJTL() {
        if (lastLoadedFilePath == null || lastLoadedFilePath.isEmpty()) return;
        if (!new File(lastLoadedFilePath).exists()) return;
        try {
            JTLParser.FilterOptions opts = buildFilterOptions();
            JTLParser parser = new JTLParser();
            Map<String, SamplingStatCalculator> results = parser.parse(lastLoadedFilePath, opts);

            cachedResults = results;
            populateTable(results, opts.percentile);
        } catch (Exception e) {
            log.debug("Error reloading JTL with filters: {}", e.getMessage());
        }
    }

    private JTLParser.FilterOptions buildFilterOptions() {
        JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
        try {
            String s = startOffsetField.getText().trim();
            if (!s.isEmpty()) opts.startOffset = Integer.parseInt(s);
        } catch (NumberFormatException ignored) { opts.startOffset = 0; }
        try {
            String s = endOffsetField.getText().trim();
            if (!s.isEmpty()) opts.endOffset = Integer.parseInt(s);
        } catch (NumberFormatException ignored) { opts.endOffset = 0; }
        try {
            opts.percentile = Integer.parseInt(percentileField.getText().trim());
        } catch (NumberFormatException ignored) { opts.percentile = 90; }
        return opts;
    }

    // ─────────────────────────────────────────────────────────────
    // Save Table Data
    // ─────────────────────────────────────────────────────────────

    private void saveTableData() {
        if (tableModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(this,
                    "No data to save. Load a JTL file first.",
                    "No Data", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save Table Data");
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".csv");
            }
            public String getDescription() { return "CSV Files (*.csv)"; }
        });
        fc.setSelectedFile(new File("aggregate_report.csv"));

        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".csv")) {
                file = new File(file.getAbsolutePath() + ".csv");
            }
            try {
                saveTableToCSV(file);
                JOptionPane.showMessageDialog(this,
                        "Saved to:\n" + file.getAbsolutePath(),
                        "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                log.error("Error saving CSV: {}", file.getAbsolutePath(), e);
                JOptionPane.showMessageDialog(this,
                        "Error saving file:\n" + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * Exports only the currently VISIBLE columns to CSV.
     */
    private void saveTableToCSV(File file) throws Exception {
        // Build list of visible column indices (model indices)
        List<Integer> visibleCols = getVisibleColumnModelIndices();

        try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.FileWriter(file))) {
            if (saveTableHeaderBox.isSelected()) {
                StringBuilder hdr = new StringBuilder();
                for (int i = 0; i < visibleCols.size(); i++) {
                    if (i > 0) hdr.append(",");
                    hdr.append(ALL_COLUMNS[visibleCols.get(i)]);
                }
                w.write(hdr.toString());
                w.newLine();
            }
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < visibleCols.size(); i++) {
                    if (i > 0) line.append(",");
                    Object val = tableModel.getValueAt(row, visibleCols.get(i));
                    String cell = val != null ? val.toString() : "";
                    if (visibleCols.get(i) == 0) cell = escapeCSV(cell);
                    line.append(cell);
                }
                w.write(line.toString());
                w.newLine();
            }
        }
    }

    /**
     * Get model indices of currently visible columns (checked checkboxes).
     */
    private List<Integer> getVisibleColumnModelIndices() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < columnMenuItems.length; i++) {
            if (columnMenuItems[i].isSelected()) {
                indices.add(i);
            }
        }
        return indices;
    }

    private String escapeCSV(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ─────────────────────────────────────────────────────────────
    // Compact DocumentListener helper
    // ─────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface SimpleDocListener extends javax.swing.event.DocumentListener {
        void onUpdate();
        default void insertUpdate(javax.swing.event.DocumentEvent e)  { onUpdate(); }
        default void removeUpdate(javax.swing.event.DocumentEvent e)  { onUpdate(); }
        default void changedUpdate(javax.swing.event.DocumentEvent e) { onUpdate(); }
    }
}