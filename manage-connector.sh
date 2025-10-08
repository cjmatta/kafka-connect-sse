#!/bin/bash
#
# manage-connector.sh
#
# A unified script for managing Server Sent Events connector in Confluent Cloud
# Combines upload-to-confluent-cloud.sh and create_connector_ccloud.sh
# Adds capabilities to delete the connector plugin or cluster
# Uses environment variables for credentials (works with op run)
#

# Default values - customize these as needed
PLUGIN_NAME="kafka-connect-sse"
PLUGIN_FILE="$(pwd)/target/components/packages/cjmatta-kafka-connect-sse-1.4.zip"
CONNECTOR_CLASS="com.github.cjmatta.kafka.connect.sse.ServerSentEventsSourceConnector"
CLUSTER_ID="lkc-zm1p10"
CLOUD_PROVIDER="aws"
CONNECTOR_DESCRIPTION="A Kafka Connect source connector for Server Sent Events"
DOCUMENTATION_LINK="https://github.com/cjmatta/kafka-connect-sse"
CONNECTOR_TYPE="Source"
CONNECTOR_NAME="Wikipedia SSE"
TASKS_MAX="1"
SSE_URI="https://stream.wikimedia.org/v2/stream/recentchange"
TOPIC="wikimedia-raw"
CONTACT_INFO="admin@example.com"
COMPRESSION_ENABLED="true"

# Dynamic plugin ID - will be set after upload
PLUGIN_ID=""

# Format text styles
BOLD="\033[1m"
RED="\033[31m"
GREEN="\033[32m"
YELLOW="\033[33m"
BLUE="\033[34m"
RESET="\033[0m"

# Configuration template embedded in script
# This will be filled in with dynamic values including plugin ID
# and credentials from environment variables
CONFIG_TEMPLATE='{
    "name": "{{CONNECTOR_NAME}}",
    "config": {
        "connector.class": "{{CONNECTOR_CLASS}}",
        "kafka.auth.mode": "KAFKA_API_KEY",
        "kafka.api.key": "{{KAFKA_API_KEY}}",
        "kafka.api.secret": "{{KAFKA_API_SECRET}}",
        "tasks.max": "{{TASKS_MAX}}",
        "confluent.custom.plugin.id": "{{PLUGIN_ID}}",
        "confluent.connector.type": "CUSTOM",
        "confluent.custom.connection.endpoints": "stream.wikimedia.org:443",
        "confluent.custom.schema.registry.auto": "true",
        "key.converter": "io.confluent.connect.json.JsonSchemaConverter",
        "user.agent": "{{USER_AGENT}}",
        "contact.info": "{{CONTACT_INFO}}",
        "compression.enabled": "{{COMPRESSION_ENABLED}}",
        "sse.uri": "{{SSE_URI}}",
        "topic": "{{TOPIC}}",
        "value.converter": "io.confluent.connect.json.JsonSchemaConverter"
    }
}'

