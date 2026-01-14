#!/bin/bash

# Script to decompile a .jar file using cfr.jar
# Usage: ./decompile.sh <path-to-jar-file>

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Get the script directory and project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CFR_JAR="$SCRIPT_DIR/cfr.jar"
OUTPUT_DIR="$PROJECT_ROOT/decompiled"

# Check if jar file argument is provided
if [ $# -eq 0 ]; then
    echo -e "${RED}Error: No .jar file specified${NC}"
    echo "Usage: $0 <path-to-jar-file>"
    exit 1
fi

JAR_FILE="$1"

# Check if the jar file exists
if [ ! -f "$JAR_FILE" ]; then
    echo -e "${RED}Error: JAR file not found: $JAR_FILE${NC}"
    exit 1
fi

# Check if cfr.jar exists
if [ ! -f "$CFR_JAR" ]; then
    echo -e "${RED}Error: cfr.jar not found at: $CFR_JAR${NC}"
    exit 1
fi

# Create output directory if it doesn't exist
if [ ! -d "$OUTPUT_DIR" ]; then
    echo -e "${YELLOW}Creating output directory: $OUTPUT_DIR${NC}"
    mkdir -p "$OUTPUT_DIR"
fi

# Get the base name of the jar file (without path and extension)
JAR_BASENAME=$(basename "$JAR_FILE" .jar)
DECOMPILE_OUTPUT="$OUTPUT_DIR/$JAR_BASENAME"

# Remove existing decompiled output if it exists
if [ -d "$DECOMPILE_OUTPUT" ]; then
    echo -e "${YELLOW}Removing existing decompiled output: $DECOMPILE_OUTPUT${NC}"
    rm -rf "$DECOMPILE_OUTPUT"
fi

echo -e "${GREEN}Decompiling $JAR_FILE...${NC}"
echo -e "${GREEN}Output directory: $DECOMPILE_OUTPUT${NC}"

# Run CFR decompiler
java -jar "$CFR_JAR" "$JAR_FILE" --outputdir "$DECOMPILE_OUTPUT"

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Decompilation completed successfully!${NC}"
    echo -e "${GREEN}Decompiled files are in: $DECOMPILE_OUTPUT${NC}"
else
    echo -e "${RED}✗ Decompilation failed${NC}"
    exit 1
fi
