/*
 * Copyright 2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.photon.controller.housekeeper.dcp;

import com.vmware.dcp.common.Operation;
import com.vmware.dcp.common.ServiceDocument;
import com.vmware.dcp.common.StatefulService;
import com.vmware.dcp.common.UriUtils;
import com.vmware.dcp.common.Utils;
import com.vmware.dcp.services.common.LuceneQueryTaskFactoryService;
import com.vmware.dcp.services.common.QueryTask;
import com.vmware.photon.controller.common.clients.HostClient;
import com.vmware.photon.controller.common.clients.HostClientProvider;
import com.vmware.photon.controller.common.clients.exceptions.RpcException;
import com.vmware.photon.controller.common.dcp.OperationUtils;
import com.vmware.photon.controller.common.dcp.QueryTaskUtils;
import com.vmware.photon.controller.common.dcp.ServiceUtils;
import com.vmware.photon.controller.common.zookeeper.ZookeeperHostMonitor;
import com.vmware.photon.controller.host.gen.HostConfig;
import com.vmware.photon.controller.housekeeper.zookeeper.ZookeeperHostMonitorProvider;
import com.vmware.photon.controller.resource.gen.Datastore;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang3.StringUtils;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Class implementing ImageCleanerService: orchestrate the deletion of all images not present in the shared datastore
 * from all datastores in the system using the ImageDatastoreSweeperService.
 */
public class ImageCleanerService extends StatefulService {
  /**
   * Time to delay query task executions.
   */
  private static final int DEFAULT_QUERY_POLL_DELAY = 10000;

  /**
   * Default constructor.
   */
  public ImageCleanerService() {
    super(State.class);
    super.toggleOption(ServiceOption.PERSISTENCE, true);
    super.toggleOption(ServiceOption.REPLICATION, true);
    super.toggleOption(ServiceOption.OWNER_SELECTION, true);
    super.toggleOption(ServiceOption.INSTRUMENTATION, true);
  }

  @Override
  public void handleStart(Operation start) {
    try {
      ServiceUtils.logInfo(this, "Starting service %s", getSelfLink());

      // Initialize the task stage
      State s = start.getBody(State.class);
      if (s.taskInfo == null || s.taskInfo.stage == TaskState.TaskStage.CREATED) {
        s.taskInfo = new TaskState();
        s.taskInfo.stage = TaskState.TaskStage.STARTED;
        s.taskInfo.subStage = TaskState.SubStage.GET_REFERENCE_DATASTORE_INFO;
      }

      // If the service got restarted in AWAIT_COMPLETION substage, clean finishedCount
      // and failedOrCancelledCount to avoid getting stuck
      if (s.taskInfo.stage == TaskState.TaskStage.STARTED &&
          s.taskInfo.subStage == TaskState.SubStage.AWAIT_COMPLETION) {
        s.failedOrCanceledDeletes = null;
        s.finishedDeletes = null;
      }

      if (s.queryPollDelay == null) {
        s.queryPollDelay = DEFAULT_QUERY_POLL_DELAY;
      }

      if (s.documentExpirationTimeMicros <= 0) {
        s.documentExpirationTimeMicros = ServiceUtils.computeExpirationTime(ServiceUtils.DEFAULT_DOC_EXPIRATION_TIME);
      }

      this.validateState(s);
      start.setBody(s).complete();

      this.sendStageProgressPatch(s, s.taskInfo.stage, s.taskInfo.subStage);
    } catch (Throwable e) {
      ServiceUtils.logSevere(this, e);
      if (!OperationUtils.isCompleted(start)) {
        start.fail(e);
      }
    }
  }

  @Override
  public void handlePatch(Operation patch) {
    try {
      State currentState = getState(patch);
      State patchState = patch.getBody(State.class);

      this.validatePatch(currentState, patchState);
      this.applyPatch(currentState, patchState);

      this.validateState(currentState);
      patch.complete();

      switch (currentState.taskInfo.stage) {
        case STARTED:
          this.processStartedStage(currentState, patchState);
          break;
        case FAILED:
        case FINISHED:
        case CANCELLED:
          break;
        default:
          throw new IllegalStateException(
              String.format("Invalid stage %s", currentState.taskInfo.stage));
      }
    } catch (Throwable e) {
      ServiceUtils.logSevere(this, e);
      if (!OperationUtils.isCompleted(patch)) {
        patch.fail(e);
      }
    }
  }