# Function to generate configuration with injected values
function generate_config {
    local config_file="$1"
    
    # Check for environment variables
    if [ -z "$KAFKA_API_KEY" ] || [ -z "$KAFKA_API_SECRET" ]; then
        echo -e "${YELLOW}Environment variables KAFKA_API_KEY and/or KAFKA_API_SECRET are not set.${RESET}"
        echo -e "These can be set using:"
        echo -e "  export KAFKA_API_KEY=your_api_key"
        echo -e "  export KAFKA_API_SECRET=your_api_secret"
        echo -e "Or by using 1Password CLI:"
        echo -e "  op run --env-file=.env -- $0 $COMMAND [options]"
        echo
        
        # Ask for credentials interactively if not in environment
        read -p "Enter Kafka API Key: " KAFKA_API_KEY
        read -s -p "Enter Kafka API Secret: " KAFKA_API_SECRET
        echo
    else
        echo -e "${GREEN}Using API credentials from environment variables.${RESET}"
    fi
    
    # Check if plugin ID is available
    if [ -z "$PLUGIN_ID" ]; then
        echo -e "${YELLOW}Warning: No plugin ID provided. The connector might not work correctly.${RESET}"
        echo -e "You should upload the plugin first to get a plugin ID."
        read -p "Do you want to continue anyway? (y/n) " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            echo -e "${YELLOW}Operation cancelled.${RESET}"
            exit 0
        fi
        
        # Ask for plugin ID if continuing
        read -p "Please enter the plugin ID manually: " PLUGIN_ID
    fi
    
    # Replace placeholders in template
    local config=$(echo "$CONFIG_TEMPLATE" | \
        sed "s|{{CONNECTOR_NAME}}|$CONNECTOR_NAME|g" | \
        sed "s|{{CONNECTOR_CLASS}}|$CONNECTOR_CLASS|g" | \
        sed "s|{{KAFKA_API_KEY}}|$KAFKA_API_KEY|g" | \
        sed "s|{{KAFKA_API_SECRET}}|$KAFKA_API_SECRET|g" | \
        sed "s|{{TASKS_MAX}}|$TASKS_MAX|g" | \
        sed "s|{{PLUGIN_ID}}|$PLUGIN_ID|g" | \
        sed "s|{{SSE_URI}}|$SSE_URI|g" | \
        sed "s|{{USER_AGENT}}|$USER_AGENT|g" | \
        sed "s|{{CONTACT_INFO}}|$CONTACT_INFO|g" | \
        sed "s|{{COMPRESSION_ENABLED}}|$COMPRESSION_ENABLED|g" | \
        sed "s|{{TOPIC}}|$TOPIC|g")
    
    # Write config to file
    echo "$config" > "$config_file"
    echo -e "${GREEN}Configuration file generated at: $config_file${RESET}"
    
    # Clear sensitive variables
    KAFKA_API_KEY=""
    KAFKA_API_SECRET=""
}

# Function to display usage information
function show_usage {
    echo -e "${BOLD}USAGE:${RESET}"
    echo -e "  $0 [COMMAND] [OPTIONS]"
    echo
    echo -e "${BOLD}COMMANDS:${RESET}"
    echo -e "  ${GREEN}upload${RESET}      Upload the connector plugin to Confluent Cloud"
    echo -e "  ${GREEN}create${RESET}      Create a connector instance on a cluster"
    echo -e "  ${GREEN}delete-plugin${RESET}  Delete the connector plugin from Confluent Cloud"
    echo -e "  ${GREEN}delete-connector${RESET} Delete the connector instance from a cluster"
    echo -e "  ${GREEN}status${RESET}      Check the status of connectors and plugins"
    echo -e "  ${GREEN}help${RESET}        Display this help message"
    echo
    echo -e "${BOLD}OPTIONS:${RESET}"
    echo -e "  ${BLUE}--name${RESET} NAME        Connector plugin name (default: $PLUGIN_NAME)"
    echo -e "  ${BLUE}--file${RESET} FILE        Path to connector plugin file (default: $PLUGIN_FILE)"
    echo -e "  ${BLUE}--class${RESET} CLASS      Connector class name (default: $CONNECTOR_CLASS)"
    echo -e "  ${BLUE}--cluster${RESET} ID       Cluster ID (default: $CLUSTER_ID)"
    echo -e "  ${BLUE}--cloud${RESET} PROVIDER   Cloud provider (default: $CLOUD_PROVIDER)"
    echo -e "  ${BLUE}--plugin-id${RESET} ID     Plugin ID (for delete-plugin command or manual specification)"
    echo -e "  ${BLUE}--connector-id${RESET} ID  Connector ID (for delete-connector command)"
    echo -e "  ${BLUE}--topic${RESET} TOPIC      Topic name for the connector (default: $TOPIC)"
    echo -e "  ${BLUE}--sse-uri${RESET} URI      Server-Sent Events URI (default: $SSE_URI)"
    echo -e "  ${BLUE}--tasks${RESET} NUM        Maximum number of tasks (default: $TASKS_MAX)"
    echo
    echo -e "${BOLD}ENVIRONMENT VARIABLES:${RESET}"
    echo -e "  ${GREEN}KAFKA_API_KEY${RESET}     Confluent Cloud API Key"
    echo -e "  ${GREEN}KAFKA_API_SECRET${RESET}  Confluent Cloud API Secret"
    echo
    echo -e "${BOLD}EXAMPLE WITH 1PASSWORD:${RESET}"
    echo -e "  op run --env-file=.env -- $0 create"
    echo -e "  (where .env contains KAFKA_API_KEY and KAFKA_API_SECRET)"
}

