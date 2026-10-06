package com.digihealth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DigihealthBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(DigihealthBackendApplication.class, args);
	}

}
