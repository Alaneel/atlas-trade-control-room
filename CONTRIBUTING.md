# Contributing to Atlas Trade Control Room

Thank you for considering contributing to Atlas Trade Control Room!

## Code of Conduct

Please be respectful and constructive in all interactions.

## How to Contribute

### Reporting Bugs

1. Check if the bug has already been reported
2. Use the bug report template
3. Include:
   - Steps to reproduce
   - Expected behavior
   - Actual behavior
   - Environment details

### Suggesting Features

1. Check if the feature has been suggested
2. Describe the feature clearly
3. Explain the use case and benefits

### Pull Requests

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/amazing-feature`
3. Make your changes
4. Write or update tests
5. Ensure all tests pass: `mvn test`
6. Commit with clear messages: `git commit -m 'Add amazing feature'`
7. Push to your fork: `git push origin feature/amazing-feature`
8. Open a Pull Request

### Code Style

- Follow existing code style
- Use meaningful variable names
- Add comments for complex logic
- Keep functions small and focused

### Testing

- Write unit tests for new code
- Maintain or improve code coverage
- Integration tests for new features

### Documentation

- Update README for user-facing changes
- Add JavaDoc comments for public APIs
- Update API documentation

## Development Setup

See the [README.md](README.md) for development environment setup.

## Questions?

Feel free to open an issue for any questions.

## Reliable execution changes

Use `mvn clean test` for unit and HTTP contract checks. A configured `ATLAS_MONGO_URI` must point at a disposable loopback replica set; integration tests create their own UUID databases. See `docs/RELIABLE_EXECUTION.md` for API compatibility, the fresh-database requirement and optional failure injection. `mvn verify` also enforces the scoped reliability-package coverage gate; actual integration acceptance requires the database and explicit non-skipped results.

## Explanation tests and real model opt-in

`./scripts/run-explanation-eval.sh` runs the offline rules, HTTP-contract and loopback provider-transport tests. Real Mongo read-only invariance tests run with `ATLAS_MONGO_URI` in `mvn verify` or the acceptance runner. Actual provider evaluation is excluded by default; see `docs/AI_EXPLANATIONS.md` for its bounded explicit opt-in. Never present scripted plans or provider transport fixtures as real model-quality measurements.
