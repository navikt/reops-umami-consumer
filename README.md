# Getting Started

### Prerequisites
- Java version 25

### Build the project
```
./gradlew build
./gradlew test --rerun-tasks
```

### Run locally
1. Start docker-compose in project reops-event-proxy under .compose `docker-compose up`, has a readme in that folder describing the services started.
2. Start application with profile `local`:  
   `./gradlew bootRun -Dspring-boot.run.profiles=local`
 - local profile does not use ssl for kafka

### Privacy filter
See the [privacy filter design and rules](src/main/kotlin/no/nav/reops/filter/Filter.md) for details about traversal, structural rules, and redaction behavior.