# Function to upload connector plugin to Confluent Cloud
function upload_plugin {
    echo -e "${BOLD}Uploading connector plugin to Confluent Cloud...${RESET}"
    echo -e "Plugin name: ${GREEN}$PLUGIN_NAME${RESET}"
    echo -e "Plugin file: ${GREEN}$PLUGIN_FILE${RESET}"
    echo -e "Connector class: ${GREEN}$CONNECTOR_CLASS${RESET}"
    echo -e "Cloud provider: ${GREEN}$CLOUD_PROVIDER${RESET}"
    
    # Check if plugin file exists
    if [ ! -f "$PLUGIN_FILE" ]; then
        echo -e "${RED}Error: Plugin file not found: $PLUGIN_FILE${RESET}"
        echo -e "Make sure you've built the project with 'mvn clean package'"
        exit 1
    fi
    
    # Run confluent CLI command to create custom plugin
    local result=$(confluent connect custom-plugin create "$PLUGIN_NAME" \
        --plugin-file "$PLUGIN_FILE" \
        --connector-class "$CONNECTOR_CLASS" \
        --description "$CONNECTOR_DESCRIPTION" \
        --documentation-link "$DOCUMENTATION_LINK" \
        --connector-type "$CONNECTOR_TYPE" \
        --sensitive-properties http.basic.auth.password \
        --cloud "$CLOUD_PROVIDER" 2>&1)
    
    # Check if command succeeded
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}Plugin uploaded successfully${RESET}"
        
        # Extract plugin ID from output
        # The plugin ID typically appears in format "ccp-xxxxx"
        PLUGIN_ID=$(echo "$result" | grep -o 'ccp-[a-z0-9]*' | head -1)
        
        if [ -n "$PLUGIN_ID" ]; then
            echo -e "${GREEN}Extracted plugin ID: $PLUGIN_ID${RESET}"
        else
            echo -e "${YELLOW}Warning: Could not extract plugin ID automatically.${RESET}"
            echo -e "Output from command: $result"
            
            # Prompt for manual entry if extraction failed
            read -p "Please enter the plugin ID manually (format: ccp-xxxxx): " PLUGIN_ID
        fi
    else
        echo -e "${RED}Failed to upload plugin${RESET}"
        echo -e "Error: $result"
        exit 1
    fi
}

# Function to create connector instance on a cluster
function create_connector {
    echo -e "${BOLD}Creating connector instance on cluster...${RESET}"
    echo -e "Cluster ID: ${GREEN}$CLUSTER_ID${RESET}"
    
    # Generate a temporary config file
    local config_file="/tmp/connector-config-$$.json"
    generate_config "$config_file"
    
    echo -e "Using configuration file: ${GREEN}$config_file${RESET}"
    
    # Run confluent CLI command to create connector
    confluent connect cluster create --config-file "$config_file" --cluster "$CLUSTER_ID"
    
    # Check if command succeeded
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}Connector created successfully${RESET}"
        
        # Cleanup temporary config file with sensitive data
        rm -f "$config_file"
    else
        echo -e "${RED}Failed to create connector${RESET}"
        
        # Cleanup temporary config file with sensitive data even on failure
        rm -f "$config_file"
        exit 1
    fi
}

# Function to delete connector plugin from Confluent Cloud
function delete_plugin {
    # Check if plugin ID is provided
    if [ -z "$PLUGIN_ID" ]; then
        # List available plugins and ask user to select one
        echo -e "${YELLOW}No plugin ID provided. Listing available plugins...${RESET}"
        confluent connect custom-plugin list
        echo
        echo -e "Please run the command again with --plugin-id parameter:"
        echo -e "  $0 delete-plugin --plugin-id <PLUGIN_ID>"
        exit 1
    fi
    
    echo -e "${BOLD}Deleting connector plugin from Confluent Cloud...${RESET}"
    echo -e "Plugin ID: ${GREEN}$PLUGIN_ID${RESET}"
    
    # Ask for confirmation
    read -p "Are you sure you want to delete this plugin? (y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo -e "${YELLOW}Delete operation cancelled${RESET}"
        exit 0
    fi
    
    # Run confluent CLI command to delete custom plugin
    confluent connect custom-plugin delete "$PLUGIN_ID"
    
    # Check if command succeeded
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}Plugin deleted successfully${RESET}"
    else
        echo -e "${RED}Failed to delete plugin${RESET}"
        exit 1
    fi
}

