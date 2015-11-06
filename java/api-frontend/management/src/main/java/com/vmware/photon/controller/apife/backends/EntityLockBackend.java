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

package com.vmware.photon.controller.apife.backends;

import com.vmware.photon.controller.api.common.entities.base.BaseEntity;
import com.vmware.photon.controller.api.common.exceptions.external.ConcurrentTaskException;
import com.vmware.photon.controller.apife.entities.StepEntity;
import com.vmware.photon.controller.apife.entities.TaskEntity;

/**
 * Entity Lock operation.
 */
public interface EntityLockBackend {

  void setStepLock(BaseEntity entity, StepEntity step) throws ConcurrentTaskException;

  void clearLocks(StepEntity step);

  void setTaskLock(String entityId, TaskEntity task) throws ConcurrentTaskException;

  void clearTaskLocks(TaskEntity task);

  Boolean lockExistsForEntityId(String entityId);
}
