package com.shackspacehosting.engineering.pvmanager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

@SpringBootApplication
@ComponentScan({"com.shackspacehosting.engineering.security", "com.shackspacehosting.engineering.pvmanager"})
@Configuration
public class OpenshiftPVManagerApplication {
	private static final Logger LOG = LoggerFactory.getLogger(OpenshiftPVManagerApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(OpenshiftPVManagerApplication.class, args);
	}

	@Bean
	public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
		return new PropertySourcesPlaceholderConfigurer();
	}
}
