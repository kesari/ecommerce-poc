#!/bin/bash
set -euo pipefail

BOOTSTRAP="${BOOTSTRAP:?BOOTSTRAP env var required}"
TOPICS_FILE="${TOPICS_FILE:?TOPICS_FILE env var required}"
KAFKA_BIN=/opt/kafka/bin

mapfile -t rows < <(awk '
  /^[[:space:]]+-[[:space:]]+name:/ { name = $NF }
  /^[[:space:]]+partitions:/ { print name, $2 }
' "$TOPICS_FILE")

if [ "${#rows[@]}" -eq 0 ]; then
  echo "no topics parsed from $TOPICS_FILE" >&2
  exit 1
fi

create_topic() {
  local topic="$1"
  local partitions="$2"
  "$KAFKA_BIN/kafka-topics.sh" \
    --bootstrap-server "$BOOTSTRAP" \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions "$partitions" \
    --replication-factor 1
}

for row in "${rows[@]}"; do
  name="${row% *}"
  partitions="${row#* }"
  create_topic "$name" "$partitions"
  create_topic "$name.retry.1" 1
  create_topic "$name.retry.2" 1
  create_topic "$name.dlq" 1
done

"$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --list | sort
