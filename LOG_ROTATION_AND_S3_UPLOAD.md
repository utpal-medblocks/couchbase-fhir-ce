# Couchbase-FHIR-CE — Log Rotation & S3 Uploads

> **⚠️ BETA RELEASE NOTICE**: The S3 upload functionality is currently **DISABLED** for the Beta release. Only log rotation is active. The code remains in the codebase but the service is not registered with Spring Boot.

This document describes how to enable and configure **log rotation** and **automatic S3 uploads** in the Couchbase-FHIR-CE project.

---

## 1. Overview

Couchbase-FHIR-CE supports automatic log management through:

- **Log rotation**: Application logs are rotated into timestamped files under the `logs/` directory.
- **S3 upload service**: A background service (`LogUploadService`) periodically uploads rotated log files to an Amazon S3 bucket, then deletes them locally.

This ensures application logs are persisted and managed efficiently without filling up local disk space.

---

## 2. Configuration

Log upload behavior is controlled via a `config.yaml` file.  
By default, the application looks for:

```

config.yaml

```

at the root directory.

### Example `config.yaml`

```yaml
logger:
  log-s3-bucket: my-fhir-logs
  log-s3-prefix: logs/
  log-upload-interval-ms: 3600000 # every 1 hour

aws:
  access-key-id: YOUR_AWS_ACCESS_KEY
  secret-access-key: YOUR_AWS_SECRET_KEY
  region: us-east-1
```

See the [template file](./config.yaml.template) to learn more.

### Configuration Parameters

| Section  | Key                      | Description                                       | Default        |
| -------- | ------------------------ | ------------------------------------------------- | -------------- |
| `logger` | `log-s3-bucket`          | Name of the S3 bucket where logs will be uploaded | (empty string) |
| `logger` | `log-s3-prefix`          | Key prefix in S3 under which log files are stored | `logs/`        |
| `logger` | `log-upload-interval-ms` | Interval (in milliseconds) between upload jobs    | `3600000` (1h) |
| `aws`    | `access-key-id`          | AWS IAM access key ID                             | (none)         |
| `aws`    | `secret-access-key`      | AWS IAM secret access key                         | (none)         |
| `aws`    | `region`                 | AWS region for the S3 bucket                      | `us-east-1`    |

---

## 3. How It Works

1. **Log rotation**

   - Application logs are written under the `logs/` directory.
   - Rotated logs follow the pattern:

     ```
     app-YYYY-MM-DD_HH.log
     ```

   - The active log file (`app.log`) is never uploaded.

2. **S3 uploads**

   - The `LogUploadService` scans the `logs/` directory at the configured interval.
   - Any rotated logs matching the pattern are uploaded to the configured S3 bucket and prefix.
   - Successfully uploaded files are **deleted locally**.

3. **Scheduling**

   - A `SchedulingConfig` class dynamically registers the background upload task using Spring’s scheduling infrastructure.
   - The interval is determined from `config.yaml`.

---

## 4. Flow Diagram

```
                ┌────────────────────────┐
                │   Application Logs     │
                │   (app.log)            │
                └──────────┬─────────────┘
                           │ rotation
                           ▼
                ┌────────────────────────┐
                │  Rotated Log Files     │
                │  logs/app-YYYY-MM-DD   │
                └──────────┬─────────────┘
                           │ upload task
                           ▼
                ┌────────────────────────┐
                │     AWS S3 Bucket      │
                │   s3://bucket/prefix   │
                └──────────┬─────────────┘
                           │ delete after upload
                           ▼
                ┌────────────────────────┐
                │ Local File Removed     │
                └────────────────────────┘
```

---

## 5. Prerequisites

- An existing **S3 bucket** in AWS.
- An IAM user/role with **`s3:PutObject`** and **`s3:DeleteObject`** permissions on the bucket.
- The `config.yaml` file placed at the correct path (`../config.yaml` relative to the application working dir).

---

## 6. Running the Service

When the application is started:

- The `LogUploadService` bean is initialized.
- The scheduler (`SchedulingConfig`) registers a background task with a fixed delay based on `log-upload-interval-ms`.
- The service uploads log files on schedule.

You should see messages in the console if uploads succeed or fail:

```
Failed to upload log file to S3: app-2025-08-28_12.log, error: <reason>
```

---

## 7. Troubleshooting

- **No logs are uploaded**:

  - Ensure log rotation is enabled and rotated files exist in the `logs/` directory.
  - Check that the S3 bucket name and region are correct in `config.yaml`.

- **Authentication issues**:

  - Verify AWS credentials in `config.yaml`.
  - Ensure IAM permissions allow writing and deleting objects in the target bucket.

- **Still seeing circular dependency errors**:

  - Make sure you are using the `SchedulingConfig` class with `PeriodicTrigger(Duration)` instead of `@Scheduled` on `LogUploadService`.

---

## 8. Re-enabling S3 Upload (Post-Beta)

To re-enable the S3 upload functionality after the Beta release:

1. **Restore Service Registration**:

   - In `LogUploadService.java`, uncomment the `@Service` annotation
   - Uncomment the `import org.springframework.stereotype.Service;` import

2. **Restore Scheduling Configuration**:

   - In `SchedulingConfig.java`, uncomment the `@Configuration` and `@EnableScheduling` annotations
   - Uncomment the related import statements

3. **Update Configuration**:

   - In `config.yaml.template`, uncomment the `logger` and `aws` configuration sections
   - Update your actual `config.yaml` file with proper AWS credentials and S3 bucket information

4. **Test the Feature**:
   - Restart the application
   - Verify that logs are being uploaded to S3 as expected

---

## 9. Security Notes

- Avoid committing AWS credentials into version control.
- Prefer using **AWS IAM Roles** or environment variables in production.
- If no credentials are provided in `config.yaml`, the AWS **default provider chain** will be used (e.g., environment, ECS/EC2 instance profiles).
