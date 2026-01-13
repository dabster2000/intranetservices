#!/bin/bash

# DDD Entity Analysis Script
# Extracts metadata from all @Entity annotated classes for DDD aggregate analysis

OUTPUT_DIR="ddd-analysis-output"
mkdir -p "$OUTPUT_DIR"

echo "Starting entity analysis..."
echo "Output directory: $OUTPUT_DIR"

# Find all entity files
ENTITY_FILES=$(find src/main/java -name "*.java" -type f -exec grep -l "@Entity" {} \;)

# Initialize JSON output
echo '{
  "analysis_date": "'$(date -u +"%Y-%m-%dT%H:%M:%SZ")'",
  "total_entities": 0,
  "entities": [],
  "relationships": [],
  "event_entities": [],
  "domain_entities": []
}' > "$OUTPUT_DIR/entity-metadata.json"

ENTITY_COUNT=0
EVENT_COUNT=0

# Process each entity
for FILE in $ENTITY_FILES; do
  ENTITY_COUNT=$((ENTITY_COUNT + 1))
  CLASS_NAME=$(basename "$FILE" .java)

  # Check if it's an event entity
  if [[ "$CLASS_NAME" == *"Event"* ]]; then
    EVENT_COUNT=$((EVENT_COUNT + 1))
    echo "  [EVENT] $CLASS_NAME ($FILE)"
    continue
  fi

  echo "  [$ENTITY_COUNT] Analyzing: $CLASS_NAME"

  # Extract annotations and patterns
  HAS_ONE_TO_MANY=$(grep -c "@OneToMany" "$FILE" || echo "0")
  HAS_MANY_TO_ONE=$(grep -c "@ManyToOne" "$FILE" || echo "0")
  HAS_ONE_TO_ONE=$(grep -c "@OneToOne" "$FILE" || echo "0")
  HAS_CASCADE_ALL=$(grep -c "CascadeType.ALL" "$FILE" || echo "0")
  HAS_EAGER=$(grep -c "FetchType.EAGER" "$FILE" || echo "0")
  HAS_ORPHAN_REMOVAL=$(grep -c "orphanRemoval.*=.*true" "$FILE" || echo "0")

  # Count business methods (exclude getters/setters)
  METHOD_COUNT=$(grep -c "public.*{$" "$FILE" | grep -v "get\|set\|equals\|hashCode\|toString" || echo "0")

  # Extract UUID fields (potential foreign key references)
  UUID_FIELDS=$(grep -o "[a-z]*uuid" "$FILE" | sort -u | tr '\n' ',' | sed 's/,$//')

  # Extract package to determine bounded context
  PACKAGE=$(grep "^package" "$FILE" | sed 's/package //;s/;//')
  BOUNDED_CONTEXT=$(echo "$PACKAGE" | sed 's/dk.trustworks.intranet.//' | cut -d. -f1)

  echo "    - Package: $BOUNDED_CONTEXT"
  echo "    - @OneToMany: $HAS_ONE_TO_MANY, @ManyToOne: $HAS_MANY_TO_ONE"
  echo "    - CascadeType.ALL: $HAS_CASCADE_ALL"
  echo "    - UUID fields: $UUID_FIELDS"

done

echo ""
echo "Analysis complete!"
echo "Total entities: $ENTITY_COUNT"
echo "Event entities: $EVENT_COUNT"
echo "Domain entities: $((ENTITY_COUNT - EVENT_COUNT))"
echo ""
echo "Output files created in: $OUTPUT_DIR/"
