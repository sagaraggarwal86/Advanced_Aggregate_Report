# 📊 Configurable Aggregate Report — JMeter Plugin

> A powerful, file-based Aggregate Report plugin for Apache JMeter with offset filtering, configurable percentile,
> column visibility controls, and CSV export.

---

## ✨ Features at a Glance

| Feature                        | Description                                                     |
|--------------------------------|-----------------------------------------------------------------|
| 📂 **JTL File Processing**     | Browse and load JTL files — metrics populate instantly          |
| �?��? **Start / End Offset**      | Filter out ramp-up and ramp-down samples by seconds             |
| 📈 **Configurable Percentile** | Set any percentile value (50th, 95th, 99th…)                    |
| �?�? **Column Visibility**      | Show/hide columns via dropdown multi-select                     |
| ✅ **Pass / Fail Counts**       | Dedicated columns for Transaction Passed and Transaction Failed |
| �? **Test Time Info**          | Start Date/Time, End Date/Time, and Duration displayed          |
| 🔀 **Sortable Columns**        | Click any column header to sort ascending/descending            |
| 💾 **CSV Export**              | Save visible table data to CSV with one click                   |
| 🚫 **No Live Metrics**         | Designed for post-test JTL analysis — no runtime overhead       |

---

## 📦 Installation

