package com.hubspot.singularity.data.history;

import com.hubspot.mesos.JavaUtils;
import com.hubspot.singularity.SingularityDeleteResult;
import com.hubspot.singularity.SingularityHistoryItem;
import com.hubspot.singularity.SingularityManagedThreadPoolFactory;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.scheduler.SingularityLeaderOnlyPoller;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class SingularityHistoryPersister<T extends SingularityHistoryItem>
  extends SingularityLeaderOnlyPoller {
  private static final Logger LOG = LoggerFactory.getLogger(
    SingularityHistoryPersister.class
  );

  protected final SingularityConfiguration configuration;
  protected final ReentrantLock persisterLock;
  protected final ExecutorService persisterExecutor;
  protected final AtomicLong lastPersisterSuccess;

  public SingularityHistoryPersister(
    SingularityConfiguration configuration,
    ReentrantLock persisterLock,
    AtomicLong lastPersisterSuccess,
    SingularityManagedThreadPoolFactory managedThreadPoolFactory
  ) {
    super(configuration.getPersistHistoryEverySeconds(), TimeUnit.SECONDS);
    this.configuration = configuration;
    this.persisterLock = persisterLock;
    this.lastPersisterSuccess = lastPersisterSuccess;
    this.persisterExecutor =
      managedThreadPoolFactory.get(
        "persister",
        configuration.getHistoryPollerConcurrency()
      );
  }

  @Override
  protected boolean abortsOnError() {
    return false;
  }

  protected boolean persistsHistoryInsteadOfPurging() {
    return configuration.getDatabaseConfiguration().isPresent();
  }

  @Override
  protected boolean isEnabled() {
    if (configuration.isSqlReadOnlyMode()) {
      return false;
    }
    return (
      persistsHistoryInsteadOfPurging() ||
      getMaxAgeInMillisOfItem() > 0 ||
      getMaxNumberOfItems().isPresent()
    );
  }

  protected abstract long getMaxAgeInMillisOfItem();

  protected abstract Optional<Integer> getMaxNumberOfItems();

  protected abstract boolean moveToHistory(T object);

  protected abstract SingularityDeleteResult purgeFromZk(T object);

  public boolean moveToHistoryOrCheckForPurge(T object, int index) {
    final long start = System.currentTimeMillis();

    if (moveToHistoryOrCheckForPurgeAndShouldDelete(object, index)) {
      SingularityDeleteResult deleteResult = purgeFromZk(object);
      LOG.debug(
        "{} {} (deleted: {}) in {}",
        persistsHistoryInsteadOfPurging() ? "Persisted" : "Purged",
        object,
        deleteResult,
        JavaUtils.duration(start)
      );
      return true;
    }

    return false;
  }

  private boolean moveToHistoryOrCheckForPurgeAndShouldDelete(T object, int index) {
    if (persistsHistoryInsteadOfPurging()) {
      return moveToHistory(object);
    }

    final long age =
      System.currentTimeMillis() - object.getCreateTimestampForCalculatingHistoryAge();

    if (age > getMaxAgeInMillisOfItem()) {
      LOG.trace(
        "Deleting {} because it is {} old (max : {})",
        object,
        JavaUtils.durationFromMillis(age),
        JavaUtils.durationFromMillis(getMaxAgeInMillisOfItem())
      );
      return true;
    }

    if (getMaxNumberOfItems().isPresent() && index >= getMaxNumberOfItems().get()) {
      LOG.trace(
        "Deleting {} because it is item number {} (max: {})",
        object,
        index,
        getMaxNumberOfItems().get()
      );
      return true;
    }

    return false;
  }
}
