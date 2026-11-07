package fft_battleground.config;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import lombok.extern.slf4j.Slf4j;

/**
 * Starts a managed PostgreSQL instance for local development, so no system-wide
 * install is needed. Only activates when explicitly requested at launch:
 *
 *   java -Dfft_battleground.embeddedPostgres=true -jar target/Viewer.war
 *
 * or with the environment variable FFTB_EMBEDDED_POSTGRES=true. It is
 * deliberately NOT readable from application.properties so a production
 * deployment can never turn it on by accident.
 *
 * Data lives in ./pgdata and survives restarts; delete that folder to reset.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EmbeddedPostgresInitializer implements EnvironmentPostProcessor {

	private static final String ENABLE_FLAG = "fft_battleground.embeddedPostgres";
	private static final String DATABASE_NAME = "FFT_BattleGround";
	private static final int PORT = 5432;
	//overridable so TESTS can boot an isolated instance (separate port/database/dataDir) —
	//the defaults share ./pgdata with local dev runs, and a test context's create-drop
	//would otherwise drop the dev database's schema on shutdown
	private static final String PORT_OVERRIDE = "fft_battleground.embeddedPostgres.port";
	private static final String DATABASE_OVERRIDE = "fft_battleground.embeddedPostgres.database";
	private static final String DATA_DIR_OVERRIDE = "fft_battleground.embeddedPostgres.dataDir";

	private EmbeddedPostgres postgres;

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		String flag = environment.getProperty(ENABLE_FLAG);
		if (flag == null) {
			flag = System.getenv("FFTB_EMBEDDED_POSTGRES");
		}
		if (!"true".equalsIgnoreCase(flag)) {
			return;
		}

		try {
			int port = this.overrideOrDefault(environment, PORT_OVERRIDE, PORT);
			String databaseName = this.overrideOrDefault(environment, DATABASE_OVERRIDE, DATABASE_NAME);
			String dataDir = this.overrideOrDefault(environment, DATA_DIR_OVERRIDE, "pgdata");
			log.info("Starting embedded PostgreSQL on port {}...", port);
			this.postgres = EmbeddedPostgres.builder()
					.setPort(port)
					.setDataDirectory(Paths.get(dataDir))
					.setCleanDataDirectory(false)
					.start();
			this.createDatabaseIfMissing(databaseName);
			log.info("Embedded PostgreSQL ready on port {}", port);

			Map<String, Object> props = new HashMap<>();
			props.put("spring.datasource.url", "jdbc:postgresql://localhost:" + port + "/" + databaseName);
			props.put("spring.datasource.username", "postgres");
			props.put("spring.datasource.password", "postgres");
			environment.getPropertySources().addFirst(new MapPropertySource("embeddedPostgres", props));

			Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "embedded-postgres-shutdown"));
		} catch (Exception e) {
			throw new IllegalStateException("Failed to start embedded PostgreSQL", e);
		}
	}

	private String overrideOrDefault(ConfigurableEnvironment environment, String key, String defaultValue) {
		String value = environment.getProperty(key);
		return value != null ? value : defaultValue;
	}

	private int overrideOrDefault(ConfigurableEnvironment environment, String key, int defaultValue) {
		String value = environment.getProperty(key);
		return value != null ? Integer.parseInt(value) : defaultValue;
	}

	private void createDatabaseIfMissing(String databaseName) throws Exception {
		try (Connection conn = this.postgres.getPostgresDatabase().getConnection();
				Statement stmt = conn.createStatement()) {
			conn.setAutoCommit(true);
			try (ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = 'postgres'")) {
				if (!rs.next()) {
					stmt.execute("CREATE ROLE postgres LOGIN SUPERUSER");
				}
			}
			try (ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + databaseName + "'")) {
				if (!rs.next()) {
					log.info("Creating database {}", databaseName);
					stmt.execute("CREATE DATABASE \"" + databaseName + "\"");
				}
			}
		}
	}

	private void stop() {
		try {
			if (this.postgres != null) {
				this.postgres.close();
			}
		} catch (Exception e) {
			log.warn("Error stopping embedded PostgreSQL", e);
		}
	}
}