1. Build the JAR (see [Building from Source](#�?-building-from-source)) or download from Releases
2. Copy it to your JMeter `lib/ext/` directory:
   ```
   <JMETER_HOME>/lib/ext/Configurable_Aggregate_Report-1.1.0.jar
   ```
3. Restart JMeter

---

## 🚀 Quick Start

1. Add the plugin to your test plan:
   **Test Plan → Add → Listener → Configurable Aggregate Report**

2. Click **Browse** → select a `.jtl` file → metrics populate immediately

3. Adjust filters:
    - **Start Offset** — skip the first N seconds (ramp-up exclusion)
    - **End Offset** — skip everything after N seconds (ramp-down exclusion)
    - **Percentile** — change from default 90th to any value

---

## 🖥�? UI Layout

```
┌─ Name / Comments ─────────────────────────────────────────────────�?
├─ Write results to file / Read from file ──────────────────────────┤
│  Filename [________________________]  [Browse...]                  │
├─ Filter Settings ─────────────────────────────────────────────────┤
│  Start Offset (s)  │  End Offset (s)  │  Percentile (%)  │ [Select Columns ▼] │
├─ Test Time Info ──────────────────────────────────────────────────┤
│  Start Date/Time        End Date/Time          Duration           │
│  [03/04/26 15:52:04]   [03/04/26 15:52:15]   [0h 0m 11s]        │
├─ Results Table (sortable) ────────────────────────────────────────┤
│  Transaction Name │ Count │ Passed │ Failed │ Avg(ms) │ ...      │
│  HTTP Request     │  19   │   0    │   19   │  448    │ ...      │
│  TOTAL            │  19   │   0    │   19   │  448    │ ...      │
├───────────────────────────────────────────────────────────────────┤
│             [Save Table Data]  ☑ Save Table Header                │
└───────────────────────────────────────────────────────────────────┘

```
<img src="img.jpg" alt="Alt text" width="500">
---

## 📋 Table Columns

| Column                    | Description                             |
|---------------------------|-----------------------------------------|
| **Transaction Name**      | Sampler label (always visible)          |
| **Transaction Count**     | Total number of samples                 |
| **Transaction Passed**    | Count of successful samples             |
| **Transaction Failed**    | Count of failed samples                 |
| **Avg Response Time(ms)** | Mean response time                      |
| **Min Response Time(ms)** | Fastest response                        |
| **Max Response Time(ms)** | Slowest response                        |
| **Xth Percentile(ms)**    | Configurable percentile (default: 90th) |
| **Std. Dev.**             | Standard deviation of response times    |
| **Error Rate**            | Percentage of failed samples            |
| **TPS**                   | Transactions per second (throughput)    |

All columns are **sortable** — click the header to sort ascending, click again for descending.

Use **Select Columns ▼** to show/hide any column except Transaction Name.

---

## �?��? Start / End Offset Filtering

Offsets let you exclude ramp-up and ramp-down periods from the analysis:

```
Test timeline:  0s────5s────────────25s────30s
All samples:    xxxxxx|=============|xxxxxx
                ^skip  ^included     ^skip

Start Offset = 5   → skip samples before 5s from test start
End Offset   = 25  → skip samples after 25s from test start
```

| Start Offset | End Offset | Behavior                                |
|--------------|------------|-----------------------------------------|
| *(empty)*    | *(empty)*  | All samples included                    |
| `5`          | *(empty)*  | Skip first 5 seconds, include the rest  |
| *(empty)*    | `25`       | Include up to 25 seconds, skip the rest |
| `5`          | `25`       | Include only the 5s – 25s window        |

Changing offset values **re-parses the JTL file instantly** — no need to re-browse.

---

## �? Test Time Info

Displayed below the filter settings after loading a JTL file:

| Field               | Value                                                                        |
|---------------------|------------------------------------------------------------------------------|
| **Start Date/Time** | Timestamp of the first included sample (`MM/dd/yy HH:mm:ss`, local timezone) |
| **End Date/Time**   | Timestamp when the last included sample completed (start + response time)    |
| **Duration**        | Wall-clock time from first sample start to last sample end                   |

> **Note:** Duration may be slightly longer than `End Offset - Start Offset` because it includes the response time of
> the last sample within the offset window.

---

## 💾 Saving Table Data

1. Click **Save Table Data**
2. Choose a location and filename (defaults to `aggregate_report.csv`)
3. Only **currently visible columns** are exported
4. Toggle **Save Table Header** checkbox to include/exclude the header row

---

## 🔧 Sub-Result Filtering

JMeter writes sub-results to JTL files as separate rows (e.g., `HTTP Request-0`, `HTTP Request-1`). The parser
automatically detects and excludes these, matching the behavior of JMeter's built-in Aggregate Report. Only parent
samples are aggregated.

---

## �? Project Structure

```
src/
  main/
    java/com/personal/jmeter/
      listener/
        ListenerCollector.java    # TestElement — property storage
        ListenerGUI.java          # GUI — file processing, table, filters
      parser/
        JTLParser.java            # CSV parser with offset & sub-result filtering
    resources/
      META-INF/services/
        org.apache.jmeter.gui.JMeterGUIComponent   # Service descriptor
  test/
    java/com/personal/jmeter/
      UIPreview.java              # Standalone preview (no JMeter runtime)
      ThroughputCalculationTest.java  # Unit tests
    resources/
      jmeter.properties          # Minimal props for test/standalone use
```

---

## 🛠�? Building from Source

**Prerequisites:** Java 17+, Maven 3.6+

```bash
git clone https://github.com/sagaraggarwal86/Configurable_Aggregate_Report.git
cd Configurable_Aggregate_Report
mvn clean package
```

The JAR is built to `target/Configurable_Aggregate_Report-1.1.0.jar`.

**Deploy to JMeter:**

```bash
cp target/Configurable_Aggregate_Report-1.1.0.jar $JMETER_HOME/lib/ext/
```

**Run standalone preview (no JMeter needed):**

```bash
mvn exec:java -Dexec.mainClass="com.personal.jmeter.UIPreview"
```

---

## 🧪 Running Tests

```bash
mvn test
```

Tests verify throughput and error percentage calculations using JMeter's `SamplingStatCalculator`.

---

## 📋 Requirements

| Requirement   | Version             |
|---------------|---------------------|
| Java          | 17+                 |
| Apache JMeter | 5.6.3+              |
| Maven         | 3.6+ (for building) |

---

## �? Contributing

Pull requests and issues are welcome!
Please test with JMeter 5.6+ on Windows, macOS, and Linux.

---

## 📄 License

Apache License 2.0 — see [LICENSE](LICENSE) for details.
