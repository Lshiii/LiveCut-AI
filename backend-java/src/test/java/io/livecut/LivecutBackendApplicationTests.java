package io.livecut;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "livecut.jobs.scan-enabled=false")
@EnabledIfEnvironmentVariable(named = "LIVECUT_DB_URL", matches = "jdbc:mysql:.*")
class LivecutBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
