# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Reliability and demo correctness

- Replace read/modify/save limit writes with atomic conditional MongoDB debits and atomic credits.
- Reject missing instrument attributes and nonfinite, zero, or negative trade amounts at the API boundary.
- Preserve spent capacity across restarts with stable, insert-only seed records.
- Record service rejections, inject the execution simulator for deterministic tests, and handle cancellation as failure.
- Make demo trades consume session capacity, add reset, and remove fabricated live performance metrics.
- Fix container Java argument order, frontend health probe, profile precedence, and production stdout logging.
- Add concurrent MongoDB tests, seed restart tests, trade outcome tests, and real Streamlit interaction tests.
- Smoke-test the complete Compose stack and run frontend tests in CI.

### Added
- Environment-based configuration with multiple profiles (dev, test, prod)
- Global exception handling with standardized error responses
- API documentation using Swagger/OpenAPI
- Health checks and monitoring endpoints with Spring Actuator
- Prometheus metrics integration
- Docker and Docker Compose support for containerization
- CI/CD pipeline with GitHub Actions
- Comprehensive test suite with JaCoCo coverage reporting
- Input validation using Spring Validation
- Security configuration with CORS and security headers
- Improved Python frontend with better error handling
- Structured logging configuration
- Comprehensive documentation (README, CONTRIBUTING, ARCHITECTURE)
- Makefile for common development tasks

### Changed
- Updated configuration from properties to YAML format
- Enhanced Python dashboard with environment variable configuration
- Improved error messages and user feedback

### Security
- Added Spring Security with basic configuration
- Configured API security headers; authentication and CSRF protection are not implemented
- Added security headers (XSS, frame options)
- Dependency vulnerability scanning in CI/CD

## [0.0.1-SNAPSHOT] - 2024-09-07

### Added
- Initial project setup with Spring Boot
- MongoDB integration for data persistence
- Redis caching support
- Basic trading operations (verify, execute, limit check)
- Instrument approval workflow
- Bloom filter for fast instrument verification
- Circuit breaker pattern with Resilience4j
- Multi-level caching (Caffeine + Redis)
- Streamlit dashboard for UI
- Mock data initialization

### Known Issues
- Authentication not implemented
- Limited test coverage
- No production deployment configuration
