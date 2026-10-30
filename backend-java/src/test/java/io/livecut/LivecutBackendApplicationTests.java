package io.livecut;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

// The cached smoke-test context must never maintain another suite's rows:
// integration suites share livecut_test but own separate temporary volumes.
@SpringBootTest(properties = {
        "livecut.jobs.scan-enabled=false", "livecut.workers.require-registration=false",
        "livecut.uploads.scan-enabled=false",
        "livecut.events.scan-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "LIVECUT_DB_URL", matches = "jdbc:mysql:.*")
class LivecutBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
