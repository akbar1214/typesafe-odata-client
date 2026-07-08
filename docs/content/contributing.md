# Contributing

How to contribute to OData Codegen.

## Development Setup

### Prerequisites

- Java 17 or later
- Maven 3.9 or later
- Git

### Clone the Repository

```bash
git clone https://github.com/odata-codegen/odata-codegen.git
cd odata-codegen
```

### Build

```bash
mvn clean install
```

### Run Tests

```bash
mvn test
```

### Run Integration Tests

Integration tests require a running TripPin service:

```bash
mvn verify -Pintegration-tests
```

## Project Structure

```
odata-codegen/
├── odata-codegen-core/        # Parser + Code Generator
├── odata-codegen-runtime/     # Runtime library
├── odata-codegen-maven-plugin/ # Build-time code generation
└── docs/                     # Documentation (MkDocs)
```

## Development Workflow

### 1. Create a Branch

```bash
git checkout -b feature/my-feature
```

### 2. Make Changes

- Write code following existing patterns
- Add tests for new functionality
- Update documentation if needed

### 3. Run Tests

```bash
mvn test
```

### 4. Commit

```bash
git commit -m "Add my feature"
```

### 5. Push

```bash
git push origin feature/my-feature
```

### 6. Create Pull Request

Go to GitHub and create a pull request.

## Code Style

### Java

- Use Java 17+ features (records, pattern matching, etc.)
- Follow existing code patterns
- Add Javadoc for public APIs
- Keep methods focused and concise

### Testing

- Write unit tests for new code
- Add integration tests for HTTP operations
- Use descriptive test names

### Documentation

- Update README for user-facing changes
- Add how-to guides for new features
- Update reference documentation

## Reporting Issues

### Bug Reports

Include:

- Steps to reproduce
- Expected behavior
- Actual behavior
- Java version and OS
- Maven dependencies

### Feature Requests

Include:

- Use case description
- Proposed API
- Alternatives considered

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