  /**
   * Validate the service state for coherence.
   *
   * @param current
   */
  protected void validateState(State current) {
    checkNotNull(current.taskInfo, "taskInfo cannot be null");
    checkNotNull(current.taskInfo.stage, "stage cannot be null");

    checkNotNull(current.queryPollDelay, "queryPollDelay cannot be null");
    checkState(current.queryPollDelay > 0, "queryPollDelay must be greater than zero");

    checkNotNull(current.imageWatermarkTime, "imageWatermarkTime cannot be null");
    checkState(current.imageWatermarkTime > 0, "imageWatermarkTime must be greater than zero");

    checkNotNull(current.imageDeleteWatermarkTime, "imageDeleteWatermarkTime cannot be null");
    checkState(current.imageDeleteWatermarkTime > 0, "imageDeleteWatermarkTime must be greater than zero");

    checkState(current.documentExpirationTimeMicros > 0, "documentExpirationTimeMicros must be greater than zero");

    if (current.dataStoreCount != null) {
      checkState(current.dataStoreCount >= 0, "dataStoreCount needs to be >= 0");
    }

    if (current.finishedDeletes != null) {
      checkState(current.finishedDeletes >= 0, "finishedDeletes needs to be >= 0");
    }

    if (current.failedOrCanceledDeletes != null) {
      checkState(current.failedOrCanceledDeletes >= 0, "failedOrCanceledDeletes needs to be >= 0");
    }

    switch (current.taskInfo.stage) {
      case STARTED:
        checkState(current.taskInfo.subStage != null, "Invalid stage update. subStage cannot be null");
        switch (current.taskInfo.subStage) {
          case AWAIT_COMPLETION:
            checkNotNull(current.dataStoreCount, "dataStoreCount cannot be null");
            // fall through
          case TRIGGER_DELETES:
            checkNotNull(current.host, "host cannot be null");
            checkNotNull(current.dataStore, "dataStore cannot be null");
            // fall through
          case GET_REFERENCE_DATASTORE_INFO:
            break;
          default:
            checkState(false, "unsupported sub-state: " + current.taskInfo.subStage.toString());
        }
        break;
      case FAILED:
      case FINISHED:
      case CANCELLED:
        checkState(current.taskInfo.subStage == null, "Invalid stage update. substage must be null");
        break;
      default:
        checkState(false, "cannot process patches in state: " + current.taskInfo.stage.toString());
    }
  }

  /**
   * Validate patch correctness.
   *
   * @param current
   * @param patch
   */
  protected void validatePatch(State current, State patch) {
    checkState(current.taskInfo.stage.ordinal() < TaskState.TaskStage.FINISHED.ordinal(),
        "Invalid stage update. Can not patch anymore when in final stage [%s]", current.taskInfo.stage);
    if (patch.taskInfo != null) {
      checkState(patch.taskInfo.stage != null, "Invalid stage update. 'stage' can not be null if taskInfo is provided");
      checkState(patch.taskInfo.stage.ordinal() >= current.taskInfo.stage.ordinal(),
          "Invalid stage update. Can not revert to %s from %s", patch.taskInfo.stage, current.taskInfo.stage);

      if (patch.taskInfo.subStage != null && current.taskInfo.subStage != null) {
        checkState(patch.taskInfo.subStage.ordinal() >= current.taskInfo.subStage.ordinal(),
            "Invalid stage update. 'subStage' cannot move back.");
      }
    }

    checkArgument(patch.imageWatermarkTime == null, "imageWatermarkTime cannot be changed.");
  }

  /**
   * Applies patch to current document state.
   *
   * @param current
   * @param patch
   */
  protected void applyPatch(State current, State patch) {
    if (patch.taskInfo != null) {
      ServiceUtils.logInfo(this, "stage update: %s to %s", current.taskInfo.stage, patch.taskInfo.stage);
      current.taskInfo = patch.taskInfo;
    }

    if (patch.dataStore != null) {
      current.dataStore = patch.dataStore;
    }

    if (patch.host != null) {
      current.host = patch.host;
    }

    if (patch.dataStoreCount != null) {
      current.dataStoreCount = patch.dataStoreCount;
    }

    if (patch.finishedDeletes != null) {
      current.finishedDeletes = patch.finishedDeletes;
    }

    if (patch.failedOrCanceledDeletes != null) {
      current.failedOrCanceledDeletes = patch.failedOrCanceledDeletes;
    }
  }

