package com.buyology.buyology_courier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "contabo.s3.endpoint=http://localhost:9000",
        "contabo.s3.access-key=test",
        "contabo.s3.secret-key=test",
        "contabo.s3.bucket-name=test-bucket",
        "contabo.s3.public-url=http://localhost:9000/test-bucket"
})
class BuyologyCourierApplicationTests {

	@Test
	void contextLoads() {
	}

}
