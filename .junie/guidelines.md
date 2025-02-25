# Project Overview

## Introduction
Arbigent is an AI-powered Android UI testing framework that enables automated testing of Android applications through intelligent interaction simulation and scenario generation.

## Key Features
- AI-driven UI testing
- Automated scenario generation
- Device interaction simulation
- Screenshot-based analysis
- Flexible command system

## UI Flow

LauncherScreen -> MainScreen

## Project Structure
```
arbigent/
├── arbigent-core/          # Core functionality and main logic
├── arbigent-core-model/    # Data models and shared components
├── arbigent-cli/           # Command-line interface
├── arbigent-ui/            # User interface components
└── .junie/                 # Project documentation and guidelines
```

## Development Guidelines

### Code Style
- Follow Kotlin coding conventions
- Use meaningful variable and function names
- Include documentation for public APIs
- Write unit tests for new functionality

### Git Workflow
1. Create feature branches from main
2. Write descriptive commit messages
3. Submit pull requests for review
4. Ensure tests pass before merging

### Testing
#### Key Testing Practices
- Use Kotlin test framework with coroutines (`runTest`, `advanceUntilIdle`)
- Write unit tests for core components
- Test YAML configurations and backward compatibility
- Implement fake objects (FakeDevice, FakeAi) for dependencies

#### UI Testing
- Use Compose UI testing framework
- Implement Robot Pattern for UI test organization
- Use behavior-driven testing style
- Include screenshot testing with Roborazzi
- Test UI interactions and state assertions

### Documentation
- Keep documentation up-to-date
- Document significant changes
- Include examples in documentation
- Use clear and concise language

## Getting Started
1. Clone the repository
2. Set up Android development environment
3. Build the project using Gradle
4. Run tests to verify setup

## Contributing
- Review existing issues
- Follow coding guidelines
- Submit detailed pull requests
- Participate in code reviews

## Support
For questions and support:
- Create GitHub issues
- Review documentation
- Contact project maintainers
