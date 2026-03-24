$ErrorActionPreference = 'Stop'

$env:MAVEN_OPTS = '-Xms256m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC'

mvn -pl assistant-agent-start -am "-Dspring-boot.run.arguments=--server.port=8082 --spring.flyway.enabled=false" spring-boot:run
