package com.couchbase.common.logger;

import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@Service
public class LogUploadService {
    private static final String DEFAULT_CONFIG_FILE = "../config.yaml";
    private static final String DEFAULT_BUCKET = "";
    private static final String DEFAULT_PREFIX = "logs/";
    private static final long DEFAULT_INTERVAL_MS = 3600000L;
    private static final String DEFAULT_REGION = "us-east-1";

    private String bucket = DEFAULT_BUCKET;
    private String prefix = DEFAULT_PREFIX;
    private long intervalMs = DEFAULT_INTERVAL_MS;
    private String awsAccessKey = "";
    private String awsAccessSecret = "";
    private String awsRegion = DEFAULT_REGION;

    private S3Client s3Client;

    public LogUploadService() {
        loadLogConfigFromYaml();
        buildS3Client();
    }

    private void loadLogConfigFromYaml() {
        try {
            Path configFile = Paths.get(DEFAULT_CONFIG_FILE);
            if (!configFile.isAbsolute()) {
                configFile = Paths.get(System.getProperty("user.dir")).resolve(DEFAULT_CONFIG_FILE);
            }
            if (!Files.exists(configFile)) return;
            Yaml yaml = new Yaml();
            Map<String, Object> yamlData;
            try (InputStream inputStream = new FileInputStream(configFile.toFile())) {
                yamlData = yaml.load(inputStream);
            }
            if (yamlData == null) return;

            @SuppressWarnings("unchecked")
            Map<String, Object> loggerConfig = (Map<String, Object>) yamlData.get("logger");
            if (loggerConfig != null) {
                if (loggerConfig.get("log-s3-bucket") != null) {
                    bucket = loggerConfig.get("log-s3-bucket").toString();
                }
                if (loggerConfig.get("log-s3-prefix") != null) {
                    prefix = loggerConfig.get("log-s3-prefix").toString();
                }
                if (loggerConfig.get("log-upload-interval-ms") != null) {
                    try {
                        intervalMs = Long.parseLong(loggerConfig.get("log-upload-interval-ms").toString());
                    } catch (NumberFormatException ignored) {}
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> awsConfig = (Map<String, Object>) yamlData.get("aws");
            if (awsConfig != null) {
                if (awsConfig.get("access-key-id") != null) {
                    awsAccessKey = awsConfig.get("access-key-id").toString();
                }
                if (awsConfig.get("secret-access-key") != null) {
                    awsAccessSecret = awsConfig.get("secret-access-key").toString();
                }
                if (awsConfig.get("region") != null) {
                    awsRegion = awsConfig.get("region").toString();
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load log upload config from config.yaml: " + e.getMessage());
        }
    }

    private void buildS3Client() {
        if (!awsAccessKey.isEmpty() && !awsAccessSecret.isEmpty()) {
            s3Client = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(awsAccessKey, awsAccessSecret)))
                    .build();
        } else {
            s3Client = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .build();
        }
    }

    public void uploadLogs() {
        File logDir = new File("logs");
        if (!logDir.exists() || !logDir.isDirectory()) return;
        File[] files = logDir.listFiles((dir, name) -> name.matches("app-\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.log"));
        if (files == null) return;

        for (File file : files) {
            // Skip app.log since it may still be in use
            if (file.getName().equals("app.log")) continue;
            try {
                String key = prefix + file.getName();
                s3Client.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build(), file.toPath());
                Files.delete(file.toPath());
            } catch (Exception e) {
                System.err.println("Failed to upload log file to S3: " + file.getName() +
                        ", error: " + e.getMessage());
            }
        }
    }

    public long getIntervalMs() {
        return intervalMs;
    }
}
