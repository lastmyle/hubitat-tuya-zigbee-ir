.PHONY: test test-local test-docker lint deploy deploy-driver deploy-app validate setup install help

GROOVY := $(shell which groovy 2>/dev/null || echo "groovy")
CODENARC_JAR := $(HOME)/.codenarc/CodeNarc-2.2.0.jar
GMETRICS_JAR := $(HOME)/.codenarc/GMetrics-1.1.jar
LOG4J_JAR := $(HOME)/.codenarc/log4j-1.2.17.jar
SLF4J_JAR := $(HOME)/.codenarc/slf4j-api-1.7.32.jar

help:
	@echo "Available targets:"
	@echo "  make setup         - Install Groovy 2.4.x via SDKMAN"
	@echo "  make test          - Run all tests (uses Docker)"
	@echo "  make test-local    - Run tests with local Groovy CLI"
	@echo "  make test-docker   - Run tests with Docker"
	@echo "  make lint          - Run CodeNarc linter"
	@echo "  make validate      - Validate code syntax"
	@echo "  make deploy        - Deploy both driver and app to Hubitat hub"
	@echo "  make deploy-driver - Deploy only the driver"
	@echo "  make deploy-app    - Deploy only the app"
	@echo "  make install       - Run tests, validate, and deploy (full workflow)"
	@echo "  make help          - Show this help message"
	@echo ""
	@echo "First time setup:"
	@echo "  1. make setup"
	@echo "  2. source ~/.sdkman/bin/sdkman-init.sh"
	@echo "  3. make test"
	@echo ""
	@echo "Development workflow:"
	@echo "  1. make test        # Run tests"
	@echo "  2. make lint        # Run linter"
	@echo "  3. make validate    # Check syntax"
	@echo "  4. make deploy      # Deploy to hub"

setup:
	@./bin/setup.sh

test: test-docker

test-local:
	@if ! command -v groovy >/dev/null 2>&1; then \
		echo "Error: Groovy not found. Run 'make setup' first or use 'make test-docker'."; \
		exit 1; \
	fi
	@echo "Running tests with local Groovy..."
	@$(GROOVY) -cp test test/all.groovy

test-docker:
	@echo "Running tests with Docker (Groovy 2.4)..."
	@docker run --rm -v "$(PWD):/workspace" -w /workspace groovy:2.4-jre groovy -cp test test/all.groovy

lint:
	@if ! command -v groovy >/dev/null 2>&1; then \
		echo "Error: Groovy not found. Run 'make setup' first."; \
		exit 1; \
	fi
	@mkdir -p $(HOME)/.codenarc
	@if [ ! -f "$(CODENARC_JAR)" ]; then \
		echo "Downloading CodeNarc 2.2.0 (compatible with Groovy 2.4.x)..."; \
		curl -sL -o $(CODENARC_JAR) https://repo1.maven.org/maven2/org/codenarc/CodeNarc/2.2.0/CodeNarc-2.2.0.jar; \
	fi
	@if [ ! -f "$(GMETRICS_JAR)" ]; then \
		echo "Downloading GMetrics 1.1..."; \
		curl -sL -o $(GMETRICS_JAR) https://repo1.maven.org/maven2/org/gmetrics/GMetrics/1.1/GMetrics-1.1.jar; \
	fi
	@if [ ! -f "$(LOG4J_JAR)" ]; then \
		echo "Downloading Log4j 1.2.17..."; \
		curl -sL -o $(LOG4J_JAR) https://repo1.maven.org/maven2/log4j/log4j/1.2.17/log4j-1.2.17.jar; \
	fi
	@if [ ! -f "$(SLF4J_JAR)" ]; then \
		echo "Downloading SLF4J 1.7.32..."; \
		curl -sL -o $(SLF4J_JAR) https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.32/slf4j-api-1.7.32.jar; \
	fi
	@echo "Running CodeNarc 2.2.0 linter..."
	@groovy -cp "$(CODENARC_JAR):$(GMETRICS_JAR):$(LOG4J_JAR):$(SLF4J_JAR)" bin/codenarc-runner.groovy

validate:
	@./bin/deploy.sh validate

deploy:
	@./bin/deploy.sh both

deploy-driver:
	@./bin/deploy.sh driver

deploy-app:
	@./bin/deploy.sh app

install: test validate deploy
	@echo ""
	@echo "Installation complete!"
	@echo ""
	@echo "Next steps:"
	@echo "  1. Paste the code into your Hubitat hub (already in clipboard)"
	@echo "  2. Go to Apps -> Add User App -> HVAC Setup Wizard"
	@echo "  3. Follow the wizard to configure your HVAC"