# Rentoza Platform - Cloud Monitoring Dashboard Configuration
# ============================================================
# This file contains the configuration for Google Cloud Monitoring dashboards
# and alerting policies for the Rentoza platform.
#
# Deploy with: gcloud monitoring dashboards create --config-from-file=monitoring-dashboard.json

# ============================================================
# DASHBOARD CONFIGURATION (JSON format for GCP)
# ============================================================

```json
{
  "displayName": "Rentoza Platform - Production Dashboard",
  "mosaicLayout": {
    "columns": 12,
    "tiles": [
      {
        "width": 6,
        "height": 4,
        "widget": {
          "title": "Request Rate (requests/sec)",
          "xyChart": {
            "dataSets": [{
              "timeSeriesQuery": {
                "prometheusQuery": "rate(http_server_requests_seconds_count{job=\"rentoza-backend\"}[5m])"
              },
              "plotType": "LINE"
            }],
            "yAxis": {"label": "req/s"}
          }
        }
      },
      {
        "xPos": 6,
        "width": 6,
        "height": 4,
        "widget": {
          "title": "Error Rate (%)",
          "xyChart": {
            "dataSets": [{
              "timeSeriesQuery": {
                "prometheusQuery": "100 * sum(rate(http_server_requests_seconds_count{job=\"rentoza-backend\",status=~\"5..\"}[5m])) / sum(rate(http_server_requests_seconds_count{job=\"rentoza-backend\"}[5m]))"
              },
              "plotType": "LINE"
            }],
            "yAxis": {"label": "%"},
            "thresholds": [{
              "targetAxis": "Y1",
              "value": 5,
              "color": "RED",
              "label": "Alert Threshold"
            }]
          }
        }
      },
      {
        "yPos": 4,
        "width": 6,
        "height": 4,
        "widget": {
          "title": "Response Latency P95 (ms)",
          "xyChart": {
            "dataSets": [{
              "timeSeriesQuery": {
                "prometheusQuery": "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{job=\"rentoza-backend\"}[5m])) by (le)) * 1000"
              },
              "plotType": "LINE"
            }],
            "yAxis": {"label": "ms"},
            "thresholds": [{
              "targetAxis": "Y1",
              "value": 2000,
              "color": "YELLOW",
              "label": "P95 Target"
            }]
          }
        }
      },
      {
        "xPos": 6,
        "yPos": 4,
        "width": 6,
        "height": 4,
        "widget": {
          "title": "Active Database Connections",
          "xyChart": {
            "dataSets": [{
              "timeSeriesQuery": {
                "prometheusQuery": "hikaricp_connections_active{job=\"rentoza-backend\"}"
              },
              "plotType": "STACKED_AREA"
            }],
            "yAxis": {"label": "connections"}
          }
        }
      },
      {
        "yPos": 8,
        "width": 4,
        "height": 4,
        "widget": {
          "title": "Circuit Breaker State",
          "scorecard": {
            "timeSeriesQuery": {
              "prometheusQuery": "resilience4j_circuitbreaker_state{job=\"rentoza-backend\"}"
            },
            "thresholds": [
              {"value": 0, "color": "GREEN", "label": "CLOSED"},
              {"value": 1, "color": "YELLOW", "label": "HALF_OPEN"},
              {"value": 2, "color": "RED", "label": "OPEN"}
            ]
          }
        }
      },
      {
        "xPos": 4,
        "yPos": 8,
        "width": 4,
        "height": 4,
        "widget": {
          "title": "JVM Heap Memory",
          "xyChart": {
            "dataSets": [{
              "timeSeriesQuery": {
                "prometheusQuery": "jvm_memory_used_bytes{job=\"rentoza-backend\",area=\"heap\"} / 1024 / 1024"
              },
              "plotType": "LINE"
            }],
            "yAxis": {"label": "MB"}
          }
        }
      },
      {
        "xPos": 8,
        "yPos": 8,
        "width": 4,
        "height": 4,
        "widget": {
          "title": "CPU Usage (%)",
          "xyChart": {
            "dataSets": [{
              "timeSeriesQuery": {
                "prometheusQuery": "process_cpu_usage{job=\"rentoza-backend\"} * 100"
              },
              "plotType": "LINE"
            }],
            "yAxis": {"label": "%"},
            "thresholds": [{
              "targetAxis": "Y1",
              "value": 80,
              "color": "RED",
              "label": "Alert Threshold"
            }]
          }
        }
      },
      {
        "yPos": 12,
        "width": 6,
        "height": 4,
        "widget": {
          "title": "Bookings Created (per hour)",
          "xyChart": {
            "dataSets": [{
              "timeSeriesQuery": {
                "prometheusQuery": "increase(bookings_created_total{job=\"rentoza-backend\"}[1h])"
              },
              "plotType": "STACKED_BAR"
            }],
            "yAxis": {"label": "bookings"}
          }
        }
      },
      {
        "xPos": 6,
        "yPos": 12,
        "width": 6,
        "height": 4,
        "widget": {
          "title": "Payment Processing Success Rate",
          "scorecard": {
            "timeSeriesQuery": {
              "prometheusQuery": "100 * sum(rate(payments_processed_total{job=\"rentoza-backend\",status=\"success\"}[1h])) / sum(rate(payments_processed_total{job=\"rentoza-backend\"}[1h]))"
            },
            "thresholds": [
              {"value": 99, "color": "GREEN"},
              {"value": 95, "color": "YELLOW"},
              {"value": 0, "color": "RED"}
            ],
            "sparkChartView": {"sparkChartType": "SPARK_LINE"}
          }
        }
      }
    ]
  }
}
```

