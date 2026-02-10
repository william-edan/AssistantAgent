# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Assistant Agent is an enterprise-grade intelligent assistant framework built on Spring AI Alibaba using the **Code-as-Action paradigm**. Instead of calling predefined tools, the agent generates and executes Python code in a GraalVM sandbox to complete tasks.

**Tech Stack:** Java 17+, Spring Boot 3.4.8, Spring AI 1.1.0, Spring AI Alibaba 1.1.0.0, GraalVM Polyglot 24.2.1

## Build & Run Commands

```bash
# Build (skip tests for faster builds)
mvn clean install -DskipTests

# Build with tests
mvn clean install

# Run tests
mvn test

# Run specific test
mvn test -Dtest=ClassName

# Run application
cd assistant-agent-start
mvn spring-boot:run

# Generate test coverage report
mvn test jacoco:report
```

**Environment:** Set `DASHSCOPE_API_KEY` environment variable before running.

**Access Points:**
- Application: http://localhost:8080
- Chat UI: http://localhost:8080/chatui/index.html

## Architecture

### Module Structure

```
AssistantAgent/
├── assistant-agent-common/           # Shared interfaces, enums, constants
├── assistant-agent-core/             # GraalVM executor, tool registry, Python-Java bridges
├── assistant-agent-evaluation/       # Multi-layer intent recognition via Evaluation Graph
├── assistant-agent-prompt-builder/   # Dynamic prompt assembly based on evaluation results
├── assistant-agent-extensions/       # Plugin modules:
│   ├── dynamic/                      # MCP & HTTP API tools
│   ├── experience/                   # Experience management & FastIntent
│   ├── learning/                     # Learning extraction & storage
│   ├── search/                       # Unified search (knowledge, project, web)
│   ├── reply/                        # Multi-channel reply
│   ├── trigger/                      # Scheduled/delayed/callback triggers
│   └── evaluation/                   # Evaluation integration
├── assistant-agent-autoconfigure/    # Spring Boot auto-configuration, CodeactAgent
└── assistant-agent-start/            # Application entry point
```

### Key Components

- **CodeactAgent** (`autoconfigure`): Main agent orchestration class
- **GraalCodeExecutor** (`core`): Executes AI-generated Python code in GraalVM sandbox
- **CodeactToolRegistry** (`core`): Registers MCP, HTTP API, and custom tools
- **EvaluationService** (`evaluation`): Multi-dimensional intent recognition with dependency graph
- **PromptManager** (`prompt-builder`): Conditionally injects context based on evaluation results

### Tool Bridges (Python-Java Interop)

Python code executed by GraalVM can call Java tools through bridges:
- `AgentToolBridge` - Call registered tools from Python
- `StateBridge` - Access shared state
- `LoggerBridge` - Logging from Python code

## Code Style

- **Java Style:** Google Java Style Guide, 4-space indentation, 120 char line limit
- **Logging Format:** `ClassName#methodName - reason=description`
- **Test Naming:** `shouldExpectedBehaviorWhenCondition()`
- **License:** Apache 2.0 header required on all source files

## SPI Extension Points

Implement these interfaces to extend functionality:
- `SearchProvider` - Connect knowledge sources
- `ReplyChannelDefinition` - Add reply channels
- `CodeactTool` - Create custom tools
- `PromptBuilder` - Customize prompt generation
- `EvaluationCriterion` - Add evaluation logic
- `LearningExtractor` - Custom learning strategies

## Configuration

Main config: `assistant-agent-start/src/main/resources/application.yml`
Full reference: `assistant-agent-start/src/main/resources/application-reference.yml`

Key config namespace: `spring.ai.alibaba.codeact.extension.*`
