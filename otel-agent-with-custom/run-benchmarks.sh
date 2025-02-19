#!/usr/bin/env bash
set -e

AGENT_JAR="my-standalone-agent/target/my-standalone-agent-1.0.0.jar"
JMH_JAR="performance-tests/target/performance-tests-1.0.0.jar"

# 1) Build everything
mvn clean package -DskipTests

if [ ! -f "$AGENT_JAR" ]; then
  echo "[ERROR] Agent jar not found: $AGENT_JAR"
  exit 1
fi
if [ ! -f "$JMH_JAR" ]; then
  echo "[ERROR] JMH jar not found: $JMH_JAR"
  exit 1
fi

############################################################
# Helper function to run a scenario
############################################################
run_scenario() {
  local scenario="$1"
  local agent_arg="$2"
  local csv_file="$3"
  local log_file="$4"

  echo
  echo "==========================================================="
  echo "Running scenario: $scenario"
  echo "==========================================================="

  # Remove old logs if present
  rm -f "$csv_file" "$log_file"

  # We’ll pipe console output to a log file so we can parse OTel CPU/mem lines
  #
  # Updated JMH parameters:
  #  -bm thrpt    => measure throughput (ops/s)
  #  -wi 3        => 3 warmup iterations
  #  -i 5         => 5 measurement iterations
  #  -f 2         => 2 forks (each scenario in a fresh JVM)
  #  -t 4         => 4 threads
  #  -w 2s        => 2s warmup time each iteration
  #  -r 3s        => 3s measurement time each iteration
  #  -rf csv      => output CSV
  #  -rff csv_file => store results in that CSV
  #
  java $agent_arg -jar "$JMH_JAR" \
    -bm thrpt \
    -wi 3 \
    -i 5 \
    -w 2s \
    -r 3s \
    -t 4 \
    -f 2 \
    -rf csv \
    -rff "$csv_file" \
    > "$log_file" 2>&1
}

# 2) No agent
run_scenario "NoAgent" "" "no_agent.csv" "no_agent.log"

# 3) Custom agent
run_scenario "CustomAgent" "-javaagent:$AGENT_JAR" "custom_agent.csv" "custom_agent.log"

# 4) Official agent only (optional if we have OTEL_JAVAAGENT env set)
if [ -n "$OTEL_JAVAAGENT" ] && [ -f "$OTEL_JAVAAGENT" ]; then
  run_scenario "OfficialAgent" "-javaagent:$OTEL_JAVAAGENT" "official_agent.csv" "official_agent.log"
fi

# 5) Both
if [ -n "$OTEL_JAVAAGENT" ] && [ -f "$OTEL_JAVAAGENT" ]; then
  run_scenario "BothAgents" "-javaagent:$OTEL_JAVAAGENT -javaagent:$AGENT_JAR" \
    "both_agents.csv" "both_agents.log"
fi

########################################################
# PARSE RESULTS
########################################################

# Parse JMH throughput from CSV
get_thrpt_from_csv() {
  local csv_file="$1"
  local thrpt_line
  # Adjust the benchmark class/method if needed (currently matching "OrderServiceBenchmark.benchmarkProcessOrder")
  thrpt_line=$(grep -m1 "OrderServiceBenchmark.benchmarkProcessOrder" "$csv_file" || true)
  if [ -z "$thrpt_line" ]; then
    echo "0"
    return
  fi
  # parse the 5th column for Score
  local score
  score=$(echo "$thrpt_line" | cut -d, -f5)
  echo "$score"
}

# parse CPU from final lines of log: metric(system.process.cpu.load) = 0.28
get_cpu_from_log() {
  local log_file="$1"
  local cpu_line
  cpu_line=$(grep "metric(system.process.cpu.load)" "$log_file" | tail -n1 | awk -F'= ' '{print $2}')
  if [ -z "$cpu_line" ]; then
    echo "0.0"
  else
    echo "$cpu_line"
  fi
}

# parse memory usage from final lines of log: metric(runtime.memory.used) = <bytes>
get_mem_from_log() {
  local log_file="$1"
  local mem_line
  mem_line=$(grep "metric(runtime.memory.used)" "$log_file" | tail -n1 | awk -F'= ' '{print $2}')
  if [ -z "$mem_line" ]; then
    echo "0"
  else
    # convert from bytes to MB
    echo "scale=2; $mem_line / 1048576" | bc
  fi
}

# gather data
NO_AGENT_THRPT=$(get_thrpt_from_csv "no_agent.csv")
NO_AGENT_CPU=$(get_cpu_from_log "no_agent.log")
NO_AGENT_MEM=$(get_mem_from_log "no_agent.log")

CUSTOM_THRPT=$(get_thrpt_from_csv "custom_agent.csv")
CUSTOM_CPU=$(get_cpu_from_log "custom_agent.log")
CUSTOM_MEM=$(get_mem_from_log "custom_agent.log")

# optional official
if [ -f "official_agent.csv" ]; then
  OFFICIAL_THRPT=$(get_thrpt_from_csv "official_agent.csv")
  OFFICIAL_CPU=$(get_cpu_from_log "official_agent.log")
  OFFICIAL_MEM=$(get_mem_from_log "official_agent.log")