# ============================================================
# ALERTING POLICIES
# ============================================================

## 1. High Error Rate Alert
# Triggers when 5xx error rate exceeds 5% for 5 minutes

```yaml
# alert-high-error-rate.yaml
displayName: "Rentoza - High Error Rate"
combiner: OR
conditions:
  - displayName: "Error rate > 5%"
    conditionPrometheusQueryLanguage:
      query: |
        100 * sum(rate(http_server_requests_seconds_count{job="rentoza-backend",status=~"5.."}[5m]))
        / sum(rate(http_server_requests_seconds_count{job="rentoza-backend"}[5m])) > 5
      duration: 300s
      evaluationInterval: 60s
alertStrategy:
  autoClose: 604800s
notificationChannels:
  - projects/${PROJECT_ID}/notificationChannels/${SLACK_CHANNEL_ID}
  - projects/${PROJECT_ID}/notificationChannels/${PAGERDUTY_CHANNEL_ID}
documentation:
  content: |
    ## High Error Rate Detected
    
    **Severity:** Critical
    
    **What's happening:**
    The Rentoza backend is returning more than 5% 5xx errors.
    
    **Impact:**
    Users are experiencing failures when using the platform.
    
    **Runbook:** See [On-Call Runbook](../docs/ON_CALL_RUNBOOK.md#high-error-rate)
    
    **Actions:**
    1. Check Cloud Run logs for error details
    2. Verify database connectivity
    3. Check circuit breaker states
    4. Consider rolling back recent deployment
  mimeType: text/markdown
```

## 2. High Latency Alert
# Triggers when P95 latency exceeds 2 seconds

```yaml
# alert-high-latency.yaml
displayName: "Rentoza - High Latency"
combiner: OR
conditions:
  - displayName: "P95 latency > 2s"
    conditionPrometheusQueryLanguage:
      query: |
        histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{job="rentoza-backend"}[5m])) by (le)) > 2
      duration: 300s
      evaluationInterval: 60s
alertStrategy:
  autoClose: 604800s
notificationChannels:
  - projects/${PROJECT_ID}/notificationChannels/${SLACK_CHANNEL_ID}
documentation:
  content: |
    ## High Latency Detected
    
    **Severity:** Warning
    
    **What's happening:**
    The Rentoza backend P95 response time exceeds 2 seconds.
    
    **Impact:**
    Users are experiencing slow page loads and poor UX.
    
    **Runbook:** See [On-Call Runbook](../docs/ON_CALL_RUNBOOK.md#high-latency)
    
    **Actions:**
    1. Check for slow database queries
    2. Verify Cloud Run instance count
    3. Check external API response times
    4. Review recent code changes
  mimeType: text/markdown
```

## 3. Circuit Breaker Open Alert
# Triggers when any circuit breaker opens