  /**
   * Retrieves the ZookeeperHostMonitor from the host.
   *
   * @return
   */
  @VisibleForTesting
  protected ZookeeperHostMonitor getZookeeperHostMonitor() {
    return ((ZookeeperHostMonitorProvider) getHost()).getZookeeperHostMonitor();
  }

  /**
   * Retrieves the host client from the host.
   *
   * @return
   */
  @VisibleForTesting
  protected HostClient getHostClient() {
    return ((HostClientProvider) getHost()).getHostClient();
  }

  /**
   * Does the processing necessary to perform the started stage.
   *
   * @param current
   */
  private void processStartedStage(final State current, final State patch)
      throws IOException, RpcException {
    switch (current.taskInfo.subStage) {
      case GET_REFERENCE_DATASTORE_INFO:
        this.processGetReferenceDatastoreInfo(current);
        break;
      case TRIGGER_DELETES:
        this.processTriggerDeletes(current);
        break;
      case AWAIT_COMPLETION:
        // Check if all copies have completed.
        this.processAwaitCompletion(current, patch);
        break;
      default:
        failTask(
            new RuntimeException(
                String.format("un-expected substage: %s", current.taskInfo.subStage))
        );
    }
  }

  /**
   * Get the reference datastore by iterating through all datastores.
   *
   * @param current
   */
  private void processGetReferenceDatastoreInfo(final State current) {
    try {
      Set<Datastore> imageDatastores = getZookeeperHostMonitor().getImageDatastores();
      checkState(imageDatastores.size() > 0, "No reference datastore found.");
      if (imageDatastores.size() > 1) {
        // log a warning if we detected more than one image datastores
        ServiceUtils.logWarning(ImageCleanerService.this,
            "There were %s image datastores detected. Only 1 image datastore is expected. %s",
            imageDatastores.size(), Utils.toJson(imageDatastores));
      }
      Datastore imageDatastore = ServiceUtils.selectRandomItem(imageDatastores);

      Set<HostConfig> hosts = getZookeeperHostMonitor().getHostsForDatastore(imageDatastore.getId());
      checkState(hosts.size() > 0, "No hosts found for reference datastore. [%s].", imageDatastore.getId());
      HostConfig host = ServiceUtils.selectRandomItem(hosts);

      if (current.isSelfProgressionDisabled) {
        return;
      }

      // move to next stage
      State patch = buildPatch(
          TaskState.TaskStage.STARTED, TaskState.SubStage.TRIGGER_DELETES, null);
      patch.host = host.getAddress().getHost();
      patch.dataStore = imageDatastore.getId();

      sendSelfPatch(patch);
    } catch (Exception e) {
      failTask(e);
    }
  }

