.PHONY: help install build test test-coverage clean run-backend run-frontend docker-build docker-up docker-down docker-logs docker-clean lint security-scan dev health-check metrics format package

help: ## Show this help message
	@echo 'Usage: make [target]'
	@echo ''
	@echo 'Available targets:'
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "  %-20s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

install: ## Install all dependencies
	@echo "Installing Java dependencies..."
	mvn clean install -DskipTests
	@echo "Installing Python dependencies..."
	pip install -r python_src/requirements.txt

build: ## Build the application
	@echo "Building backend..."
	mvn clean package -DskipTests

test: ## Run all tests
	@echo "Running tests..."
	mvn test

test-coverage: ## Run tests with coverage report
	@echo "Running tests with coverage..."
	mvn clean test jacoco:report
	@echo "Coverage report: target/site/jacoco/index.html"

clean: ## Clean build artifacts
	@echo "Cleaning..."
	mvn clean

run-backend: ## Run Spring Boot backend
	@echo "Starting backend..."
	mvn spring-boot:run

run-frontend: ## Run Streamlit frontend
	@echo "Starting frontend..."
	streamlit run python_src/trading_dashboard.py

docker-build: ## Build Docker images
	@echo "Building Docker images..."
	docker compose build

docker-up: ## Start all services with Docker Compose
	@echo "Starting services..."
	docker compose up --build -d --wait
	@echo "Services started. Access:"
	@echo "  Frontend: http://localhost:8501"
	@echo "  Backend:  http://localhost:8080"
	@echo "  Swagger:  http://localhost:8080/swagger-ui.html"

docker-down: ## Stop all services
	@echo "Stopping services..."
	docker compose down

docker-logs: ## View Docker logs
	docker compose logs -f

docker-clean: ## Remove all containers, volumes, and images
	@echo "Cleaning Docker resources..."
	docker compose down -v
	docker rmi atlas-trade-api:local atlas-control-room:local 2>/dev/null || true

lint: ## Run code quality checks
	@echo "Running checkstyle..."
	mvn checkstyle:check

security-scan: ## Run security vulnerability scan
	@echo "Running security scan..."
	mvn dependency-check:check

dev: ## Start development environment
	@echo "Starting development environment..."
	@make -j2 run-backend run-frontend

health-check: ## Check application health
	@echo "Checking backend health..."
	@curl -s http://localhost:8080/actuator/health | jq .
	@echo "\nChecking MongoDB..."
	@curl -s http://localhost:8080/actuator/health/mongo | jq .
	@echo "\nChecking Redis..."
	@curl -s http://localhost:8080/actuator/health/redis | jq .

metrics: ## View application metrics
	@curl -s http://localhost:8080/actuator/metrics | jq .

format: ## Format code (Java)
	@echo "Formatting Java code..."
	mvn formatter:format

package: build ## Package application for deployment
	@echo "Application packaged: target/atlas-trade-control-room-1.0.0-SNAPSHOT.jar"
