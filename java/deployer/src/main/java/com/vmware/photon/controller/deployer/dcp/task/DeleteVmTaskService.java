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

package com.vmware.photon.controller.deployer.dcp.task;

import com.vmware.dcp.common.Operation;
import com.vmware.dcp.common.Service;
import com.vmware.dcp.common.ServiceDocument;
import com.vmware.dcp.common.StatefulService;
import com.vmware.dcp.common.TaskState;
import com.vmware.dcp.common.Utils;
import com.vmware.photon.controller.api.Task;
import com.vmware.photon.controller.common.dcp.InitializationUtils;
import com.vmware.photon.controller.common.dcp.PatchUtils;
import com.vmware.photon.controller.common.dcp.ServiceUtils;
import com.vmware.photon.controller.common.dcp.TaskUtils;
import com.vmware.photon.controller.common.dcp.ValidationUtils;
import com.vmware.photon.controller.common.dcp.validation.DefaultInteger;
import com.vmware.photon.controller.common.dcp.validation.DefaultTaskState;
import com.vmware.photon.controller.common.dcp.validation.Immutable;
import com.vmware.photon.controller.common.dcp.validation.NotNull;
import com.vmware.photon.controller.deployer.dcp.constant.DeployerDefaults;
import com.vmware.photon.controller.deployer.dcp.util.ApiUtils;
import com.vmware.photon.controller.deployer.dcp.util.ControlFlags;
import com.vmware.photon.controller.deployer.dcp.util.HostUtils;

import com.google.common.util.concurrent.FutureCallback;

import javax.annotation.Nullable;

/**
 * This class implements a DCP micro-service which performs the task of
 * deploying an ESX Cloud agent instance.
 */
public class DeleteVmTaskService extends StatefulService {

  /**
   * This class defines the document state associated with a single
   * {@link DeleteVmTaskService} instance.
   */
  public static class State extends ServiceDocument {
    /**
     * This value represents the state of the current task.
     */
    @DefaultTaskState(value = TaskState.TaskStage.STARTED)
    public TaskState taskState;

    /**
     * This value represents the control flags for the operation.
     */
    @DefaultInteger(value = 0)
    @Immutable
    public Integer controlFlags;

    /**
     * This value represents the unique identifier of the VM to be deleted.
     */
    @NotNull
    @Immutable
    public String vmId;

    /**
     * This value represents the delay interval to use when polling the status
     * of the task object generated by the API call, in milliseconds.
     */
    @DefaultInteger(value = DeployerDefaults.DEFAULT_TASK_POLL_DELAY)
    public Integer taskPollDelay;
  }

  public DeleteVmTaskService() {
    super(DeleteVmTaskService.State.class);
    super.toggleOption(ServiceOption.OWNER_SELECTION, true);
    super.toggleOption(ServiceOption.PERSISTENCE, true);
    super.toggleOption(ServiceOption.REPLICATION, true);
  }

  /**
   * This method is called when a start operation is performed for the current service instance.
   *
   * @param start
   */
  @Override
  public void handleStart(Operation start) {
    ServiceUtils.logInfo(this, "Starting service %s", getSelfLink());
    State startState = start.getBody(State.class);
    InitializationUtils.initialize(startState);
    validateState(startState);

    if (TaskState.TaskStage.CREATED == startState.taskState.stage) {
      startState.taskState.stage = TaskState.TaskStage.STARTED;
    }

    start.setBody(startState).complete();

    try {
      if (ControlFlags.isOperationProcessingDisabled(startState.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping start operation processing (disabled)");
      } else if (TaskState.TaskStage.STARTED == startState.taskState.stage) {
        TaskUtils.sendSelfPatch(this, buildPatch(startState.taskState.stage, null));
      }
    } catch (Throwable t) {
      failTask(t);
    }
  }

  /**
   * This method is called when a patch operation is performed on the current service.
   *
   * @param patch
   */
  @Override
  public void handlePatch(Operation patch) {
    ServiceUtils.logInfo(this, "Handling patch for service %s", getSelfLink());
    State startState = getState(patch);
    State patchState = patch.getBody(State.class);
    validatePatchState(startState, patchState);
    PatchUtils.patchState(startState, patchState);
    validateState(startState);
    patch.complete();

    try {
      if (ControlFlags.isOperationProcessingDisabled(startState.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping start operation processing (disabled)");
      } else if (TaskState.TaskStage.STARTED == startState.taskState.stage) {
        deleteVm(startState);
      }
    } catch (Throwable t) {
      failTask(t);
    }
  }

  /**
   * This method validates the state of a service document for internal
   * consistency.
   *
   * @param currentState
   */
  private void validateState(State currentState) {
    ValidationUtils.validateState(currentState);
    ValidationUtils.validateTaskStage(currentState.taskState);
  }

  /**
   * This method validates a patch against a valid service document.
   *
   * @param startState
   * @param patchState
   */
  private void validatePatchState(State startState, State patchState) {
    ValidationUtils.validatePatch(startState, patchState);
    ValidationUtils.validateTaskStage(patchState.taskState);
    ValidationUtils.validateTaskStageProgression(startState.taskState, patchState.taskState);
  }

  private void deleteVm(final State currentState) throws Throwable {

    HostUtils.getApiClient(this).getVmApi().deleteAsync(currentState.vmId,
        new FutureCallback<Task>() {
          @Override
          public void onSuccess(@Nullable Task result) {
            try {
              processTask(currentState, result);
            } catch (Throwable e) {
              failTask(e);
            }
          }

          @Override
          public void onFailure(Throwable t) {
            failTask(t);
          }
        });
  }

  private void processTask(final State currentState, final Task task) {
    final Service service = this;

    FutureCallback<Task> pollTaskCallback = new FutureCallback<Task>() {
      @Override
      public void onSuccess(@Nullable Task result) {
        TaskUtils.sendSelfPatch(service, buildPatch(TaskState.TaskStage.FINISHED, null));
      }

      @Override
      public void onFailure(Throwable t) {
        failTask(t);
      }
    };

    ApiUtils.pollTaskAsync(task,
        HostUtils.getApiClient(this),
        this,
        currentState.taskPollDelay,
        pollTaskCallback);
  }

  /**
   * This method sends a patch operation to the current service instance
   * to move to the FAILED state in response to the specified exception.
   *
   * @param e
   */
  private void failTask(Throwable e) {
    ServiceUtils.logSevere(this, e);
    TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FAILED, e));
  }

  /**
   * This method builds a state object which can be used to submit a state
   * progress self-patch.
   *
   * @param stage
   * @param e
   * @return
   */
  protected State buildPatch(TaskState.TaskStage stage, @Nullable Throwable e) {
    State state = new State();
    state.taskState = new TaskState();
    state.taskState.stage = stage;

    if (null != e) {
      state.taskState.failure = Utils.toServiceErrorResponse(e);
    }

    return state;
  }
}
