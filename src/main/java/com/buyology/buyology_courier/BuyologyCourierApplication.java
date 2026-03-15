package com.buyology.buyology_courier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class BuyologyCourierApplication {

	public static void main(String[] args) {
		SpringApplication.run(BuyologyCourierApplication.class, args);
	}

}
