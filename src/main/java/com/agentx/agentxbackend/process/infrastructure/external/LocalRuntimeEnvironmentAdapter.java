package com.agentx.agentxbackend.process.infrastructure.external;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.agentx.agentxbackend.process.application.port.out.RuntimeEnvironmentMaintenancePort;
import com.agentx.agentxbackend.process.application.port.out.RuntimeEnvironmentPort;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.bson.Document;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class LocalRuntimeEnvironmentAdapter implements RuntimeEnvironmentPort, RuntimeEnvironmentMaintenancePort {

    private static final Logger log = LoggerFactory.getLogger(LocalRuntimeEnvironmentAdapter.class);
    private static final Duration DEFAULT_ENV_TTL = Duration.ofDays(7);
    private static final String MODE_LOCAL = "local";
    private static final String MODE_DOCKER = "docker";
    private static final String TP_JAVA_17 = "TP-JAVA-17";
    private static final String TP_JAVA_21 = "TP-JAVA-21";
    private static final String PULL_POLICY_ALWAYS = "always";
    private static final String PULL_POLICY_IF_NOT_PRESENT = "if-not-present";
    private static final String PULL_POLICY_NEVER = "never";
    private static final String DATABASE_ACCOUNT_READY = "READY";
    private static final String DATABASE_ACCOUNT_SKIPPED = "SKIPPED";
    private static final String DATABASE_ACCOUNT_FAILED = "FAILED";
    private static final String MYSQL_PROVIDER = "mysql";
    private static final String POSTGRESQL_PROVIDER = "postgresql";
    private static final String REDIS_PROVIDER = "redis";
    private static final String MONGODB_PROVIDER = "mongodb";
    private static final Set<String> SUPPORTED_DATABASE_PROVIDERS = Set.of(
        MYSQL_PROVIDER,
        POSTGRESQL_PROVIDER,
        REDIS_PROVIDER,
        MONGODB_PROVIDER,
        "sqlserver",
        "oracle"
    );
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String mode;
    private final Path rootPath;
    private final String pythonExecutable;
    private final Duration commandTimeout;
    private final String dockerExecutable;
    private final String dockerPullPolicy;
    private final String dockerDefaultImage;
    private final String dockerJava21Image;
    private final String dockerMaven3Image;
    private final String dockerGit2Image;
    private final String dockerPython311Image;
    private final boolean databaseAccountEnabled;
    private final String databaseAccountUsernamePrefix;
    private final int databaseAccountPasswordLength;
    private final Set<String> databaseAccountProviders;
    private final Map<String, String> databaseAccountCommandTemplates;
    private final String mysqlAccountHost;
    private final int mysqlAccountPort;
    private final String mysqlAccountDatabase;
    private final String mysqlAccountAdminUsername;
    private final String mysqlAccountAdminPassword;
    private final String postgresqlAccountHost;
    private final int postgresqlAccountPort;
    private final String postgresqlAccountDatabase;
    private final String postgresqlAccountAdminDatabase;
    private final String postgresqlAccountAdminUsername;
    private final String postgresqlAccountAdminPassword;
    private final String redisAccountHost;
    private final int redisAccountPort;
    private final int redisAccountDatabase;
    private final String redisAccountAdminUsername;
    private final String redisAccountAdminPassword;
    private final String mongodbAccountHost;
    private final int mongodbAccountPort;
    private final String mongodbAccountDatabase;
    private final String mongodbAccountAdminDatabase;
    private final String mongodbAccountAdminUsername;
    private final String mongodbAccountAdminPassword;

    public LocalRuntimeEnvironmentAdapter(
        ObjectMapper objectMapper,
        @Value("${agentx.workforce.runtime-environment.enabled:true}") boolean enabled,
        @Value("${agentx.workforce.runtime-environment.mode:docker}") String mode,
        @Value("${agentx.workforce.runtime-environment.root:.agentx/runtime-env}") String rootPath,
        @Value("${agentx.workforce.runtime-environment.python-executable:python}") String pythonExecutable,
        @Value("${agentx.workforce.runtime-environment.command-timeout-ms:120000}") long timeoutMs,
        @Value("${agentx.workforce.runtime-environment.docker.executable:docker}") String dockerExecutable,
        @Value("${agentx.workforce.runtime-environment.docker.pull-policy:if-not-present}") String dockerPullPolicy,
        @Value("${agentx.workforce.runtime-environment.docker.default-image:alpine:3.20}") String dockerDefaultImage,
        @Value("${agentx.workforce.runtime-environment.docker.image.java21:eclipse-temurin:21-jdk}") String dockerJava21Image,
        @Value("${agentx.workforce.runtime-environment.docker.image.maven3:maven:3.9.11-eclipse-temurin-21}") String dockerMaven3Image,
        @Value("${agentx.workforce.runtime-environment.docker.image.git2:alpine/git:2.47.2}") String dockerGit2Image,
        @Value("${agentx.workforce.runtime-environment.docker.image.python311:python:3.11-slim}") String dockerPython311Image,
        @Value("${agentx.workforce.runtime-environment.db-account.enabled:true}") boolean databaseAccountEnabled,
        @Value("${agentx.workforce.runtime-environment.db-account.username-prefix:ax}") String databaseAccountUsernamePrefix,
        @Value("${agentx.workforce.runtime-environment.db-account.password-length:20}") int databaseAccountPasswordLength,
        @Value("${agentx.workforce.runtime-environment.db-account.providers:mysql,postgresql,redis,mongodb,sqlserver,oracle}") String databaseAccountProviders,
        @Value("${agentx.workforce.runtime-environment.db-account.command-templates-json:{}}") String databaseAccountCommandTemplatesJson,
        @Value("${agentx.workforce.runtime-environment.db-account.mysql.host:127.0.0.1}") String mysqlAccountHost,
        @Value("${agentx.workforce.runtime-environment.db-account.mysql.port:3306}") int mysqlAccountPort,
        @Value("${agentx.workforce.runtime-environment.db-account.mysql.database:agentx_backend}") String mysqlAccountDatabase,
        @Value("${agentx.workforce.runtime-environment.db-account.mysql.admin-username:root}") String mysqlAccountAdminUsername,
        @Value("${agentx.workforce.runtime-environment.db-account.mysql.admin-password:}") String mysqlAccountAdminPassword,
        @Value("${agentx.workforce.runtime-environment.db-account.postgresql.host:127.0.0.1}") String postgresqlAccountHost,
        @Value("${agentx.workforce.runtime-environment.db-account.postgresql.port:5432}") int postgresqlAccountPort,
        @Value("${agentx.workforce.runtime-environment.db-account.postgresql.database:agentx_backend}") String postgresqlAccountDatabase,
        @Value("${agentx.workforce.runtime-environment.db-account.postgresql.admin-database:postgres}") String postgresqlAccountAdminDatabase,
        @Value("${agentx.workforce.runtime-environment.db-account.postgresql.admin-username:postgres}") String postgresqlAccountAdminUsername,
        @Value("${agentx.workforce.runtime-environment.db-account.postgresql.admin-password:}") String postgresqlAccountAdminPassword,
        @Value("${agentx.workforce.runtime-environment.db-account.redis.host:127.0.0.1}") String redisAccountHost,
        @Value("${agentx.workforce.runtime-environment.db-account.redis.port:6379}") int redisAccountPort,
        @Value("${agentx.workforce.runtime-environment.db-account.redis.database:0}") int redisAccountDatabase,
        @Value("${agentx.workforce.runtime-environment.db-account.redis.admin-username:}") String redisAccountAdminUsername,
        @Value("${agentx.workforce.runtime-environment.db-account.redis.admin-password:}") String redisAccountAdminPassword,
        @Value("${agentx.workforce.runtime-environment.db-account.mongodb.host:127.0.0.1}") String mongodbAccountHost,
        @Value("${agentx.workforce.runtime-environment.db-account.mongodb.port:27017}") int mongodbAccountPort,
        @Value("${agentx.workforce.runtime-environment.db-account.mongodb.database:agentx_backend}") String mongodbAccountDatabase,
        @Value("${agentx.workforce.runtime-environment.db-account.mongodb.admin-database:admin}") String mongodbAccountAdminDatabase,
        @Value("${agentx.workforce.runtime-environment.db-account.mongodb.admin-username:root}") String mongodbAccountAdminUsername,
        @Value("${agentx.workforce.runtime-environment.db-account.mongodb.admin-password:}") String mongodbAccountAdminPassword
    ) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.mode = normalizeMode(mode);
        this.rootPath = Path.of(rootPath == null || rootPath.isBlank() ? ".agentx/runtime-env" : rootPath.trim()).toAbsolutePath();
        this.pythonExecutable = (pythonExecutable == null || pythonExecutable.isBlank()) ? "python" : pythonExecutable.trim();
        this.commandTimeout = Duration.ofMillis(Math.max(1000L, timeoutMs));
        this.dockerExecutable = (dockerExecutable == null || dockerExecutable.isBlank()) ? "docker" : dockerExecutable.trim();
        this.dockerPullPolicy = normalizePullPolicy(dockerPullPolicy);
        this.dockerDefaultImage = requireImageOrDefault(dockerDefaultImage, "alpine:3.20");
        this.dockerJava21Image = requireImageOrDefault(dockerJava21Image, "eclipse-temurin:21-jdk");
        this.dockerMaven3Image = requireImageOrDefault(dockerMaven3Image, "maven:3.9.11-eclipse-temurin-21");
        this.dockerGit2Image = requireImageOrDefault(dockerGit2Image, "alpine/git:2.47.2");
        this.dockerPython311Image = requireImageOrDefault(dockerPython311Image, "python:3.11-slim");
        this.databaseAccountEnabled = databaseAccountEnabled;
        this.databaseAccountUsernamePrefix = (databaseAccountUsernamePrefix == null || databaseAccountUsernamePrefix.isBlank())
            ? "ax"
            : sanitizePathSegment(databaseAccountUsernamePrefix.trim().toLowerCase(Locale.ROOT));
        this.databaseAccountPasswordLength = Math.max(12, Math.min(databaseAccountPasswordLength, 64));
        this.databaseAccountProviders = parseDatabaseAccountProviders(databaseAccountProviders);
        this.databaseAccountCommandTemplates = parseCommandTemplates(databaseAccountCommandTemplatesJson);
        this.mysqlAccountHost = (mysqlAccountHost == null || mysqlAccountHost.isBlank())
            ? "127.0.0.1"
            : mysqlAccountHost.trim();
        this.mysqlAccountPort = mysqlAccountPort <= 0 ? 3306 : mysqlAccountPort;
        this.mysqlAccountDatabase = (mysqlAccountDatabase == null || mysqlAccountDatabase.isBlank())
            ? "agentx_backend"
            : mysqlAccountDatabase.trim();
        this.mysqlAccountAdminUsername = (mysqlAccountAdminUsername == null || mysqlAccountAdminUsername.isBlank())
            ? "root"
            : mysqlAccountAdminUsername.trim();
        this.mysqlAccountAdminPassword = mysqlAccountAdminPassword == null ? "" : mysqlAccountAdminPassword.trim();
        this.postgresqlAccountHost = (postgresqlAccountHost == null || postgresqlAccountHost.isBlank())
            ? "127.0.0.1"
            : postgresqlAccountHost.trim();
        this.postgresqlAccountPort = postgresqlAccountPort <= 0 ? 5432 : postgresqlAccountPort;
        this.postgresqlAccountDatabase = (postgresqlAccountDatabase == null || postgresqlAccountDatabase.isBlank())
            ? "agentx_backend"
            : postgresqlAccountDatabase.trim();
        this.postgresqlAccountAdminDatabase = (postgresqlAccountAdminDatabase == null || postgresqlAccountAdminDatabase.isBlank())
            ? "postgres"
            : postgresqlAccountAdminDatabase.trim();
        this.postgresqlAccountAdminUsername = (postgresqlAccountAdminUsername == null || postgresqlAccountAdminUsername.isBlank())
            ? "postgres"
            : postgresqlAccountAdminUsername.trim();
        this.postgresqlAccountAdminPassword = postgresqlAccountAdminPassword == null ? "" : postgresqlAccountAdminPassword.trim();
        this.redisAccountHost = (redisAccountHost == null || redisAccountHost.isBlank())
            ? "127.0.0.1"
            : redisAccountHost.trim();
        this.redisAccountPort = redisAccountPort <= 0 ? 6379 : redisAccountPort;
        this.redisAccountDatabase = Math.max(0, redisAccountDatabase);
        this.redisAccountAdminUsername = redisAccountAdminUsername == null ? "" : redisAccountAdminUsername.trim();
        this.redisAccountAdminPassword = redisAccountAdminPassword == null ? "" : redisAccountAdminPassword.trim();
        this.mongodbAccountHost = (mongodbAccountHost == null || mongodbAccountHost.isBlank())
            ? "127.0.0.1"
            : mongodbAccountHost.trim();
        this.mongodbAccountPort = mongodbAccountPort <= 0 ? 27017 : mongodbAccountPort;
        this.mongodbAccountDatabase = (mongodbAccountDatabase == null || mongodbAccountDatabase.isBlank())
            ? "agentx_backend"
            : mongodbAccountDatabase.trim();
        this.mongodbAccountAdminDatabase = (mongodbAccountAdminDatabase == null || mongodbAccountAdminDatabase.isBlank())
            ? "admin"
            : mongodbAccountAdminDatabase.trim();
        this.mongodbAccountAdminUsername = mongodbAccountAdminUsername == null ? "" : mongodbAccountAdminUsername.trim();
        this.mongodbAccountAdminPassword = mongodbAccountAdminPassword == null ? "" : mongodbAccountAdminPassword.trim();
    }

    @Override
    public PreparedEnvironment ensureReady(String sessionId, String workerId, List<String> requiredToolpackIds) {
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        String normalizedWorkerId = requireNotBlank(workerId, "workerId");
        List<String> normalizedToolpacks = normalizeToolpackIds(requiredToolpackIds);
        if (!enabled || normalizedToolpacks.isEmpty()) {
            return new PreparedEnvironment(null, null, normalizedToolpacks);
        }

        Path globalToolpackRoot = rootPath.resolve("global-toolpacks");
        Path projectRoot = rootPath
            .resolve("projects")
            .resolve(sanitizePathSegment(normalizedSessionId))
            .resolve(computeFingerprint(normalizedToolpacks));
        Path pythonVenvPath = null;
        Map<String, String> dockerImagesByToolpack = Map.of();
        Map<String, DatabaseVirtualAccount> databaseAccounts = Map.of();

        try {
            Files.createDirectories(globalToolpackRoot);
            Files.createDirectories(projectRoot);
            if (MODE_DOCKER.equals(mode)) {
                dockerImagesByToolpack = prepareDockerRuntime(globalToolpackRoot, projectRoot, normalizedToolpacks);
            } else {
                pythonVenvPath = prepareLocalRuntime(globalToolpackRoot, projectRoot, normalizedToolpacks);
            }
            databaseAccounts = ensureDatabaseVirtualAccounts(
                projectRoot,
                normalizedSessionId,
                normalizedWorkerId,
                normalizedToolpacks
            );
            writeEnvironmentManifest(
                projectRoot,
                normalizedSessionId,
                normalizedWorkerId,
                normalizedToolpacks,
                pythonVenvPath,
                dockerImagesByToolpack,
                databaseAccounts
            );
            return new PreparedEnvironment(
                projectRoot.toString(),
                pythonVenvPath == null ? null : pythonVenvPath.toString(),
                normalizedToolpacks
            );
        } catch (Exception ex) {
            throw new IllegalStateException(
                "Failed to prepare runtime environment, sessionId=" + normalizedSessionId + ", workerId=" + normalizedWorkerId,
                ex
            );
        }
    }

    private Path prepareLocalRuntime(Path globalToolpackRoot, Path projectRoot, List<String> normalizedToolpacks)
        throws IOException, InterruptedException {
        Path pythonVenvPath = null;
        for (String toolpackId : normalizedToolpacks) {
            ensureGlobalToolpackMarker(globalToolpackRoot, toolpackId);
            String normalizedToolpackId = toolpackId.toUpperCase(Locale.ROOT);
            if (normalizedToolpackId.startsWith("TP-PYTHON")) {
                pythonVenvPath = ensurePythonVenv(projectRoot);
                continue;
            }
            if (isJavaRuntimeToolpack(normalizedToolpackId)) {
                ensureCommandAvailable(List.of("java", "-version"), projectRoot, toolpackId);
                continue;
            }
            if ("TP-MAVEN-3".equals(normalizedToolpackId)) {
                ensureCommandAvailable(List.of("mvn", "-v"), projectRoot, toolpackId);
                continue;
            }
            if ("TP-GIT-2".equals(normalizedToolpackId)) {
                ensureCommandAvailable(List.of("git", "--version"), projectRoot, toolpackId);
            }
        }
        return pythonVenvPath;
    }

    private Map<String, String> prepareDockerRuntime(Path globalToolpackRoot, Path projectRoot, List<String> normalizedToolpacks)
        throws IOException, InterruptedException {
        ensureDockerAvailable(projectRoot);
        Map<String, String> toolpackImages = new LinkedHashMap<>();
        for (String toolpackId : normalizedToolpacks) {
            ensureGlobalToolpackMarker(globalToolpackRoot, toolpackId);
            String image = resolveDockerImageForToolpack(toolpackId);
            ensureDockerImagePrepared(projectRoot, image, toolpackId);
            toolpackImages.put(toolpackId, image);
        }
        return Map.copyOf(toolpackImages);
    }

    private Map<String, DatabaseVirtualAccount> ensureDatabaseVirtualAccounts(
        Path projectRoot,
        String sessionId,
        String workerId,
        List<String> normalizedToolpacks
    ) throws Exception {
        if (!databaseAccountEnabled || normalizedToolpacks == null || normalizedToolpacks.isEmpty()) {
            return Map.of();
        }
        Set<String> providers = resolveDatabaseProviders(normalizedToolpacks);
        if (providers.isEmpty()) {
            return Map.of();
        }
        Map<String, DatabaseVirtualAccount> accounts = new LinkedHashMap<>(loadDatabaseAccounts(projectRoot));
        boolean changed = false;
        for (String provider : providers) {
            DatabaseVirtualAccount existing = accounts.get(provider);
            if (existing != null && DATABASE_ACCOUNT_READY.equalsIgnoreCase(existing.status())) {
                continue;
            }
            DatabaseVirtualAccount created = provisionDatabaseAccount(projectRoot, sessionId, workerId, provider);
            accounts.put(provider, created);
            changed = true;
        }
        if (changed) {
            saveDatabaseAccounts(projectRoot, accounts);
        }
        return Map.copyOf(accounts);
    }

    private DatabaseVirtualAccount provisionDatabaseAccount(
        Path projectRoot,
        String sessionId,
        String workerId,
        String provider
    ) {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        String fingerprint = computeFingerprint(List.of(sessionId, workerId, normalizedProvider, projectRoot.toString()));
        String username = buildDatabaseUsername(normalizedProvider, fingerprint);
        String password = randomPassword(databaseAccountPasswordLength);
        String createdAt = Instant.now().toString();
        ProviderRuntimeConfig runtimeConfig = resolveProviderRuntimeConfig(normalizedProvider);
        String commandTemplate = databaseAccountCommandTemplates.get(normalizedProvider);
        String provisionedBy = (commandTemplate == null || commandTemplate.isBlank())
            ? runtimeConfig.provisionedBy()
            : "template";
        try {
            if (commandTemplate != null && !commandTemplate.isBlank()) {
                String command = applyCommandTemplate(commandTemplate, Map.of(
                    "provider", normalizedProvider,
                    "username", username,
                    "password", password,
                    "database", runtimeConfig.database(),
                    "host", runtimeConfig.host(),
                    "port", String.valueOf(runtimeConfig.port()),
                    "admin_username", runtimeConfig.adminUsername(),
                    "admin_password", runtimeConfig.adminPassword(),
                    "session_id", sessionId,
                    "worker_id", workerId
                ));
                runShellCommand(command, projectRoot, "provision database account for " + normalizedProvider);
                return new DatabaseVirtualAccount(
                    normalizedProvider,
                    DATABASE_ACCOUNT_READY,
                    username,
                    password,
                    runtimeConfig.database(),
                    runtimeConfig.host(),
                    runtimeConfig.port(),
                    "template",
                    createdAt,
                    null
                );
            }

            if (MYSQL_PROVIDER.equals(normalizedProvider)) {
                provisionMysqlUser(username, password);
                return new DatabaseVirtualAccount(
                    normalizedProvider,
                    DATABASE_ACCOUNT_READY,
                    username,
                    password,
                    runtimeConfig.database(),
                    runtimeConfig.host(),
                    runtimeConfig.port(),
                    "builtin-mysql",
                    createdAt,
                    null
                );
            }
            if (POSTGRESQL_PROVIDER.equals(normalizedProvider)) {
                provisionPostgresqlUser(username, password);
                return new DatabaseVirtualAccount(
                    normalizedProvider,
                    DATABASE_ACCOUNT_READY,
                    username,
                    password,
                    runtimeConfig.database(),
                    runtimeConfig.host(),
                    runtimeConfig.port(),
                    "builtin-postgresql",
                    createdAt,
                    null
                );
            }
            if (REDIS_PROVIDER.equals(normalizedProvider)) {
                provisionRedisUser(username, password);
                return new DatabaseVirtualAccount(
                    normalizedProvider,
                    DATABASE_ACCOUNT_READY,
                    username,
                    password,
                    runtimeConfig.database(),
                    runtimeConfig.host(),
                    runtimeConfig.port(),
                    "builtin-redis",
                    createdAt,
                    null
                );
            }
            if (MONGODB_PROVIDER.equals(normalizedProvider)) {
                provisionMongodbUser(username, password);
                return new DatabaseVirtualAccount(
                    normalizedProvider,
                    DATABASE_ACCOUNT_READY,
                    username,
                    password,
                    runtimeConfig.database(),
                    runtimeConfig.host(),
                    runtimeConfig.port(),
                    "builtin-mongodb",
                    createdAt,
                    null
                );
            }

            String note = "No built-in adapter or command template configured for provider: " + normalizedProvider;
            log.warn("Skip database account provisioning, {}", note);
            return new DatabaseVirtualAccount(
                normalizedProvider,
                DATABASE_ACCOUNT_SKIPPED,
                username,
                password,
                runtimeConfig.database(),
                runtimeConfig.host(),
                runtimeConfig.port(),
                "skipped",
                createdAt,
                note
            );
        } catch (Exception ex) {
            return new DatabaseVirtualAccount(
                normalizedProvider,
                DATABASE_ACCOUNT_FAILED,
                username,
                password,
                runtimeConfig.database(),
                runtimeConfig.host(),
                runtimeConfig.port(),
                provisionedBy,
                createdAt,
                abbreviate(ex.getMessage(), 512)
            );
        }
    }

    private void provisionMysqlUser(String username, String password) throws Exception {
        if (mysqlAccountAdminUsername == null || mysqlAccountAdminUsername.isBlank()) {
            throw new IllegalStateException("MySQL admin username is blank");
        }
        if (mysqlAccountAdminPassword == null || mysqlAccountAdminPassword.isBlank()) {
            throw new IllegalStateException("MySQL admin password is blank");
        }
        String jdbcUrl = "jdbc:mysql://" + mysqlAccountHost + ":" + mysqlAccountPort
            + "/mysql?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        String escapedUser = escapeSqlString(username);
        String escapedPassword = escapeSqlString(password);
        String escapedDatabase = escapeSqlIdentifier(mysqlAccountDatabase);
        try (Connection connection = DriverManager.getConnection(
            jdbcUrl,
            mysqlAccountAdminUsername,
            mysqlAccountAdminPassword
        ); Statement statement = connection.createStatement()) {
            statement.execute("CREATE USER IF NOT EXISTS '" + escapedUser + "'@'%' IDENTIFIED BY '" + escapedPassword + "'");
            statement.execute("GRANT ALL PRIVILEGES ON `" + escapedDatabase + "`.* TO '" + escapedUser + "'@'%'");
            statement.execute("FLUSH PRIVILEGES");
        }
    }

    private void provisionPostgresqlUser(String username, String password) throws Exception {
        if (postgresqlAccountAdminUsername == null || postgresqlAccountAdminUsername.isBlank()) {
            throw new IllegalStateException("PostgreSQL admin username is blank");
        }
        String adminJdbcUrl = "jdbc:postgresql://" + postgresqlAccountHost + ":" + postgresqlAccountPort
            + "/" + postgresqlAccountAdminDatabase;
        String targetJdbcUrl = "jdbc:postgresql://" + postgresqlAccountHost + ":" + postgresqlAccountPort
            + "/" + postgresqlAccountDatabase;
        String quotedUser = quoteSqlIdentifier(username);
        String quotedDatabase = quoteSqlIdentifier(postgresqlAccountDatabase);
        String escapedPassword = escapeSqlString(password);
        try (Connection adminConnection = DriverManager.getConnection(
            adminJdbcUrl,
            postgresqlAccountAdminUsername,
            postgresqlAccountAdminPassword
        ); Statement statement = adminConnection.createStatement()) {
            statement.execute(
                "DO $$ BEGIN "
                    + "CREATE ROLE " + quotedUser + " LOGIN PASSWORD '" + escapedPassword + "'; "
                    + "EXCEPTION WHEN duplicate_object THEN "
                    + "ALTER ROLE " + quotedUser + " LOGIN PASSWORD '" + escapedPassword + "'; "
                    + "END $$;"
            );
            statement.execute("GRANT CONNECT ON DATABASE " + quotedDatabase + " TO " + quotedUser);
        }
        try (Connection targetConnection = DriverManager.getConnection(
            targetJdbcUrl,
            postgresqlAccountAdminUsername,
            postgresqlAccountAdminPassword
        ); Statement statement = targetConnection.createStatement()) {
            statement.execute("GRANT USAGE ON SCHEMA public TO " + quotedUser);
            statement.execute("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO " + quotedUser);
            statement.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO " + quotedUser);
        }
    }

    private void provisionRedisUser(String username, String password) {
        if (redisAccountAdminPassword == null || redisAccountAdminPassword.isBlank()) {
            throw new IllegalStateException("Redis admin password is blank");
        }
        RedisURI.Builder uriBuilder = RedisURI.builder()
            .withHost(redisAccountHost)
            .withPort(redisAccountPort)
            .withDatabase(redisAccountDatabase);
        if (redisAccountAdminUsername != null && !redisAccountAdminUsername.isBlank()) {
            uriBuilder.withAuthentication(redisAccountAdminUsername, redisAccountAdminPassword.toCharArray());
        } else {
            uriBuilder.withPassword(redisAccountAdminPassword.toCharArray());
        }
        RedisURI redisUri = uriBuilder.build();
        RedisClient redisClient = RedisClient.create(redisUri);
        try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            RedisCommands<String, String> commands = connection.sync();
            CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                .add("SETUSER")
                .add(username)
                .add("on")
                .add("resetpass")
                .add(">" + password)
                .add("~*")
                .add("+@all");
            String reply = commands.dispatch(
                CommandType.ACL,
                new StatusOutput<>(StringCodec.UTF8),
                args
            );
            if (reply == null || !reply.trim().equalsIgnoreCase("OK")) {
                throw new IllegalStateException("Redis ACL SETUSER failed, reply=" + reply);
            }
        } finally {
            redisClient.shutdown();
        }
    }

    private void provisionMongodbUser(String username, String password) {
        int timeoutMs = (int) Math.min(Math.max(commandTimeout.toMillis(), 2000L), 5000L);
        MongoClientSettings.Builder settingsBuilder = MongoClientSettings.builder()
            .applyToClusterSettings(cluster -> {
                cluster.hosts(List.of(new ServerAddress(mongodbAccountHost, mongodbAccountPort)));
                cluster.serverSelectionTimeout(timeoutMs, TimeUnit.MILLISECONDS);
            })
            .applyToSocketSettings(socket -> {
                socket.connectTimeout(timeoutMs, TimeUnit.MILLISECONDS);
                socket.readTimeout(timeoutMs, TimeUnit.MILLISECONDS);
            });
        if (mongodbAccountAdminUsername != null && !mongodbAccountAdminUsername.isBlank()) {
            settingsBuilder.credential(
                MongoCredential.createCredential(
                    mongodbAccountAdminUsername,
                    mongodbAccountAdminDatabase,
                    mongodbAccountAdminPassword == null ? new char[0] : mongodbAccountAdminPassword.toCharArray()
                )
            );
        }
        try (MongoClient client = MongoClients.create(settingsBuilder.build())) {
            MongoDatabase database = client.getDatabase(mongodbAccountDatabase);
            List<Document> roles = List.of(new Document("role", "readWrite").append("db", mongodbAccountDatabase));
            try {
                database.runCommand(
                    new Document("createUser", username)
                        .append("pwd", password)
                        .append("roles", roles)
                );
            } catch (MongoCommandException ex) {
                if (!isMongoUserAlreadyExists(ex)) {
                    throw ex;
                }
                database.runCommand(
                    new Document("updateUser", username)
                        .append("pwd", password)
                        .append("roles", roles)
                );
            }
        }
    }

    private ProviderRuntimeConfig resolveProviderRuntimeConfig(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (MYSQL_PROVIDER.equals(normalized)) {
            return new ProviderRuntimeConfig(
                mysqlAccountHost,
                mysqlAccountPort,
                mysqlAccountDatabase,
                mysqlAccountAdminUsername,
                mysqlAccountAdminPassword,
                "builtin-mysql"
            );
        }
        if (POSTGRESQL_PROVIDER.equals(normalized)) {
            return new ProviderRuntimeConfig(
                postgresqlAccountHost,
                postgresqlAccountPort,
                postgresqlAccountDatabase,
                postgresqlAccountAdminUsername,
                postgresqlAccountAdminPassword,
                "builtin-postgresql"
            );
        }
        if (REDIS_PROVIDER.equals(normalized)) {
            return new ProviderRuntimeConfig(
                redisAccountHost,
                redisAccountPort,
                String.valueOf(redisAccountDatabase),
                redisAccountAdminUsername,
                redisAccountAdminPassword,
                "builtin-redis"
            );
        }
        if (MONGODB_PROVIDER.equals(normalized)) {
            return new ProviderRuntimeConfig(
                mongodbAccountHost,
                mongodbAccountPort,
                mongodbAccountDatabase,
                mongodbAccountAdminUsername,
                mongodbAccountAdminPassword,
                "builtin-mongodb"
            );
        }
        return new ProviderRuntimeConfig(
            mysqlAccountHost,
            mysqlAccountPort,
            mysqlAccountDatabase,
            mysqlAccountAdminUsername,
            mysqlAccountAdminPassword,
            "template"
        );
    }

    private static boolean isMongoUserAlreadyExists(MongoCommandException ex) {
        if (ex == null) {
            return false;
        }
        int code = ex.getErrorCode();
        if (code == 51003 || code == 11000) {
            return true;
        }
        String codeName = ex.getErrorCodeName();
        if (codeName != null && (codeName.equalsIgnoreCase("Location51003") || codeName.equalsIgnoreCase("DuplicateKey"))) {
            return true;
        }
        String message = ex.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("already exists");
    }

    private Map<String, DatabaseVirtualAccount> loadDatabaseAccounts(Path projectRoot) {
        Path manifest = projectRoot.resolve("database-accounts.json");
        if (!Files.exists(manifest) || !Files.isRegularFile(manifest)) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                return Map.of();
            }
            Map<String, DatabaseVirtualAccount> accounts = new LinkedHashMap<>();
            Map<String, Object> payload = objectMapper.convertValue(root, Map.class);
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                String provider = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
                if (provider.isBlank() || !(entry.getValue() instanceof Map<?, ?> valueMap)) {
                    continue;
                }
                DatabaseVirtualAccount account = new DatabaseVirtualAccount(
                    provider,
                    normalizeMapString(valueMap.get("status"), DATABASE_ACCOUNT_SKIPPED),
                    normalizeMapString(valueMap.get("username"), ""),
                    normalizeMapString(valueMap.get("password"), ""),
                    textOrNull(normalizeMapString(valueMap.get("database"), "")),
                    textOrNull(normalizeMapString(valueMap.get("host"), "")),
                    normalizeMapInt(valueMap.get("port"), 0),
                    normalizeMapString(valueMap.get("provisioned_by"), "unknown"),
                    normalizeMapString(valueMap.get("created_at"), Instant.now().toString()),
                    textOrNull(normalizeMapString(valueMap.get("note"), ""))
                );
                accounts.put(provider, account);
            }
            return Map.copyOf(accounts);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private void saveDatabaseAccounts(Path projectRoot, Map<String, DatabaseVirtualAccount> accounts) throws Exception {
        if (accounts == null || accounts.isEmpty()) {
            return;
        }
        ObjectNode root = objectMapper.createObjectNode();
        for (Map.Entry<String, DatabaseVirtualAccount> entry : accounts.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            DatabaseVirtualAccount account = entry.getValue();
            ObjectNode node = root.putObject(entry.getKey());
            node.put("provider", account.provider());
            node.put("status", account.status());
            node.put("username", account.username());
            node.put("password", account.password());
            if (account.database() == null) {
                node.putNull("database");
            } else {
                node.put("database", account.database());
            }
            if (account.host() == null) {
                node.putNull("host");
            } else {
                node.put("host", account.host());
            }
            node.put("port", account.port());
            node.put("provisioned_by", account.provisionedBy());
            node.put("created_at", account.createdAt());
            if (account.note() == null) {
                node.putNull("note");
            } else {
                node.put("note", account.note());
            }
        }
        Path manifest = projectRoot.resolve("database-accounts.json");
        Files.writeString(manifest, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
    }

    private Set<String> resolveDatabaseProviders(List<String> toolpackIds) {
        if (toolpackIds == null || toolpackIds.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> providers = new LinkedHashSet<>();
        for (String toolpackId : toolpackIds) {
            String provider = resolveProviderByToolpack(toolpackId);
            if (provider == null || provider.isBlank()) {
                continue;
            }
            if (!databaseAccountProviders.contains(provider)) {
                continue;
            }
            providers.add(provider);
        }
        return Set.copyOf(providers);
    }

    private static String resolveProviderByToolpack(String toolpackId) {
        if (toolpackId == null || toolpackId.isBlank()) {
            return null;
        }
        String normalized = toolpackId.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("MYSQL")) {
            return "mysql";
        }
        if (normalized.contains("POSTGRES") || normalized.contains("POSTGRESQL")) {
            return "postgresql";
        }
        if (normalized.contains("REDIS")) {
            return "redis";
        }
        if (normalized.contains("MONGO")) {
            return "mongodb";
        }
        if (normalized.contains("MSSQL") || normalized.contains("SQLSERVER")) {
            return "sqlserver";
        }
        if (normalized.contains("ORACLE")) {
            return "oracle";
        }
        if (normalized.startsWith("TP-DB-")) {
            String[] parts = normalized.split("-");
            if (parts.length >= 3) {
                return parts[2].toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private String buildDatabaseUsername(String provider, String fingerprint) {
        String suffix = (fingerprint == null || fingerprint.isBlank()) ? "000000" : fingerprint.substring(0, Math.min(6, fingerprint.length()));
        String base = databaseAccountUsernamePrefix + "_" + sanitizePathSegment(provider) + "_" + suffix;
        String normalized = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        if (normalized.length() > 30) {
            normalized = normalized.substring(0, 30);
        }
        if (normalized.isBlank()) {
            return "ax_user_" + suffix;
        }
        return normalized;
    }

    private static String randomPassword(int length) {
        final char[] chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-.@".toCharArray();
        StringBuilder sb = new StringBuilder(Math.max(12, length));
        for (int i = 0; i < Math.max(12, length); i++) {
            sb.append(chars[SECURE_RANDOM.nextInt(chars.length)]);
        }
        return sb.toString();
    }

    private void runShellCommand(String command, Path workDir, String operation) throws IOException, InterruptedException {
        List<String> shellCommand = new ArrayList<>();
        if (isWindows()) {
            shellCommand.add("powershell");
            shellCommand.add("-NoProfile");
            shellCommand.add("-Command");
            shellCommand.add(command);
        } else {
            shellCommand.add("bash");
            shellCommand.add("-lc");
            shellCommand.add(command);
        }
        runCommand(shellCommand, workDir, operation);
    }

    private static String applyCommandTemplate(String template, Map<String, String> replacements) {
        String command = template;
        if (replacements != null) {
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                String placeholder = "${" + entry.getKey() + "}";
                command = command.replace(placeholder, entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return command;
    }

    private static Set<String> parseDatabaseAccountProviders(String raw) {
        if (raw == null || raw.isBlank()) {
            return SUPPORTED_DATABASE_PROVIDERS;
        }
        LinkedHashSet<String> providers = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            if ("*".equals(normalized)) {
                return SUPPORTED_DATABASE_PROVIDERS;
            }
            if (SUPPORTED_DATABASE_PROVIDERS.contains(normalized)) {
                providers.add(normalized);
            }
        }
        if (providers.isEmpty()) {
            return SUPPORTED_DATABASE_PROVIDERS;
        }
        return Set.copyOf(providers);
    }

    private Map<String, String> parseCommandTemplates(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            if (root == null || !root.isObject()) {
                return Map.of();
            }
            Map<String, String> templates = new LinkedHashMap<>();
            Map<String, Object> payload = objectMapper.convertValue(root, Map.class);
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                String provider = entry.getKey().trim().toLowerCase(Locale.ROOT);
                String template = normalizeMapString(entry.getValue(), "");
                if (!provider.isBlank() && !template.isBlank()) {
                    templates.put(provider, template);
                }
            }
            return templates.isEmpty() ? Map.of() : Map.copyOf(templates);
        } catch (Exception ex) {
            log.warn("Failed to parse db account command templates JSON, fallback to empty. cause={}", ex.getMessage());
            return Map.of();
        }
    }

    private static String escapeSqlString(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private static String escapeSqlIdentifier(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return "agentx_backend";
        }
        return normalized.replace("`", "``");
    }

    private static String quoteSqlIdentifier(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("SQL identifier must not be blank");
        }
        return "\"" + normalized.replace("\"", "\"\"") + "\"";
    }

    private static String textOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String normalizeMapString(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = String.valueOf(value).trim();
        if (normalized.isEmpty() || "null".equalsIgnoreCase(normalized)) {
            return fallback;
        }
        return normalized;
    }

    private static int normalizeMapInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    @Override
    public CleanupResult cleanupExpiredProjectEnvironments(Duration ttl, int maxDeletePerCycle) {
        if (!enabled) {
            return new CleanupResult(0, 0, 0);
        }

        Duration effectiveTtl = (ttl == null || ttl.isZero() || ttl.isNegative()) ? DEFAULT_ENV_TTL : ttl;
        int deleteBudget = Math.max(1, maxDeletePerCycle);
        Path projectsRoot = rootPath.resolve("projects");
        if (!Files.exists(projectsRoot) || !Files.isDirectory(projectsRoot)) {
            return new CleanupResult(0, 0, 0);
        }

        int scanned = 0;
        int deleted = 0;
        int failed = 0;
        Instant now = Instant.now();
        try (var sessionDirs = Files.list(projectsRoot)) {
            for (Path sessionDir : sessionDirs.filter(Files::isDirectory).toList()) {
                if (deleted >= deleteBudget) {
                    break;
                }
                try (var envDirs = Files.list(sessionDir)) {
                    for (Path envDir : envDirs.filter(Files::isDirectory).toList()) {
                        scanned++;
                        if (deleted >= deleteBudget) {
                            continue;
                        }
                        if (!isExpiredEnvironment(envDir, now, effectiveTtl)) {
                            continue;
                        }
                        try {
                            deleteRecursively(envDir, projectsRoot);
                            deleted++;
                        } catch (Exception ex) {
                            failed++;
                            log.warn(
                                "Failed to cleanup runtime environment directory, path={}, cause={}",
                                envDir,
                                ex.getMessage()
                            );
                        }
                    }
                } catch (Exception ex) {
                    failed++;
                    log.warn("Failed to scan runtime environment session directory, path={}", sessionDir, ex);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to scan runtime environment projects root: " + projectsRoot, ex);
        }
        return new CleanupResult(scanned, deleted, failed);
    }

    private void ensureGlobalToolpackMarker(Path globalToolpackRoot, String toolpackId) throws IOException {
        Path toolpackRoot = globalToolpackRoot.resolve(sanitizePathSegment(toolpackId));
        Files.createDirectories(toolpackRoot);
        Path marker = toolpackRoot.resolve(".ready");
        if (!Files.exists(marker)) {
            Files.writeString(marker, Instant.now().toString(), StandardCharsets.UTF_8);
        }
    }

    private Path ensurePythonVenv(Path projectRoot) throws IOException, InterruptedException {
        Path venvPath = projectRoot.resolve("python-venv");
        Path pythonBinary = resolvePythonBinary(venvPath);
        if (Files.exists(pythonBinary)) {
            return venvPath;
        }
        ensureCommandAvailable(List.of(pythonExecutable, "--version"), projectRoot, "python");
        runCommand(
            List.of(pythonExecutable, "-m", "venv", venvPath.toString()),
            projectRoot,
            "create python virtual environment"
        );
        if (!Files.exists(pythonBinary)) {
            throw new IllegalStateException("python venv created but interpreter missing at " + pythonBinary);
        }
        return venvPath;
    }

    private void ensureCommandAvailable(List<String> command, Path workDir, String commandLabel) throws IOException, InterruptedException {
        runCommand(command, workDir, "check command " + commandLabel);
    }

    private void runCommand(List<String> command, Path workDir, String operation) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workDir.toFile());
        processBuilder.redirectErrorStream(true);
        Path outputFile = Files.createTempFile(workDir, "runtime-cmd-", ".log");
        processBuilder.redirectOutput(outputFile.toFile());
        try {
            Process process = processBuilder.start();
            boolean finished = process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException(
                    "Command timed out while " + operation + ": " + String.join(" ", command)
                        + ", output=" + readLogSnippet(outputFile, 512)
                );
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String output = readLogSnippet(outputFile, 512);
                throw new IllegalStateException(
                    "Command failed while " + operation + ", exitCode=" + exitCode + ", command=" + String.join(" ", command)
                        + ", output=" + output
                );
            }
        } finally {
            try {
                Files.deleteIfExists(outputFile);
            } catch (Exception ignored) {
                // best effort cleanup for temporary command output file.
            }
        }
    }

    private void ensureDockerAvailable(Path workDir) throws IOException, InterruptedException {
        ensureCommandAvailable(
            List.of(dockerExecutable, "version", "--format", "{{.Server.Version}}"),
            workDir,
            "docker daemon"
        );
    }

    private void ensureDockerImagePrepared(Path workDir, String image, String toolpackId) throws IOException, InterruptedException {
        if (PULL_POLICY_ALWAYS.equals(dockerPullPolicy)) {
            runCommand(List.of(dockerExecutable, "pull", image), workDir, "pull docker image for " + toolpackId);
            return;
        }
        if (PULL_POLICY_NEVER.equals(dockerPullPolicy)) {
            ensureCommandAvailable(List.of(dockerExecutable, "image", "inspect", image), workDir, "docker image " + image);
            return;
        }
        if (!canRunCommand(List.of(dockerExecutable, "image", "inspect", image), workDir)) {
            runCommand(List.of(dockerExecutable, "pull", image), workDir, "pull docker image for " + toolpackId);
        }
    }

    private boolean canRunCommand(List<String> command, Path workDir) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workDir.toFile());
        processBuilder.redirectErrorStream(true);
        Path outputFile = Files.createTempFile(workDir, "runtime-cmd-check-", ".log");
        processBuilder.redirectOutput(outputFile.toFile());
        try {
            Process process = processBuilder.start();
            boolean finished = process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } finally {
            try {
                Files.deleteIfExists(outputFile);
            } catch (Exception ignored) {
                // best effort cleanup for temporary command output file.
            }
        }
    }

    private String resolveDockerImageForToolpack(String toolpackId) {
        String normalizedToolpackId = toolpackId == null ? "" : toolpackId.trim().toUpperCase(Locale.ROOT);
        if (isJavaRuntimeToolpack(normalizedToolpackId)) {
            return dockerJava21Image;
        }
        if ("TP-MAVEN-3".equals(normalizedToolpackId)) {
            return dockerMaven3Image;
        }
        if ("TP-GIT-2".equals(normalizedToolpackId)) {
            return dockerGit2Image;
        }
        if (normalizedToolpackId.startsWith("TP-PYTHON")) {
            return dockerPython311Image;
        }
        return dockerDefaultImage;
    }

    private static boolean isJavaRuntimeToolpack(String normalizedToolpackId) {
        return TP_JAVA_17.equals(normalizedToolpackId) || TP_JAVA_21.equals(normalizedToolpackId);
    }

    private void writeEnvironmentManifest(
        Path projectRoot,
        String sessionId,
        String workerId,
        List<String> toolpackIds,
        Path pythonVenvPath,
        Map<String, String> dockerImagesByToolpack,
        Map<String, DatabaseVirtualAccount> databaseAccounts
    ) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("session_id", sessionId);
        root.put("worker_id", workerId);
        root.put("prepared_at", Instant.now().toString());
        root.put("project_environment_path", projectRoot.toString());
        root.put("runtime_mode", mode);
        if (pythonVenvPath == null) {
            root.putNull("python_venv_path");
        } else {
            root.put("python_venv_path", pythonVenvPath.toString());
        }
        ArrayNode toolpacks = root.putArray("toolpacks");
        for (String toolpackId : toolpackIds) {
            toolpacks.add(toolpackId);
        }
        ObjectNode dockerImagesNode = root.putObject("docker_images");
        if (dockerImagesByToolpack != null) {
            for (Map.Entry<String, String> entry : dockerImagesByToolpack.entrySet()) {
                dockerImagesNode.put(entry.getKey(), entry.getValue());
            }
        }
        ObjectNode databaseAccountsNode = root.putObject("database_accounts");
        if (databaseAccounts != null) {
            for (Map.Entry<String, DatabaseVirtualAccount> entry : databaseAccounts.entrySet()) {
                DatabaseVirtualAccount account = entry.getValue();
                if (account == null) {
                    continue;
                }
                ObjectNode accountNode = databaseAccountsNode.putObject(entry.getKey());
                accountNode.put("provider", account.provider());
                accountNode.put("status", account.status());
                accountNode.put("username", account.username());
                accountNode.put("password", account.password());
                accountNode.put("database", account.database());
                accountNode.put("host", account.host());
                accountNode.put("port", account.port());
                accountNode.put("provisioned_by", account.provisionedBy());
                accountNode.put("created_at", account.createdAt());
                if (account.note() == null || account.note().isBlank()) {
                    accountNode.putNull("note");
                } else {
                    accountNode.put("note", account.note());
                }
            }
        }
        Path manifest = projectRoot.resolve("environment.json");
        Files.writeString(manifest, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        log.info("Runtime environment prepared, sessionId={}, workerId={}, path={}", sessionId, workerId, projectRoot);
    }

    private static Path resolvePythonBinary(Path venvPath) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return venvPath.resolve("Scripts").resolve("python.exe");
        }
        return venvPath.resolve("bin").resolve("python");
    }

    private static List<String> normalizeToolpackIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                continue;
            }
            normalized.add(item.trim());
        }
        return new ArrayList<>(normalized);
    }

    private static String sanitizePathSegment(String raw) {
        return raw
            .replaceAll("[^a-zA-Z0-9._-]+", "_")
            .replaceAll("_+", "_");
    }

    private static String computeFingerprint(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = String.join("|", values);
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hash.length; i++) {
                if (i >= 8) {
                    break;
                }
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute runtime environment fingerprint", ex);
        }
    }

    private static String requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeMode(String rawMode) {
        String normalized = rawMode == null ? "" : rawMode.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return MODE_DOCKER;
        }
        if (MODE_LOCAL.equals(normalized) || MODE_DOCKER.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported runtime environment mode: " + rawMode);
    }

    private static String normalizePullPolicy(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return PULL_POLICY_IF_NOT_PRESENT;
        }
        if (PULL_POLICY_ALWAYS.equals(normalized)
            || PULL_POLICY_IF_NOT_PRESENT.equals(normalized)
            || PULL_POLICY_NEVER.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported docker pull policy: " + raw);
    }

    private static String requireImageOrDefault(String rawImage, String fallback) {
        String value = rawImage == null ? "" : rawImage.trim();
        if (!value.isEmpty()) {
            return value;
        }
        return fallback;
    }

    private static String abbreviate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    private boolean isExpiredEnvironment(Path envDir, Instant now, Duration ttl) {
        Instant preparedAt = readPreparedAtFromManifest(envDir).orElseGet(() -> readLastModified(envDir));
        return preparedAt.plus(ttl).isBefore(now);
    }

    private Optional<Instant> readPreparedAtFromManifest(Path envDir) {
        Path manifest = envDir.resolve("environment.json");
        if (!Files.exists(manifest) || !Files.isRegularFile(manifest)) {
            return Optional.empty();
        }
        try {
            String raw = Files.readString(manifest, StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(raw);
            String preparedAt = node.path("prepared_at").asText("").trim();
            if (preparedAt.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(Instant.parse(preparedAt));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static Instant readLastModified(Path path) {
        try {
            FileTime fileTime = Files.getLastModifiedTime(path);
            return fileTime.toInstant();
        } catch (Exception ex) {
            return Instant.now();
        }
    }

    private static void deleteRecursively(Path targetDir, Path projectsRoot) throws IOException {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        Path normalizedRoot = projectsRoot.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot) || normalizedTarget.equals(normalizedRoot)) {
            throw new IllegalStateException("Cleanup target is out of projects root: " + normalizedTarget);
        }
        Files.walkFileTree(normalizedTarget, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String readLogSnippet(Path outputFile, int maxLen) {
        if (outputFile == null || !Files.exists(outputFile)) {
            return "";
        }
        try {
            String raw = Files.readString(outputFile, StandardCharsets.UTF_8);
            String compact = raw.replaceAll("\\s+", " ").trim();
            return abbreviate(compact, maxLen);
        } catch (Exception ex) {
            return "";
        }
    }

    private record ProviderRuntimeConfig(
        String host,
        int port,
        String database,
        String adminUsername,
        String adminPassword,
        String provisionedBy
    ) {
    }

    private record DatabaseVirtualAccount(
        String provider,
        String status,
        String username,
        String password,
        String database,
        String host,
        int port,
        String provisionedBy,
        String createdAt,
        String note
    ) {
    }
}
