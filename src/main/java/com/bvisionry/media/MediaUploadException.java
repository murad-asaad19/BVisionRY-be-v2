package com.bvisionry.media;

/**
 * Thrown when a file cannot be uploaded to MinIO or when the bucket is unavailable.
 */
public class MediaUploadException extends RuntimeException {

    public MediaUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