fi

# optional both
if [ -f "both_agents.csv" ]; then
  BOTH_THRPT=$(get_thrpt_from_csv "both_agents.csv")
  BOTH_CPU=$(get_cpu_from_log "both_agents.log")
  BOTH_MEM=$(get_mem_from_log "both_agents.log")
fi

###############################################
# compute deltas for Custom Agent scenario
###############################################
CPU_DELTA=$(awk -v c="$CUSTOM_CPU" -v n="$NO_AGENT_CPU" 'BEGIN {print c - n}')
MEM_DELTA=$(awk -v c="$CUSTOM_MEM" -v n="$NO_AGENT_MEM" 'BEGIN {print c - n}')
THRPT_DELTA=$(awk -v c="$CUSTOM_THRPT" -v n="$NO_AGENT_THRPT" 'BEGIN {print c - n}')

# compute % overhead => difference in throughput from NoAgent
PERCENT_OVERHEAD="0"
if [ "$(echo "$NO_AGENT_THRPT > 0" | bc)" -eq 1 ]; then
  PERCENT_OVERHEAD=$(
    awk -v dt="$THRPT_DELTA" -v nt="$NO_AGENT_THRPT" '
    BEGIN {
      if (nt == 0) {
        printf "%s", 0
        exit
      }
      # overhead = -(dt / nt)*100
      # negative dt => overhead is positive
      overhead = ( -dt / nt ) * 100
      printf "%.1f", overhead
    }'
  )
fi

echo
echo "Scenario                JMH Throughput (ops/s)   CPU load   Memory used (MB)"
echo "No Agent               $NO_AGENT_THRPT                           $NO_AGENT_CPU              $NO_AGENT_MEM"
echo "Custom Agent           $CUSTOM_THRPT                           $CUSTOM_CPU              $CUSTOM_MEM"
echo "Delta                  $THRPT_DELTA ops/s ($PERCENT_OVERHEAD% ↓)      $CPU_DELTA         $MEM_DELTA"
echo

############################################################
# Official Agent
############################################################
if [ -n "$OFFICIAL_THRPT" ]; then
  OFFICIAL_CPU_DELTA=$(awk -v o="$OFFICIAL_CPU" -v n="$NO_AGENT_CPU" 'BEGIN {print o - n}')
  OFFICIAL_MEM_DELTA=$(awk -v o="$OFFICIAL_MEM" -v n="$NO_AGENT_MEM" 'BEGIN {print o - n}')
  OFFICIAL_THRPT_DELTA=$(awk -v o="$OFFICIAL_THRPT" -v n="$NO_AGENT_THRPT" 'BEGIN {print o - n}')

  OFFICIAL_OVERHEAD="0"
  if [ "$(echo "$NO_AGENT_THRPT > 0" | bc)" -eq 1 ]; then
    OFFICIAL_OVERHEAD=$(
      awk -v dt="$OFFICIAL_THRPT_DELTA" -v nt="$NO_AGENT_THRPT" '
      BEGIN {
        if (nt == 0) {
          printf "%s", 0
          exit
        }
        overhead = ( -dt / nt ) * 100
        printf "%.1f", overhead
      }'
    )
  fi

  echo "Official Agent         $OFFICIAL_THRPT           $OFFICIAL_CPU    $OFFICIAL_MEM"
  echo "Delta vs NoAgent       $OFFICIAL_THRPT_DELTA ops/s ($OFFICIAL_OVERHEAD% ↓)   $OFFICIAL_CPU_DELTA     $OFFICIAL_MEM_DELTA"
  echo
fi

############################################################
# Both Agents
############################################################
if [ -n "$BOTH_THRPT" ]; then
  BOTH_CPU_DELTA=$(awk -v b="$BOTH_CPU" -v n="$NO_AGENT_CPU" 'BEGIN {print b - n}')
  BOTH_MEM_DELTA=$(awk -v b="$BOTH_MEM" -v n="$NO_AGENT_MEM" 'BEGIN {print b - n}')
  BOTH_THRPT_DELTA=$(awk -v b="$BOTH_THRPT" -v n="$NO_AGENT_THRPT" 'BEGIN {print b - n}')

  BOTH_OVERHEAD="0"
  if [ "$(echo "$NO_AGENT_THRPT > 0" | bc)" -eq 1 ]; then
    BOTH_OVERHEAD=$(
      awk -v dt="$BOTH_THRPT_DELTA" -v nt="$NO_AGENT_THRPT" '
      BEGIN {
        if (nt == 0) {
          printf "%s", 0
          exit
        }
        overhead = ( -dt / nt ) * 100
        printf "%.1f", overhead
      }'
    )
  fi

  echo "Both Agents            $BOTH_THRPT        $BOTH_CPU       $BOTH_MEM"
  echo "Delta vs NoAgent       $BOTH_THRPT_DELTA ops/s ($BOTH_OVERHEAD% ↓)  $BOTH_CPU_DELTA   $BOTH_MEM_DELTA"
  echo
fi

echo "[DONE] Summary of overhead complete!"
