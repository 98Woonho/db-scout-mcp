package io.github._98woonho.dbscout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * db-scout MCP 서버 진입점.
 *
 * <p>MCP 클라이언트(Claude Code 등)가 이 프로그램을 실행하고 표준입출력(STDIO)으로 대화한다.
 * 표준출력은 MCP 메시지 전용이므로 로그는 표준에러로만 보낸다(logback-spring.xml).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DbScoutApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbScoutApplication.class, args);
    }
}
