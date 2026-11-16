package com.leyon.lumin;

import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableScheduling
@SpringBootApplication
public class LuminBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(LuminBackendApplication.class, args);
	}

}
