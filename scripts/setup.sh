#!/bin/bash

# Script to download/copy HytaleServer.jar and Asset.zip
# Usage: ./setup-assets.sh

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Get the script directory and project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LIBS_DIR="$PROJECT_ROOT/libs"
RUN_DIR="$PROJECT_ROOT/run"

# Function to check if a string is a URL
is_url() {
    [[ "$1" =~ ^https?:// ]]
}

# Function to download or copy a file
download_or_copy() {
    local source="$1"
    local destination="$2"
    local filename="$3"
    
    if is_url "$source"; then
        echo -e "${BLUE}Downloading from URL: $source${NC}"
        if command -v curl &> /dev/null; then
            curl -L -o "$destination" "$source"
        elif command -v wget &> /dev/null; then
            wget -O "$destination" "$source"
        else
            echo -e "${RED}Error: Neither curl nor wget is available. Please install one of them.${NC}"
            exit 1
        fi
    else
        # It's a file path
        if [ ! -f "$source" ]; then
            echo -e "${RED}Error: File not found: $source${NC}"
            exit 1
        fi
        echo -e "${BLUE}Copying from: $source${NC}"
        cp "$source" "$destination"
    fi
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ Successfully copied $filename${NC}"
    else
        echo -e "${RED}✗ Failed to copy $filename${NC}"
        exit 1
    fi
}

# Create directories if they don't exist
if [ ! -d "$LIBS_DIR" ]; then
    echo -e "${YELLOW}Creating libs directory: $LIBS_DIR${NC}"
    mkdir -p "$LIBS_DIR"
fi

if [ ! -d "$RUN_DIR" ]; then
    echo -e "${YELLOW}Creating run directory: $RUN_DIR${NC}"
    mkdir -p "$RUN_DIR"
fi

# Prompt for HytaleServer.jar
echo -e "${YELLOW}Please provide the HytaleServer.jar:${NC}"
echo -e "${BLUE}You can provide either:${NC}"
echo -e "${BLUE}  - A URL (e.g., https://example.com/HytaleServer.jar)${NC}"
echo -e "${BLUE}  - A file path (e.g., /path/to/HytaleServer.jar)${NC}"
read -p "HytaleServer.jar (URL or path): " HYTALE_SERVER_INPUT

if [ -z "$HYTALE_SERVER_INPUT" ]; then
    echo -e "${RED}Error: No input provided for HytaleServer.jar${NC}"
    exit 1
fi

HYTALE_SERVER_DEST="$LIBS_DIR/HytaleServer.jar"
download_or_copy "$HYTALE_SERVER_INPUT" "$HYTALE_SERVER_DEST" "HytaleServer.jar"

# Prompt for Asset.zip
echo ""
echo -e "${YELLOW}Please provide the Asset.zip:${NC}"
echo -e "${BLUE}You can provide either:${NC}"
echo -e "${BLUE}  - A URL (e.g., https://example.com/Asset.zip)${NC}"
echo -e "${BLUE}  - A file path (e.g., /path/to/Asset.zip)${NC}"
read -p "Asset.zip (URL or path): " ASSET_ZIP_INPUT

if [ -z "$ASSET_ZIP_INPUT" ]; then
    echo -e "${RED}Error: No input provided for Asset.zip${NC}"
    exit 1
fi

ASSET_ZIP_DEST="$RUN_DIR/Asset.zip"
download_or_copy "$ASSET_ZIP_INPUT" "$ASSET_ZIP_DEST" "Asset.zip"

echo ""
echo -e "${GREEN}✓ Setup completed successfully!${NC}"
echo -e "${GREEN}  - HytaleServer.jar is in: $LIBS_DIR${NC}"
echo -e "${GREEN}  - Asset.zip is in: $RUN_DIR${NC}"
