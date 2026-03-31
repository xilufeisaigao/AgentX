package com.agentx.platform.support.eval;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

public final class RealWorkflowEvalProfileSeeds {

    private static final Path PROFILE_SEED_ROOT = Path.of("db", "seeds", "profiles");

    private RealWorkflowEvalProfileSeeds() {
    }

    public static void apply(DataSource dataSource, String profileId) {
        Path seedPath = PROFILE_SEED_ROOT.resolve(profileId + ".sql").toAbsolutePath().normalize();
        if (Files.notExists(seedPath)) {
            throw new IllegalArgumentException("profile SQL companion not found: " + seedPath);
        }
        try {
            String sql = Files.readString(seedPath, StandardCharsets.UTF_8);
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (IOException | java.sql.SQLException exception) {
            throw new IllegalStateException("failed to apply profile SQL companion for " + profileId, exception);
        }
    }
}
