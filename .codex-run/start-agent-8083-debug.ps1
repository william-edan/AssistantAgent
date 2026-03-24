$ErrorActionPreference = 'Stop'

$env:MAVEN_OPTS = '-Xms128m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC'

mvn -pl assistant-agent-start -am "-Dspring-boot.run.arguments=--server.port=8083 --spring.flyway.enabled=false --logging.level.com.alibaba.assistant.agent.runtime.intent=DEBUG --logging.level.com.alibaba.assistant.agent.runtime.interceptor=DEBUG" spring-boot:run