```yaml
# alert-circuit-breaker-open.yaml
displayName: "Rentoza - Circuit Breaker Open"
combiner: OR
conditions:
  - displayName: "Circuit breaker opened"
    conditionPrometheusQueryLanguage:
      query: |
        resilience4j_circuitbreaker_state{job="rentoza-backend"} == 2
      duration: 60s
      evaluationInterval: 30s
alertStrategy:
  autoClose: 604800s
notificationChannels:
  - projects/${PROJECT_ID}/notificationChannels/${SLACK_CHANNEL_ID}
documentation:
  content: |
    ## Circuit Breaker Opened
    
    **Severity:** Warning
    
    **What's happening:**
    A circuit breaker has opened, indicating repeated failures to an external service.
    
    **Circuit Breakers:**
    - `exifValidation` - Image EXIF validation service
    - `paymentGateway` - Payment processing service
    - `notificationService` - Push notification service
    
    **Runbook:** See [On-Call Runbook](../docs/ON_CALL_RUNBOOK.md#circuit-breaker)
    
    **Actions:**
    1. Identify which circuit breaker opened from logs
    2. Check the upstream service health
    3. Verify network connectivity
    4. Wait for half-open state and monitor recovery
  mimeType: text/markdown
```

## 4. High CPU Usage Alert
# Triggers when CPU exceeds 80%

```yaml
# alert-high-cpu.yaml
displayName: "Rentoza - High CPU Usage"
combiner: OR
conditions:
  - displayName: "CPU usage > 80%"
    conditionPrometheusQueryLanguage:
      query: |
        process_cpu_usage{job="rentoza-backend"} * 100 > 80
      duration: 300s
      evaluationInterval: 60s
alertStrategy:
  autoClose: 604800s
notificationChannels:
  - projects/${PROJECT_ID}/notificationChannels/${SLACK_CHANNEL_ID}
documentation:
  content: |
    ## High CPU Usage
    
    **Severity:** Warning
    
    **What's happening:**
    Cloud Run instance CPU usage exceeds 80%.
    
    **Impact:**
    Request processing may slow down. Auto-scaling should handle this.
    
    **Runbook:** See [On-Call Runbook](../docs/ON_CALL_RUNBOOK.md#high-cpu)
    
    **Actions:**
    1. Verify Cloud Run auto-scaling is working
    2. Check for runaway processes or infinite loops
    3. Review recent code changes
    4. Consider increasing max instances
  mimeType: text/markdown
```

## 5. Database Connection Pool Exhaustion
# Triggers when active connections approach pool limit

```yaml
# alert-db-connections.yaml
displayName: "Rentoza - DB Connection Pool Near Limit"
combiner: OR
conditions:
  - displayName: "Active connections > 80% of pool"
    conditionPrometheusQueryLanguage:
      query: |
        hikaricp_connections_active{job="rentoza-backend"} 
        / hikaricp_connections_max{job="rentoza-backend"} * 100 > 80
      duration: 300s
      evaluationInterval: 60s
alertStrategy:
  autoClose: 604800s
notificationChannels:
  - projects/${PROJECT_ID}/notificationChannels/${SLACK_CHANNEL_ID}
documentation:
  content: |
    ## Database Connection Pool Near Limit
    
    **Severity:** Warning
    
    **What's happening:**
    The HikariCP connection pool is more than 80% utilized.
    
    **Impact:**
    New requests may wait for connections, causing timeouts.
    
    **Runbook:** See [On-Call Runbook](../docs/ON_CALL_RUNBOOK.md#db-connections)
    
    **Actions:**
    1. Check for long-running transactions
    2. Look for connection leaks in logs
    3. Review slow query patterns
    4. Consider increasing pool size (with caution)
  mimeType: text/markdown
```

# ============================================================
# DEPLOYMENT INSTRUCTIONS
# ============================================================

# 1. Create the dashboard:
#    gcloud monitoring dashboards create --config-from-file=dashboard.json

# 2. Create notification channels first (Slack, PagerDuty):
#    gcloud alpha monitoring channels create --channel-content-from-file=slack-channel.yaml

# 3. Create alerting policies:
#    gcloud alpha monitoring policies create --policy-from-file=alert-high-error-rate.yaml

# 4. Verify metrics are being scraped:
#    - Check Cloud Run logs for Micrometer export
#    - Verify /actuator/prometheus endpoint is accessible
#    - Confirm metrics appear in Monitoring > Metrics Explorer
