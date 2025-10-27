#!/usr/bin/env bash

# Setup script for Hubitat development environment
# Installs Groovy 2.4.21 via SDKMAN

set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if SDKMAN is installed
if [ ! -d "$HOME/.sdkman" ]; then
    log_info "Installing SDKMAN..."
    curl -s "https://get.sdkman.io" | bash

    # Source SDKMAN
    source "$HOME/.sdkman/bin/sdkman-init.sh"

    log_info "SDKMAN installed successfully"
else
    log_info "SDKMAN already installed"
    source "$HOME/.sdkman/bin/sdkman-init.sh"
fi

# Install Groovy 2.4.19 (matches Docker/test environment version)
GROOVY_VERSION="2.4.19"
log_info "Checking Groovy installation..."

if ! groovy -version 2>/dev/null | grep -q "Groovy Version: ${GROOVY_VERSION}"; then
    log_info "Installing Groovy ${GROOVY_VERSION}..."
    # Answer 'Y' to make it default automatically
    echo "Y" | sdk install groovy "${GROOVY_VERSION}"
else
    log_info "Groovy ${GROOVY_VERSION} already installed"
fi

# Make sure we're using the correct version
sdk default groovy "${GROOVY_VERSION}" 2>/dev/null || true
log_info "Active Groovy version: $(groovy -version)"

# Note: CodeNarc linting is not set up automatically due to compatibility issues
# between Groovy 2.4.x and recent CodeNarc versions compiled with Java 21.
# Linting is optional - tests and deployment work without it.

log_info ""
log_info "Setup complete!"
log_info ""
log_info "To use Groovy in your current shell, run:"
log_info "  source ~/.sdkman/bin/sdkman-init.sh"
log_info ""
log_info "Or add to your ~/.bashrc or ~/.zshrc:"
log_info "  export SDKMAN_DIR=\"\$HOME/.sdkman\""
log_info "  [[ -s \"\$SDKMAN_DIR/bin/sdkman-init.sh\" ]] && source \"\$SDKMAN_DIR/bin/sdkman-init.sh\""
log_info ""
log_info "Available commands:"
log_info "  make test      - Run all tests"
log_info "  make lint      - Run CodeNarc linter"
log_info "  make validate  - Validate code syntax"
log_info "  make deploy    - Deploy to Hubitat hub"
