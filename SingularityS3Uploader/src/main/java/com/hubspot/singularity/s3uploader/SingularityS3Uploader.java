package com.hubspot.singularity.s3uploader;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.SSEAwsKeyManagementParams;
import com.amazonaws.services.s3.model.StorageClass;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.github.rholder.retry.Retryer;
import com.github.rholder.retry.RetryerBuilder;
import com.github.rholder.retry.StopStrategies;
import com.github.rholder.retry.WaitStrategies;
import com.hubspot.mesos.JavaUtils;
import com.hubspot.singularity.SingularityS3FormatHelper;
import com.hubspot.singularity.SingularityS3Log;
import com.hubspot.singularity.runner.base.sentry.SingularityRunnerExceptionNotifier;
import com.hubspot.singularity.runner.base.shared.S3UploadMetadata;
import com.hubspot.singularity.s3uploader.config.SingularityS3UploaderConfiguration;
import com.hubspot.singularity.s3uploader.config.SingularityS3UploaderContentHeaders;
import java.io.File;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

public class SingularityS3Uploader extends SingularityUploader {
  private final BasicAWSCredentials credentials;
  private final S3ClientProvider s3ClientProvider;

  SingularityS3Uploader(
    BasicAWSCredentials defaultCredentials,
    S3ClientProvider s3ClientProvider,
    S3UploadMetadata uploadMetadata,
    FileSystem fileSystem,
    SingularityS3UploaderMetrics metrics,
    Path metadataPath,
    SingularityS3UploaderConfiguration configuration,
    String hostname,
    SingularityRunnerExceptionNotifier exceptionNotifier,
    Lock checkFileOpenLock
  ) {
    super(
      uploadMetadata,
      fileSystem,
      metrics,
      metadataPath,
      configuration,
      hostname,
      exceptionNotifier,
      checkFileOpenLock
    );
    this.s3ClientProvider = s3ClientProvider;
    if (
      uploadMetadata.getS3SecretKey().isPresent() &&
      uploadMetadata.getS3AccessKey().isPresent()
    ) {
      this.credentials =
        new BasicAWSCredentials(
          uploadMetadata.getS3AccessKey().get(),
          uploadMetadata.getS3SecretKey().get()
        );
    } else {
      this.credentials = defaultCredentials;
    }
  }

  @Override
  public String toString() {
    return (
      "SingularityS3Uploader [uploadMetadata=" +
      uploadMetadata +
      ", metadataPath=" +
      metadataPath +
      "]"
    );
  }