# Function to delete connector instance from a cluster
function delete_connector {
    # Check if connector ID is provided
    if [ -z "$CONNECTOR_ID" ]; then
        # List available connectors and ask user to select one
        echo -e "${YELLOW}No connector ID provided. Listing available connectors...${RESET}"
        confluent connect cluster list --cluster "$CLUSTER_ID"
        echo
        echo -e "Please run the command again with --connector-id parameter:"
        echo -e "  $0 delete-connector --connector-id <CONNECTOR_ID> --cluster <CLUSTER_ID>"
        exit 1
    fi
    
    echo -e "${BOLD}Deleting connector instance from cluster...${RESET}"
    echo -e "Connector ID: ${GREEN}$CONNECTOR_ID${RESET}"
    echo -e "Cluster ID: ${GREEN}$CLUSTER_ID${RESET}"
    
    # Ask for confirmation
    read -p "Are you sure you want to delete this connector? (y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo -e "${YELLOW}Delete operation cancelled${RESET}"
        exit 0
    fi
    
    # Run confluent CLI command to delete connector
    confluent connect cluster delete "$CONNECTOR_ID" --cluster "$CLUSTER_ID"
    
    # Check if command succeeded
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}Connector deleted successfully${RESET}"
    else
        echo -e "${RED}Failed to delete connector${RESET}"
        exit 1
    fi
}

# Function to check connector/plugin status
function check_status {
    echo -e "${BOLD}Checking status of connectors and plugins...${RESET}"
    
    echo -e "\n${BOLD}Available clusters:${RESET}"
    confluent connect cluster list
    
    echo -e "\n${BOLD}Available plugins:${RESET}"
    confluent connect custom-plugin list
    
    if [ -n "$CLUSTER_ID" ]; then
        echo -e "\n${BOLD}Connectors on cluster $CLUSTER_ID:${RESET}"
        confluent connect cluster list --cluster "$CLUSTER_ID"
    fi
}

# Parse command line arguments
COMMAND=""
PLUGIN_ID=""
CONNECTOR_ID=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        upload|create|delete-plugin|delete-connector|status|help)
            COMMAND="$1"
            ;;
        --name)
            PLUGIN_NAME="$2"
            shift
            ;;
        --file)
            PLUGIN_FILE="$2"
            shift
            ;;
        --class)
            CONNECTOR_CLASS="$2"
            shift
            ;;
        --cluster)
            CLUSTER_ID="$2"
            shift
            ;;
        --cloud)
            CLOUD_PROVIDER="$2"
            shift
            ;;
        --plugin-id)
            PLUGIN_ID="$2"
            shift
            ;;
        --connector-id)
            CONNECTOR_ID="$2"
            shift
            ;;
        --topic)
            TOPIC="$2"
            shift
            ;;
        --sse-uri)
            SSE_URI="$2"
            shift
            ;;
        --tasks)
            TASKS_MAX="$2"
            shift
            ;;
        *)
            echo -e "${RED}Unknown option: $1${RESET}"
            show_usage
            exit 1
            ;;
    esac
    shift
done

# Execute the appropriate function based on the command
case "$COMMAND" in
    upload)
        upload_plugin
        ;;
    create)
        create_connector
        ;;
    delete-plugin)
        delete_plugin
        ;;
    delete-connector)
        delete_connector
        ;;
    status)
        check_status
        ;;
    help|"")
        show_usage
        ;;
    *)
        echo -e "${RED}Unknown command: $COMMAND${RESET}"
        show_usage
        exit 1
        ;;
esac

exit 0