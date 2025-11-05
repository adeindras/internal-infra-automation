#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

usage() {
  cat <<EOF
Usage: $0 [--dry-run] [--sleep N] [--config <file>] <csv-file>
CSV must have header: topic,targetrf

Options:
  --dry-run        Only print actions, don't execute changes
  --sleep N        Sleep N seconds between each update (default 0)
  --config <file>  Path to kafkactl config file (optional)
EOF
  exit 1
}

# defaults
DRY_RUN=false
SLEEP_INTERVAL=0
KAFKACTL_CONFIG=""

while [[ $# -gt 0 ]]; do
  case $1 in
    --dry-run) DRY_RUN=true; shift ;;
    --sleep) SLEEP_INTERVAL=$2; shift 2 ;;
    --config) KAFKACTL_CONFIG=$2; shift 2 ;;
    -*) usage ;;
    *) CSV_FILE=$1; shift ;;
  esac
done

[[ -v CSV_FILE ]] || usage
[[ -f "$CSV_FILE" ]] || { echo "❌ CSV file not found: $CSV_FILE"; exit 1; }

command -v kafkactl >/dev/null || { echo "❌ kafkactl not found in PATH"; exit 1; }

if [[ -n "$KAFKACTL_CONFIG" ]]; then
  [[ -f "$KAFKACTL_CONFIG" ]] || { echo "❌ Config file not found: $KAFKACTL_CONFIG"; exit 1; }
  KAFKACTL_CMD=(kafkactl -C "$KAFKACTL_CONFIG")
else
  KAFKACTL_CMD=(kafkactl)
fi

echo "👉 Processing CSV: $CSV_FILE"
echo "👉 Dry run: $DRY_RUN"
echo "👉 Sleep interval: $SLEEP_INTERVAL seconds"
[[ -n "$KAFKACTL_CONFIG" ]] && echo "👉 Using config: $KAFKACTL_CONFIG"
echo

tail -n +2 "$CSV_FILE" | sed 's/\r$//' | while IFS=, read -r topic targetrf || [[ -n "$topic" ]]; do
  topic=$(echo "$topic" | xargs)
  targetrf=$(echo "$targetrf" | xargs)

  [[ -z "$topic" || -z "$targetrf" ]] && { echo "⚠️  Skipping invalid line"; continue; }

  line=$("${KAFKACTL_CMD[@]}" get topics | awk -v t="$topic" '$1 == t {print}')
  current_rf=$(echo "$line" | awk '{print $3}')

  if [[ -z "$current_rf" ]]; then
    echo "❌ Topic '$topic' not found in cluster"
    continue
  fi

  if [[ "$current_rf" -eq "$targetrf" ]]; then
    echo "⏩ Skipping '$topic' (already RF=$current_rf)"
    continue
  fi

  if $DRY_RUN; then
    echo "📝 [Dry Run] Would update '$topic' from RF=$current_rf → RF=$targetrf"
  else
    echo "🔄 Updating '$topic' from RF=$current_rf → RF=$targetrf ..."
    if "${KAFKACTL_CMD[@]}" alter topic "$topic" --replication-factor "$targetrf"; then
      echo "✅ Updated '$topic'"
    else
      echo "❌ Failed updating '$topic'"
    fi
  fi

  [[ "$SLEEP_INTERVAL" -ge 0 ]] && sleep "$SLEEP_INTERVAL"
done