  protected void uploadSingle(int sequence, Path file) throws Exception {
    AmazonS3 s3Client = s3ClientProvider.getClient(credentials);
    try {
      Retryer<Boolean> retryer = RetryerBuilder
        .<Boolean>newBuilder()
        .retryIfExceptionOfType(AmazonS3Exception.class)
        .retryIfRuntimeException()
        .withWaitStrategy(
          WaitStrategies.fixedWait(configuration.getRetryWaitMs(), TimeUnit.MILLISECONDS)
        )
        .withStopStrategy(StopStrategies.stopAfterAttempt(configuration.getRetryCount()))
        .build();

      retryer.call(
        () -> {
          final long start = System.currentTimeMillis();

          try {
            final String key = SingularityS3FormatHelper.getKey(
              uploadMetadata.getS3KeyFormat(),
              sequence,
              Files.getLastModifiedTime(file).toMillis(),
              Objects.toString(file.getFileName()),
              hostname
            );

            long fileSizeBytes = Files.size(file);
            LOG.info(
              "{} Uploading {} to {}/{} (size {})",
              logIdentifier,
              file,
              bucketName,
              key,
              fileSizeBytes
            );

            ObjectMetadata objectMetadata = new ObjectMetadata();

            UploaderFileAttributes fileAttributes = getFileAttributes(file);

            if (fileAttributes.getStartTime().isPresent()) {
              objectMetadata.addUserMetadata(
                SingularityS3Log.LOG_START_S3_ATTR,
                fileAttributes.getStartTime().get().toString()
              );
              LOG.debug(
                "Added extra metadata for object ({}:{})",
                SingularityS3Log.LOG_START_S3_ATTR,
                fileAttributes.getStartTime().get()
              );
            }
            if (fileAttributes.getEndTime().isPresent()) {
              objectMetadata.addUserMetadata(
                SingularityS3Log.LOG_END_S3_ATTR,
                fileAttributes.getEndTime().get().toString()
              );
              LOG.debug(
                "Added extra metadata for object ({}:{})",
                SingularityS3Log.LOG_END_S3_ATTR,
                fileAttributes.getEndTime().get()
              );
            }

            for (SingularityS3UploaderContentHeaders contentHeaders : configuration.getS3ContentHeaders()) {
              if (file.toString().endsWith(contentHeaders.getFilenameEndsWith())) {
                LOG.debug(
                  "{} Using content headers {} for file {}",
                  logIdentifier,
                  contentHeaders,
                  file
                );
                if (contentHeaders.getContentType().isPresent()) {
                  objectMetadata.setContentType(contentHeaders.getContentType().get());
                }
                if (contentHeaders.getContentEncoding().isPresent()) {
                  objectMetadata.setContentEncoding(
                    contentHeaders.getContentEncoding().get()
                  );
                }
                break;
              }
            }

            Optional<StorageClass> maybeStorageClass = Optional.empty();

            if (
              shouldApplyStorageClass(fileSizeBytes, uploadMetadata.getS3StorageClass())
            ) {
              LOG.debug(
                "{} adding storage class {} to {}",
                logIdentifier,
                uploadMetadata.getS3StorageClass().get(),
                file
              );
              maybeStorageClass =
                Optional.of(
                  StorageClass.fromValue(uploadMetadata.getS3StorageClass().get())
                );
            }

            LOG.debug("Uploading object with metadata {}", objectMetadata);

            if (fileSizeBytes > configuration.getMaxSingleUploadSizeBytes()) {
              multipartUpload(
                s3Client,
                key,
                file.toFile(),
                objectMetadata,
                maybeStorageClass
              );
            } else {
              if (uploadMetadata.isUseS3ServerSideEncryption()) {
                objectMetadata.setSSEAlgorithm(
                  ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION
                );
              }
              PutObjectRequest putObjectRequest = new PutObjectRequest(
                bucketName,
                key,
                file.toFile()
              )
              .withMetadata(objectMetadata);
              maybeStorageClass.ifPresent(putObjectRequest::setStorageClass);
              if (uploadMetadata.getEncryptionKey().isPresent()) {
                putObjectRequest.withSSEAwsKeyManagementParams(
                  new SSEAwsKeyManagementParams(uploadMetadata.getEncryptionKey().get())
                );
              }
              s3Client.putObject(putObjectRequest);
            }

            LOG.info(
              "{} Uploaded {} in {}",
              logIdentifier,
              key,
              JavaUtils.duration(start)
            );
          } catch (AmazonS3Exception se) {
            if (se.getMessage().contains("does not exist in our records")) {
              LOG.warn(
                "Upload cannot succeed with S3 key that does not exit in AWS, marking {} as complete",
                uploadMetadata
              );
              return true;
            }

            LOG.warn(
              "{} Couldn't upload {} due to {} - {}",
              logIdentifier,
              file,
              se.getErrorCode(),
              se.getErrorMessage(),
              se
            );
            throw se;
          } catch (NoSuchFileException nsfe) {
            LOG.warn(
              "File {} no longer exists, marking upload as complete",
              nsfe.getFile()
            );
            return true;
          } catch (Exception e) {
            LOG.warn("Exception uploading {}", file, e);
            throw e;
          }

          return true;
        }
      );
    } finally {
      s3ClientProvider.returnClient(credentials);
    }
  }

  private void multipartUpload(
    AmazonS3 s3Client,
    String key,
    File file,
    ObjectMetadata objectMetadata,
    Optional<StorageClass> maybeStorageClass
  )
    throws Exception {
    List<PartETag> partETags = new ArrayList<>();
    InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(
      bucketName,
      key,
      objectMetadata
    );
    maybeStorageClass.ifPresent(initRequest::setStorageClass);
    InitiateMultipartUploadResult initResponse = s3Client.initiateMultipartUpload(
      initRequest
    );

    long contentLength = file.length();
    long partSize = configuration.getUploadPartSize();

    try {
      long filePosition = 0;
      for (int i = 1; filePosition < contentLength; i++) {
        partSize = Math.min(partSize, (contentLength - filePosition));
        UploadPartRequest uploadRequest = new UploadPartRequest()
          .withBucketName(bucketName)
          .withKey(key)
          .withUploadId(initResponse.getUploadId())
          .withPartNumber(i)
          .withFileOffset(filePosition)
          .withFile(file)
          .withPartSize(partSize);
        partETags.add(s3Client.uploadPart(uploadRequest).getPartETag());
        filePosition += partSize;
      }

      CompleteMultipartUploadRequest completeRequest = new CompleteMultipartUploadRequest(
        bucketName,
        key,
        initResponse.getUploadId(),
        partETags
      );
      s3Client.completeMultipartUpload(completeRequest);
    } catch (Exception e) {
      s3Client.abortMultipartUpload(
        new AbortMultipartUploadRequest(bucketName, key, initResponse.getUploadId())
      );
      throw new RuntimeException(e);
    }
  }
}
