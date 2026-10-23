package io.livecut.video;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class VideoService {
    private static final Logger LOG = LoggerFactory.getLogger(VideoService.class);
    private final VideoStorage storage;
    private final VideoRepository repository;
    private final TransactionTemplate transaction;

    public VideoService(VideoStorage storage, VideoRepository repository, PlatformTransactionManager manager) {
        this.storage = storage;
        this.repository = repository;
        this.transaction = new TransactionTemplate(manager);
    }

    public VideoAsset upload(MultipartFile file) {
        VideoStorage.StoredVideo stored = storage.store(file);
        AtomicBoolean cleanupOwnedByTransaction = new AtomicBoolean();
        AtomicBoolean commitPhase = new AtomicBoolean();
        AtomicReference<RuntimeException> cleanupFailure = new AtomicReference<>();
        try {
            return transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

                    @Override
                    public void beforeCommit(boolean readOnly) { commitPhase.set(true); }

                    @Override
                    public void afterCompletion(int completionStatus) {
                        // JdbcTransactionManager can translate a lost commit
                        // acknowledgement into DataAccessException. Spring may
                        // then attempt rollback and report ROLLED_BACK although
                        // the original commit actually succeeded. Once commit
                        // preparation starts, retain the source regardless.
                        if (completionStatus == STATUS_ROLLED_BACK && !commitPhase.get()) {
                            cleanupSource(stored, cleanupFailure);
                        }
                    }
                });
                cleanupOwnedByTransaction.set(true);
                repository.insert(stored);
                // Read server-created metadata back so POST/GET/restart agree.
                return repository.find(stored.id()).orElseThrow(() -> new IllegalStateException("Inserted video is unavailable"));
            });
        } catch (RuntimeException | Error failure) {
            // A failure before callback registration cannot have inserted a row.
            if (!cleanupOwnedByTransaction.get()) {
                cleanupSource(stored, cleanupFailure);
            }
            RuntimeException cleanup = cleanupFailure.get();
            if (cleanup != null && cleanup != failure) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void cleanupSource(VideoStorage.StoredVideo stored, AtomicReference<RuntimeException> cleanupFailure) {
        try {
            storage.delete(stored);
        } catch (RuntimeException cleanup) {
            cleanupFailure.compareAndSet(null, cleanup);
            // Completion callbacks must not replace the original database error.
            LOG.warn("Source cleanup failed for video {} ({}); retained for reconciliation", stored.id(), cleanup.getClass().getSimpleName());
        }
    }

    public VideoAsset get(String id) {
        String canonical;
        try {
            canonical = UUID.fromString(id).toString();
            if (!canonical.equalsIgnoreCase(id)) { throw new IllegalArgumentException(); }
        } catch (IllegalArgumentException exception) {
            throw new VideoApiException(HttpStatus.BAD_REQUEST, "INVALID_VIDEO_ID", "Video ID must be a UUID");
        }
        return repository.find(canonical).orElseThrow(() ->
                new VideoApiException(HttpStatus.NOT_FOUND, "VIDEO_NOT_FOUND", "Video was not found"));
    }

    public List<VideoAsset> list(int limit, int offset) { return repository.list(limit, offset); }
}