  /**
   * Retrieves the list of datastores in the system and triggers an ImageDatastoreSweeperService
   * instance for each.
   *
   * @param current
   */
  private void processTriggerDeletes(final State current) {
    try {
      Set<Datastore> imageDatastores = getZookeeperHostMonitor().getImageDatastores();
      ServiceUtils.logInfo(this,
          "getImageDatastores returned %s. [count=%s]", Utils.toJson(imageDatastores), imageDatastores.size());

      Set<Datastore> allDatastores = getZookeeperHostMonitor().getAllDatastores();
      ServiceUtils.logInfo(this,
          "getAllDatastores returned %s. [count=%s]", Utils.toJson(allDatastores), allDatastores.size());

      // create the ImageDatastoreSweeperService instances
      int dataStoreSweeperCount = 0;
      for (Datastore datastore : allDatastores) {
        boolean isImageDatastore =
            imageDatastores.stream().anyMatch(d -> StringUtils.equals(d.getId(), datastore.getId()));

        triggerImageDatastoreSweeperService(current, datastore, isImageDatastore);
        dataStoreSweeperCount++;
      }

      if (current.isSelfProgressionDisabled) {
        return;
      }

      // move to next stage
      State patch = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.AWAIT_COMPLETION, null);
      patch.dataStoreCount = dataStoreSweeperCount;

      sendSelfPatch(patch);
    } catch (Exception e) {
      failTask(e);
    }
  }

  /**
   * Triggers an ImageDatastoreSweeperService instance for the image in the state and the datastore passed
   * as a parameter.
   *
   * @param dataStore
   */
  private void triggerImageDatastoreSweeperService(final State current,
                                                   final Datastore dataStore,
                                                   final boolean isImageDatastore) {
    // build completion handler
    Operation.CompletionHandler handler = (acknowledgeOp, failure) -> {
      if (failure != null) {
        // we could not start an ImageDatastoreSweeperService task. Something went horribly wrong. Fail
        // the current task and stop processing.
        RuntimeException e = new RuntimeException(
            String.format("Failed to send delete request %s", failure));
        failTask(e);
      }
    };

    // build start state
    ImageDatastoreSweeperService.State request = new ImageDatastoreSweeperService.State();
    request.datastore = dataStore.getId();
    request.parentLink = this.getSelfLink();
    request.imageCreateWatermarkTime = current.imageWatermarkTime;
    request.imageDeleteWatermarkTime = current.imageDeleteWatermarkTime;
    request.hostPollInterval = current.queryPollDelay;
    request.isImageDatastore = isImageDatastore;
    request.documentExpirationTimeMicros = current.documentExpirationTimeMicros;

    // start service
    Operation operation = Operation
        .createPost(UriUtils.buildUri(getHost(), ImageDatastoreSweeperServiceFactory.SELF_LINK))
        .setBody(request)
        .setCompletion(handler);
    this.sendRequest(operation);
  }

  /**
   * Determines if all child services have completed successfully.
   *
   * @param current
   * @param patch
   */
  private void processAwaitCompletion(final State current, final State patch) {
    if (current.finishedDeletes != null
        && current.dataStoreCount.equals(current.finishedDeletes)) {
      // all copies have completed successfully
      this.sendSelfPatch(buildPatch(TaskState.TaskStage.FINISHED, null, null));
      return;
    }

    if (current.finishedDeletes != null
        && current.failedOrCanceledDeletes != null
        && current.dataStoreCount.equals(current.finishedDeletes + current.failedOrCanceledDeletes)) {
      // all copies have completed, but some of them have failed
      RuntimeException e = new RuntimeException(
          String.format("Removal failed: %s deletes succeeded, %s deletes failed",
              current.finishedDeletes,
              current.failedOrCanceledDeletes)
      );
      this.failTask(e);
      return;
    }

    // determine if we have already received answers from queries that check for completion
    // of ImageDatastoreSweeperService instances
    boolean isFirstCheck = current.finishedDeletes == null
        && current.failedOrCanceledDeletes == null;

    if (isFirstCheck || patch.finishedDeletes != null) {
      // issue the query to get the count of finished ImageDatastoreSweeperService instances,
      // because we either have not yet run the query yet or we have just processed the patch
      // from the previous query
      getHost().schedule(() -> checkFailedOrCancelledCount(current), current.queryPollDelay, TimeUnit.MILLISECONDS);
    }

    if (patch.failedOrCanceledDeletes != null) {
      // issue the query to get the count of failed or cancelled ImageDatastoreSweeperService instances,
      // because we either have not run the query yet or we have just processed the patch
      // from the previous query
      getHost().schedule(() -> checkFinishedCount(current), current.queryPollDelay, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Triggers a query to retrieve the "child" ImageDatastoreSweeperService instances in FINISHED state.
   *
   * @param current
   */
  private void checkFinishedCount(final State current) {
    Operation.CompletionHandler handler = (completedOp, failure) -> {
      if (failure != null) {
        // The query failed to execute. This most likely means that the
        // host is in a bad state and if we re-issue the query it is likely
        // to fail again. Terminate and fail the task early and delegate any
        // retry logic to the caller.
        failTask(failure);
        return;
      }

      QueryTask rsp = completedOp.getBody(QueryTask.class);

      State s = buildPatch(current.taskInfo.stage, current.taskInfo.subStage, null);
      ServiceUtils.logInfo(ImageCleanerService.this, "Finished %s", Utils.toJson(rsp.results.documentLinks));

      s.finishedDeletes = rsp.results.documentLinks.size();
      sendSelfPatch(s);
    };

    QueryTask.QuerySpecification spec =
        QueryTaskUtils.buildChildServiceTaskStatusQuerySpec(
            this.getSelfLink(), ImageDatastoreSweeperService.State.class, TaskState.TaskStage.FINISHED);

    this.sendQuery(spec, handler);
  }

  /**
   * Triggers a query to retrieve the "child" ImageDatastoreSweeperService instances in FAILED or CANCELLED state.
   *
   * @param current
   */
  private void checkFailedOrCancelledCount(final State current) {
    Operation.CompletionHandler handler = (completedOp, failure) -> {
      if (failure != null) {
        // The query failed to execute. This most likely means that the
        // host is in a bad state and if we re-issue the query it is likely
        // to fail again. Terminate and fail the task early and delegate any
        // retry logic to the caller.
        failTask(failure);
        return;
      }

      QueryTask rsp = completedOp.getBody(QueryTask.class);

      State s = buildPatch(current.taskInfo.stage, current.taskInfo.subStage, null);
      ServiceUtils.logInfo(ImageCleanerService.this, "Failed or Cancelled %s",
          Utils.toJson(rsp.results.documentLinks));

      s.failedOrCanceledDeletes = rsp.results.documentLinks.size();
      sendSelfPatch(s);
    };

    QueryTask.QuerySpecification spec =
        QueryTaskUtils.buildChildServiceTaskStatusQuerySpec(
            this.getSelfLink(),
            ImageDatastoreSweeperService.State.class,
            TaskState.TaskStage.FAILED,
            TaskState.TaskStage.CANCELLED);

    this.sendQuery(spec, handler);
  }

  /**
   * This method sends a DCP query.
   *
   * @param spec
   * @param handler
   */
  private void sendQuery(final QueryTask.QuerySpecification spec, final Operation.CompletionHandler handler) {
    QueryTask task = QueryTask.create(spec)
        .setDirect(true);

    Operation queryPost = Operation
        .createPost(UriUtils.buildUri(getHost(), LuceneQueryTaskFactoryService.SELF_LINK))
        .setBody(task)
        .setCompletion(handler);

    sendRequest(queryPost);
  }

  /**
   * Moves the service into the FAILED state.
   *
   * @param e
   */
  private void failTask(Throwable e) {
    ServiceUtils.logSevere(this, e);
    this.sendSelfPatch(buildPatch(TaskState.TaskStage.FAILED, null, e));
  }

  /**
   * Send a patch message to ourselves to update the execution stage.
   *
   * @param stage
   */
  private void sendStageProgressPatch(
      final State current,
      final TaskState.TaskStage stage,
      final TaskState.SubStage subStage) {
    if (current != null && current.isSelfProgressionDisabled) {
      return;
    }

    this.sendSelfPatch(buildPatch(stage, subStage, null));
  }

  /**
   * Send a patch message to ourselves to update the execution stage.
   *
   * @param s
   */
  private void sendSelfPatch(State s) {
    Operation patch = Operation
        .createPatch(UriUtils.buildUri(getHost(), getSelfLink()))
        .setBody(s);
    sendRequest(patch);
  }

  /**
   * Build a state object that can be used to submit a stage progress
   * self patch.
   *
   * @param stage
   * @param e
   * @return
   */
  private State buildPatch(
      final TaskState.TaskStage stage,
      final TaskState.SubStage subSatge,
      final Throwable e) {
    State s = new State();
    s.taskInfo = new TaskState();
    s.taskInfo.stage = stage;
    s.taskInfo.subStage = subSatge;

    if (e != null) {
      s.taskInfo.failure = Utils.toServiceErrorResponse(e);
    }

    return s;
  }

  /**
   * Service execution stages.
   */
  public static class TaskState extends com.vmware.dcp.common.TaskState {
    /**
     * The execution substage.
     */
    public SubStage subStage;

    /**
     * Execution sub-stage.
     */
    public static enum SubStage {
      GET_REFERENCE_DATASTORE_INFO,
      TRIGGER_DELETES,
      AWAIT_COMPLETION
    }
  }

  /**
   * Class defines the durable state of the ImageCleanerService.
   */
  public static class State extends ServiceDocument {

    /**
     * Service execution state.
     */
    public TaskState taskInfo;

    /**
     * Flag indicating if the service should be "self-driving".
     * (i.e. automatically progress through it's stages)
     */
    public boolean isSelfProgressionDisabled;

    /**
     * Time in milliseconds to delay before issuing query tasks.
     */
    public Integer queryPollDelay;

    /**
     * IP address of host having access to reference datastore.
     */
    public String host;

    /**
     * The dataStore id corresponding to dataStoreInventoryName.
     */
    public String dataStore;

    /**
     * The timestamp indicating when the reference images were retrieved.
     */
    public Long imageWatermarkTime;

    /**
     * The timestamp indicating the cutoff for unused images deletion.
     */
    public Long imageDeleteWatermarkTime;

    /**
     * Count of datastores in the system. One ImageDatastoreSweeperService instance
     * is created per datastore to perform the image delete.
     */
    public Integer dataStoreCount;

    /**
     * Count of individual copies in FINISHED state.
     */
    public Integer finishedDeletes;

    /**
     * Count of individual copies in FAILED or CANCELED state.
     */
    public Integer failedOrCanceledDeletes;
  }
}
