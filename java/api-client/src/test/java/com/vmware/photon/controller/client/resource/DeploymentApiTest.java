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

package com.vmware.photon.controller.client.resource;

import com.vmware.photon.controller.api.AuthInfo;
import com.vmware.photon.controller.api.Deployment;
import com.vmware.photon.controller.api.ResourceList;
import com.vmware.photon.controller.api.Task;
import com.vmware.photon.controller.api.Vm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.FutureCallback;
import org.apache.http.HttpStatus;
import org.testng.annotations.Test;
import org.testng.internal.Nullable;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.AssertJUnit.fail;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests {@link DeploymentApi}.
 */
public class DeploymentApiTest extends ApiTestBase {

  @Test
  public void testListAll() throws IOException {
    Deployment deployment = getNewDeployment();

    ResourceList<Deployment> deploymentResourceList = new ResourceList<>(Arrays.asList(deployment));

    ObjectMapper mapper = new ObjectMapper();
    String serializedResponse = mapper.writeValueAsString(deploymentResourceList);

    setupMocks(serializedResponse, HttpStatus.SC_OK);

    DeploymentApi deploymentApi = new DeploymentApi(restClient);

    ResourceList<Deployment> response = deploymentApi.listAll();
    assertEquals(response.getItems().size(), deploymentResourceList.getItems().size());
    assertTrue(response.getItems().containsAll(deploymentResourceList.getItems()));
  }

  @Test
  public void testListAllAsync() throws Exception {
    Deployment deployment = getNewDeployment();

    ResourceList<Deployment> deploymentResourceList = new ResourceList<>(Arrays.asList(deployment));

    ObjectMapper mapper = new ObjectMapper();
    String serializedResponse = mapper.writeValueAsString(deploymentResourceList);

    setupMocks(serializedResponse, HttpStatus.SC_OK);

    DeploymentApi deploymentApi = new DeploymentApi(restClient);

    final CountDownLatch latch = new CountDownLatch(1);

    deploymentApi.listAllAsync(new FutureCallback<ResourceList<Deployment>>() {
      @Override
      public void onSuccess(@Nullable ResourceList<Deployment> result) {
        assertEquals(result.getItems(), deploymentResourceList.getItems());
        latch.countDown();
      }

      @Override
      public void onFailure(Throwable t) {
        fail(t.toString());
        latch.countDown();
      }
    });

    assertThat(latch.await(COUNTDOWNLATCH_AWAIT_TIMEOUT, TimeUnit.SECONDS), is(true));
  }

  @Test
  public void testPauseSystem() throws IOException {
    Task responseTask = getExpectedTaskResponse();
    DeploymentApi deploymentApi = new DeploymentApi(restClient);

    Task task = deploymentApi.pauseSystem("deploymentId1");
    assertEquals(task, responseTask);
  }

  @Test
  public void testPauseSystemAsync() throws IOException, InterruptedException {
    final Task responseTask = getExpectedTaskResponse();
    DeploymentApi deploymentApi = new DeploymentApi(restClient);

    final CountDownLatch latch = new CountDownLatch(1);

    deploymentApi.pauseSystemAsync("deploymentId1", new FutureCallback<Task>() {
      @Override
      public void onSuccess(Task result) {
        assertEquals(result, responseTask);
        latch.countDown();
      }

      @Override
      public void onFailure(Throwable t) {
        fail(t.toString());
        latch.countDown();
      }
    });

    assertThat(latch.await(COUNTDOWNLATCH_AWAIT_TIMEOUT, TimeUnit.SECONDS), is(true));
  }

  @Test
  public void testResumeSystem() throws IOException {
    Task responseTask = getExpectedTaskResponse();
    DeploymentApi deploymentApi = new DeploymentApi(restClient);

    Task task = deploymentApi.resumeSystem("deploymentId1");
    assertEquals(task, responseTask);
  }

  @Test
  public void testResumeSystemAsync() throws Exception {
    Task responseTask = getExpectedTaskResponse();
    DeploymentApi deploymentApi = new DeploymentApi(restClient);

    final CountDownLatch latch = new CountDownLatch(1);

    deploymentApi.resumeSystemAsync("deploymentId1", new FutureCallback<Task>() {
      @Override
      public void onSuccess(Task result) {
        assertEquals(result, responseTask);
        latch.countDown();
      }

      @Override
      public void onFailure(Throwable t) {
        fail(t.toString());
        latch.countDown();
      }
    });

    assertThat(latch.await(COUNTDOWNLATCH_AWAIT_TIMEOUT, TimeUnit.SECONDS), is(true));
  }

  private Task getExpectedTaskResponse() throws IOException {
    final Task responseTask = new Task();
    responseTask.setId("12345");
    responseTask.setState("QUEUED");
    responseTask.setQueuedTime(Date.from(Instant.now()));

    ObjectMapper mapper = new ObjectMapper();
    String serializedTask = mapper.writeValueAsString(responseTask);

    setupMocks(serializedTask, HttpStatus.SC_CREATED);
    return responseTask;
  }

  private Deployment getNewDeployment() {
    Deployment deployment = new Deployment();
    deployment.setId("deployment1");
    deployment.setAuth(new AuthInfo());
    return deployment;
  }

  @Test
  public void testGetVms() throws IOException {
    Vm vm1 = new Vm();
    vm1.setId("vm1");

    Vm vm2 = new Vm();
    vm2.setId("vm2");

    final ResourceList<Vm> vmList =
        new ResourceList<>(Arrays.asList(vm1, vm2));

    ObjectMapper mapper = new ObjectMapper();
    String serializedTask = mapper.writeValueAsString(vmList);

    setupMocks(serializedTask, HttpStatus.SC_OK);

    DeploymentApi deploymentApi = new DeploymentApi(restClient);

    ResourceList<Vm> response = deploymentApi.getAllDeploymentVms("foo");
    assertEquals(response.getItems().size(), vmList.getItems().size());
    assertTrue(response.getItems().containsAll(vmList.getItems()));
  }

  @Test
  public void testGetHostsAsync() throws IOException, InterruptedException {
    Vm vm1 = new Vm();
    vm1.setId("vm1");

    Vm vm2 = new Vm();
    vm2.setId("vm2");

    final ResourceList<Vm> vmList =
        new ResourceList<>(Arrays.asList(vm1, vm2));

    ObjectMapper mapper = new ObjectMapper();
    String serializedTask = mapper.writeValueAsString(vmList);

    setupMocks(serializedTask, HttpStatus.SC_OK);

    DeploymentApi deploymentApi = new DeploymentApi(restClient);
    final CountDownLatch latch = new CountDownLatch(1);

    deploymentApi.getAllDeploymentVmsAsync("foo", new FutureCallback<ResourceList<Vm>>() {
      @Override
      public void onSuccess(ResourceList<Vm> result) {
        assertEquals(result.getItems(), vmList.getItems());
        latch.countDown();
      }

      @Override
      public void onFailure(Throwable t) {
        fail(t.toString());
        latch.countDown();
      }
    });

    assertThat(latch.await(COUNTDOWNLATCH_AWAIT_TIMEOUT, TimeUnit.SECONDS), is(true));
  }
}
