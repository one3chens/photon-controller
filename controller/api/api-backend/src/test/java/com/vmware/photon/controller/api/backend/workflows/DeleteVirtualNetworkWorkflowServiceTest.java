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

package com.vmware.photon.controller.api.backend.workflows;

import com.vmware.photon.controller.api.backend.helpers.ReflectionUtils;
import com.vmware.photon.controller.api.backend.helpers.TestEnvironment;
import com.vmware.photon.controller.api.backend.helpers.TestHelper;
import com.vmware.photon.controller.api.backend.helpers.mocks.MockSubnetConfigurationService;
import com.vmware.photon.controller.api.backend.servicedocuments.DeleteVirtualNetworkWorkflowDocument;
import com.vmware.photon.controller.api.backend.utils.TaskStateHelper;
import com.vmware.photon.controller.api.model.Project;
import com.vmware.photon.controller.api.model.QuotaLineItem;
import com.vmware.photon.controller.api.model.QuotaUnit;
import com.vmware.photon.controller.api.model.RoutingType;
import com.vmware.photon.controller.api.model.SubnetState;
import com.vmware.photon.controller.api.model.VmState;
import com.vmware.photon.controller.cloudstore.xenon.entity.DeploymentService;
import com.vmware.photon.controller.cloudstore.xenon.entity.DeploymentServiceFactory;
import com.vmware.photon.controller.cloudstore.xenon.entity.DhcpSubnetService;
import com.vmware.photon.controller.cloudstore.xenon.entity.IpLeaseService;
import com.vmware.photon.controller.cloudstore.xenon.entity.ProjectService;
import com.vmware.photon.controller.cloudstore.xenon.entity.ProjectServiceFactory;
import com.vmware.photon.controller.cloudstore.xenon.entity.ResourceTicketService;
import com.vmware.photon.controller.cloudstore.xenon.entity.ResourceTicketServiceFactory;
import com.vmware.photon.controller.cloudstore.xenon.entity.SubnetAllocatorService;
import com.vmware.photon.controller.cloudstore.xenon.entity.TaskService;
import com.vmware.photon.controller.cloudstore.xenon.entity.TombstoneService;
import com.vmware.photon.controller.cloudstore.xenon.entity.VirtualNetworkService;
import com.vmware.photon.controller.cloudstore.xenon.entity.VmService;
import com.vmware.photon.controller.cloudstore.xenon.entity.VmServiceFactory;
import com.vmware.photon.controller.common.tests.nsx.NsxClientMock;
import com.vmware.photon.controller.common.xenon.CloudStoreHelper;
import com.vmware.photon.controller.common.xenon.ControlFlags;
import com.vmware.photon.controller.common.xenon.QueryTaskUtils;
import com.vmware.photon.controller.common.xenon.ServiceUtils;
import com.vmware.photon.controller.common.xenon.exceptions.DocumentNotFoundException;
import com.vmware.photon.controller.common.xenon.exceptions.XenonRuntimeException;
import com.vmware.photon.controller.common.xenon.validation.Immutable;
import com.vmware.photon.controller.common.xenon.validation.WriteOnce;
import com.vmware.photon.controller.nsxclient.NsxClientFactory;
import com.vmware.xenon.common.FactoryService;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.TaskState;
import com.vmware.xenon.services.common.NodeGroupBroadcastResponse;
import com.vmware.xenon.services.common.QueryTask;

import com.google.common.collect.ImmutableMap;
import org.hamcrest.CoreMatchers;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.testng.Assert.fail;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * This class implements tests for the {@link com.vmware.photon.controller.apibackend.workflows
 * .DeleteVirtualNetworkWorkflowService} class.
 */
public class DeleteVirtualNetworkWorkflowServiceTest {

  protected static final String SNAT_IP_ADDRESS = "0.0.0.1";

  private static TaskStateHelper<DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage> taskStateHelper =
      new TaskStateHelper<>(DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage.class);

  /**
   * This method is a dummy test case which forces IntelliJ to recognize the
   * current class as a test class.
   */
  @Test(enabled = false)
  private void dummy() {
  }

  /**
   * This method creates a new DeleteVirtualNetworkWorkflowDocument object to create
   * a new DeleteVirtualNetworkTaskService instance.
   */
  private static DeleteVirtualNetworkWorkflowDocument buildValidStartState(
      TaskState.TaskStage stage,
      DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage subStage,
      int controlFlags,
      String virtualNetworkId) {
    DeleteVirtualNetworkWorkflowDocument startState = new DeleteVirtualNetworkWorkflowDocument();
    startState.taskState = new DeleteVirtualNetworkWorkflowDocument.TaskState();
    startState.taskState.stage = stage;
    startState.taskState.subStage = subStage;
    startState.controlFlags = controlFlags;
    startState.networkId = virtualNetworkId;

    return startState;
  }

  /**
   * This method creates a patch State object which is sufficient to patch a
   * DeleteVirtualNetworkTaskService instance.
   */
  private static DeleteVirtualNetworkWorkflowDocument buildPatch(
      TaskState.TaskStage stage,
      DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage subStage) {
    DeleteVirtualNetworkWorkflowDocument state = new DeleteVirtualNetworkWorkflowDocument();
    state.taskState = new DeleteVirtualNetworkWorkflowDocument.TaskState();
    state.taskState.stage = stage;
    state.taskState.subStage = subStage;
    return state;
  }

  /**
   * This method creates VirtualNetworkService.State object in cloud-store.
   */
  private static VirtualNetworkService.State createVirtualNetworkDocumentInCloudStore(TestEnvironment testEnvironment)
      throws Throwable {
    VirtualNetworkService.State virtualNetwork = new VirtualNetworkService.State();
    virtualNetwork.name = "virtual_network_name";
    virtualNetwork.state = SubnetState.CREATING;
    virtualNetwork.routingType = RoutingType.ROUTED;
    virtualNetwork.parentId = "project-id";
    virtualNetwork.parentKind = Project.KIND;
    virtualNetwork.tier0RouterId = "logical_tier0_router_id";
    virtualNetwork.logicalRouterId = "logical_tier1_router_id";
    virtualNetwork.logicalSwitchId = "logical_switch_id";
    virtualNetwork.size = 16;
    virtualNetwork.isSizeQuotaConsumed = true;
    virtualNetwork.isIpAddressSpaceConsumed = true;
    virtualNetwork.snatIp = SNAT_IP_ADDRESS;

    Operation result = testEnvironment.sendPostAndWait(VirtualNetworkService.FACTORY_LINK, virtualNetwork);
    assertThat(result.getStatusCode(), is(Operation.STATUS_CODE_OK));

    VirtualNetworkService.State createdState = result.getBody(VirtualNetworkService.State.class);
    VirtualNetworkService.State patchState = new VirtualNetworkService.State();
    patchState.state = SubnetState.READY;
    result = testEnvironment.sendPatchAndWait(createdState.documentSelfLink, patchState);
    assertThat(result.getStatusCode(), is(Operation.STATUS_CODE_OK));

    return result.getBody(VirtualNetworkService.State.class);
  }

  /**
   * This class implements tests for the initial service state.
   */
  public static class InitializationTest {

    private DeleteVirtualNetworkWorkflowService deleteVirtualNetworkWorkflowService;

    @BeforeMethod
    public void setUpTest() {
      deleteVirtualNetworkWorkflowService = new DeleteVirtualNetworkWorkflowService();
    }

    @AfterMethod
    public void tearDownTest() {
      deleteVirtualNetworkWorkflowService = null;
    }

    @Test
    public void testCapabilities() {

      EnumSet<Service.ServiceOption> expected = EnumSet.of(
          Service.ServiceOption.CONCURRENT_GET_HANDLING,
          Service.ServiceOption.INSTRUMENTATION,
          Service.ServiceOption.OWNER_SELECTION,
          Service.ServiceOption.PERSISTENCE,
          Service.ServiceOption.REPLICATION);

      assertThat(deleteVirtualNetworkWorkflowService.getOptions(), is(expected));
    }
  }

  /**
   * Tests that when {@link com.vmware.photon.controller.apibackend.workflows
   * .DeleteVirtualNetworkWorkflowService#handleCreate} is called, the workflow will validate the state object and
   * behave correctly.
   */
  public static class HandleCreateTest {

    private DeleteVirtualNetworkWorkflowDocument startState;
    private TestEnvironment testEnvironment;

    @BeforeMethod
    public void setUpTest() throws Throwable {
      testEnvironment = new TestEnvironment.Builder()
          .hostCount(1)
          .cloudStoreHelper(new CloudStoreHelper())
          .build();

      VirtualNetworkService.State virtualNetworkDocument = createVirtualNetworkDocumentInCloudStore(testEnvironment);
      startState = buildValidStartState(
          TaskState.TaskStage.CREATED,
          null,
          new ControlFlags.Builder()
              .disableOperationProcessingOnHandleStart()
              .disableOperationProcessingOnHandlePatch()
              .build(),
          ServiceUtils.getIDFromDocumentSelfLink(virtualNetworkDocument.documentSelfLink));
    }

    @AfterMethod
    public void tearDownTest() throws Throwable {
      if (null != testEnvironment) {
        testEnvironment.stop();
        testEnvironment = null;
      }

      startState = null;
    }

    /**
     * Verifies that when a field of the initial state has null value and is annotated with
     * default value, the workflow will initialize the state with the default value and succeed.
     */
    @Test
    public void succeedsWithNullDefaultFields() throws Throwable {

      startState.taskState = null;
      DeleteVirtualNetworkWorkflowDocument finalState =
          testEnvironment.callServiceAndWaitForState(
              DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
              startState,
              DeleteVirtualNetworkWorkflowDocument.class,
              (state) -> TaskState.TaskStage.CREATED == state.taskState.stage);

      assertThat(finalState.taskState, notNullValue());
    }

    /**
     * Verifies that when a field of the initial state has null value but is annotated as mandatory,
     * the workflow will validate the state and fail.
     *
     * @throws Throwable
     */
    @Test
    public void failsWithNullMandatoryFields() throws Throwable {

      startState.networkId = null;
      try {
        testEnvironment.callServiceAndWaitForState(
            DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
            startState,
            DeleteVirtualNetworkWorkflowDocument.class,
            (state) -> TaskState.TaskStage.CREATED == state.taskState.stage);
      } catch (XenonRuntimeException ex) {
        assertThat(ex.getMessage(), containsString("networkId cannot be null"));
      }

    }

    /**
     * Verify the operation would fail properly when an invalid network ID is offered.
     *
     * @throws Throwable
     */
    @Test
    public void failsWithInvalidNetworkId() throws Throwable {
      startState.networkId = "invalid-id";
      try {
        testEnvironment.callServiceAndWaitForState(
            DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
            startState,
            DeleteVirtualNetworkWorkflowDocument.class,
            (state) -> TaskState.TaskStage.CREATED == state.taskState.stage
        );
      } catch (XenonRuntimeException e) {
        assertThat(e.getMessage(), is("Failed to get network " + startState.networkId));
      }
    }
  }

  /**
   * Test that when {@link com.vmware.photon.controller.apibackend.workflows
   * .DeleteVirtualNetworkWorkflowService#handleStart} is called, the workflow will validate the state object and
   * behave correctly.
   */
  public static class HandleStartTest {

    private DeleteVirtualNetworkWorkflowDocument startState;
    private TestEnvironment testEnvironment;
    private VirtualNetworkService.State virtualNetworkDocument;

    @BeforeMethod
    public void setUpTest() throws Throwable {
      testEnvironment = new TestEnvironment.Builder()
          .hostCount(1)
          .cloudStoreHelper(new CloudStoreHelper())
          .build();

      virtualNetworkDocument = createVirtualNetworkDocumentInCloudStore(testEnvironment);
      startState = buildValidStartState(
          TaskState.TaskStage.CREATED,
          null,
          new ControlFlags.Builder()
              .disableOperationProcessingOnHandleStart()
              .disableOperationProcessingOnHandlePatch()
              .build(),
          ServiceUtils.getIDFromDocumentSelfLink(virtualNetworkDocument.documentSelfLink));
    }

    @AfterMethod
    public void tearDownTest() throws Throwable {
      if (null != testEnvironment) {
        testEnvironment.stop();
        testEnvironment = null;
      }

      startState = null;
    }

    /**
     * Verifies that when a valid start state is given, the workflow will validate the state and succeed.
     */
    @Test
    public void succeedsWithValidStartState() throws Throwable {

      DeleteVirtualNetworkWorkflowDocument finalState =
          testEnvironment.callServiceAndWaitForState(
              DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
              startState,
              DeleteVirtualNetworkWorkflowDocument.class,
              (state) -> TaskState.TaskStage.CREATED == state.taskState.stage);

      assertThat(finalState.taskState, notNullValue());
    }

    /**
     * Verifies that when an invalid start state is given, the workflow will validate the state and fail.
     */
    @Test(dataProvider = "InvalidStartState", expectedExceptions = XenonRuntimeException.class)
    public void failsWithInvalidStartState(TaskState.TaskStage stage,
                                           DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage subStage)
        throws Throwable {

      startState = buildValidStartState(stage, subStage, new ControlFlags.Builder()
              .disableOperationProcessingOnHandleStart()
              .disableOperationProcessingOnHandlePatch()
              .build(),
          ServiceUtils.getIDFromDocumentSelfLink(virtualNetworkDocument.documentSelfLink));

      testEnvironment.callServiceAndWaitForState(
          DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
          startState,
          DeleteVirtualNetworkWorkflowDocument.class,
          (state) -> TaskState.TaskStage.CREATED == state.taskState.stage);
    }

    @DataProvider(name = "InvalidStartState")
    public Object[][] getInvalidStartStateTestData() throws Throwable {
      return taskStateHelper.getInvalidStartState();
    }
  }

  /**
   * Tests that when {@link com.vmware.photon.controller.apibackend.workflows
   * .DeleteVirtualNetworkWorkflowService#handlePatch} is called, the workflow will validate the state object and
   * behave correctly.
   */
  public static class HandlePatchTest {

    private DeleteVirtualNetworkWorkflowDocument startState;
    private TestEnvironment testEnvironment;

    @BeforeMethod
    public void setUpTest() throws Throwable {
      testEnvironment = new TestEnvironment.Builder()
          .hostCount(1)
          .cloudStoreHelper(new CloudStoreHelper())
          .build();

      VirtualNetworkService.State virtualNetworkDocument = createVirtualNetworkDocumentInCloudStore(testEnvironment);
      startState = buildValidStartState(
          TaskState.TaskStage.CREATED,
          null,
          new ControlFlags.Builder()
              .disableOperationProcessingOnHandlePatch()
              .disableOperationProcessingOnStageTransition()
              .build(),
          ServiceUtils.getIDFromDocumentSelfLink(virtualNetworkDocument.documentSelfLink));
    }

    @AfterMethod
    public void tearDownTest() throws Throwable {
      if (null != testEnvironment) {
        testEnvironment.stop();
        testEnvironment = null;
      }

      startState = null;
    }

    /**
     * Verifies that when a valid stage/sub-stage patch state is given, the workflow will validate
     * the state and succeed.
     */
    @Test(dataProvider = "ValidStageAndSubStagePatch")
    public void succeedsWithValidStageAndSubStagePatch(
        TaskState.TaskStage currentStage,
        DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage currentSubStage,
        TaskState.TaskStage patchStage,
        DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage patchSubStage
    ) throws Throwable {

      DeleteVirtualNetworkWorkflowDocument finalState =
          testEnvironment.callServiceAndWaitForState(
              DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
              startState,
              DeleteVirtualNetworkWorkflowDocument.class,
              (state) -> TaskState.TaskStage.STARTED == state.taskState.stage &&
                  DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage.CHECK_VM_EXISTENCE
                      == state.taskState.subStage);

      if (!(currentStage == TaskState.TaskStage.STARTED &&
          currentSubStage == DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage.CHECK_VM_EXISTENCE)) {
        testEnvironment.sendPatchAndWait(finalState.documentSelfLink,
            buildPatch(currentStage, currentSubStage));
      }

      finalState = testEnvironment.sendPatchAndWait(finalState.documentSelfLink,
          buildPatch(patchStage, patchSubStage))
          .getBody(DeleteVirtualNetworkWorkflowDocument.class);

      assertThat(finalState.taskState.stage, is(patchStage));
      assertThat(finalState.taskState.subStage, is(patchSubStage));
    }

    @DataProvider(name = "ValidStageAndSubStagePatch")
    public Object[][] getValidStageAndSubStagePatch() throws Throwable {
      return taskStateHelper.getValidPatchState(new Object[][] {
          {TaskState.TaskStage.STARTED,
              DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage.CHECK_VM_EXISTENCE,
              TaskState.TaskStage.FINISHED,
              null},
      });
    }

    /**
     * Verifies that when an invalid stage/sub-stage patch state is given, the workflow will validate
     * the state and fail.
     */
    @Test(expectedExceptions = XenonRuntimeException.class, dataProvider = "InvalidStageAndSubStagePatch")
    public void failsWithInvalidStageAndSubStagePatch(
        TaskState.TaskStage currentStage,
        DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage currentSubStage,
        TaskState.TaskStage patchStage,
        DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage patchSubStage)
        throws Throwable {

      DeleteVirtualNetworkWorkflowDocument finalState =
          testEnvironment.callServiceAndWaitForState(
              DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
              startState,
              DeleteVirtualNetworkWorkflowDocument.class,
              (state) -> TaskState.TaskStage.STARTED == state.taskState.stage &&
                  DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage.CHECK_VM_EXISTENCE
                      == state.taskState.subStage);


      if (!(currentStage == TaskState.TaskStage.STARTED &&
          currentSubStage == DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage.CHECK_VM_EXISTENCE)) {
        testEnvironment.sendPatchAndWait(finalState.documentSelfLink,
            buildPatch(currentStage, currentSubStage));
      }

      testEnvironment.sendPatchAndWait(finalState.documentSelfLink,
          buildPatch(patchStage, patchSubStage))
          .getBody(DeleteVirtualNetworkWorkflowDocument.class);
    }

    @DataProvider(name = "InvalidStageAndSubStagePatch")
    public Object[][] getInvalidStageAndSubStagePatch()
        throws Throwable {
      return taskStateHelper.getInvalidPatchState();
    }

    /**
     * Verifies that when a immutable field is set to non-null value in the patch state, the workflow will
     * validate the state and fail.
     */
    @Test(expectedExceptions = XenonRuntimeException.class,
        expectedExceptionsMessageRegExp = "^.* is immutable",
        dataProvider = "ImmutableFields")
    public void failsWithNonNullImmutableFieldPatch(String fieldName) throws Throwable {
      DeleteVirtualNetworkWorkflowDocument finalState =
          testEnvironment.callServiceAndWaitForState(
              DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
              startState,
              DeleteVirtualNetworkWorkflowDocument.class,
              (state) -> TaskState.TaskStage.STARTED == state.taskState.stage &&
                  DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage.CHECK_VM_EXISTENCE
                      == state.taskState.subStage);

      DeleteVirtualNetworkWorkflowDocument patchState = buildPatch(TaskState.TaskStage.STARTED,
          DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage.DELETE_LOGICAL_PORTS);
      Field declaredField = patchState.getClass().getDeclaredField(fieldName);
      declaredField.set(patchState, ReflectionUtils.getDefaultAttributeValue(declaredField));

      testEnvironment.sendPatchAndWait(finalState.documentSelfLink, patchState)
          .getBody(DeleteVirtualNetworkWorkflowDocument.class);

    }

    @DataProvider(name = "ImmutableFields")
    public Object[][] getImmutableFields() {
      return TestHelper.toDataProvidersList(
          ReflectionUtils.getAttributeNamesWithAnnotation(
              DeleteVirtualNetworkWorkflowDocument.class, Immutable.class));
    }

    @Test(expectedExceptions = XenonRuntimeException.class,
        expectedExceptionsMessageRegExp = "^.* cannot be set or changed in a patch",
        dataProvider = "writeOnceFields")
    public void failsWithChangeWriteOnceFields(String fieldName) throws Throwable {
      DeleteVirtualNetworkWorkflowDocument finalState =
          testEnvironment.callServiceAndWaitForState(
              DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
              startState,
              DeleteVirtualNetworkWorkflowDocument.class,
              (state) -> TaskState.TaskStage.STARTED == state.taskState.stage &&
                  DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage.CHECK_VM_EXISTENCE
                      == state.taskState.subStage);

      DeleteVirtualNetworkWorkflowDocument patchState = buildPatch(TaskState.TaskStage.STARTED,
          DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage.GET_NSX_CONFIGURATION);
      Field declaredField = patchState.getClass().getDeclaredField(fieldName);
      declaredField.set(patchState, "value1");

      testEnvironment.sendPatchAndWait(finalState.documentSelfLink, patchState)
          .getBody(DeleteVirtualNetworkWorkflowDocument.class);

      patchState = buildPatch(TaskState.TaskStage.STARTED,
          DeleteVirtualNetworkWorkflowDocument.TaskState.SubStage.DELETE_LOGICAL_PORTS);
      declaredField = patchState.getClass().getDeclaredField(fieldName);
      declaredField.set(patchState, "value2");

      testEnvironment.sendPatchAndWait(finalState.documentSelfLink, patchState)
          .getBody(DeleteVirtualNetworkWorkflowDocument.class);
    }

    @DataProvider(name = "writeOnceFields")
    public Object[][] getWriteOnceFields() {
      return TestHelper.toDataProvidersList(
          ReflectionUtils.getAttributeNamesWithAnnotation(
              DeleteVirtualNetworkWorkflowDocument.class, WriteOnce.class));
    }
  }

  /**
   * Tests end-to-end scenarios of the {@link com.vmware.photon.controller.apibackend.workflows
   * .DeleteVirtualNetworkWorkflowService}.
   */
  public class EndToEndTest {

    private static final String NETWORK_MANAGER_ADDRESS = "networkManagerAddress";
    private static final String NETWORK_MANAGER_USERNAME = "networkManagerUsername";
    private static final String NETWORK_MANAGER_PASSWORD = "networkManagerPassword";
    private static final String NETWORK_ZONE_ID = "networkZoneId";
    private static final String NETWORK_TOP_ROUTER_ID = "networkTopRouterId";

    private DeleteVirtualNetworkWorkflowDocument startState;
    private NsxClientFactory nsxClientFactory;
    private NsxClientMock nsxClientMock;
    private TestEnvironment testEnvironment;
    private Map<Class<? extends Service>, Supplier<FactoryService>> testFactoryServiceMap;

    @BeforeMethod
    public void setUpTest() throws Throwable {
      nsxClientFactory = mock(NsxClientFactory.class);

      testFactoryServiceMap = new HashMap<>();
      testFactoryServiceMap.put(MockSubnetConfigurationService.class, MockSubnetConfigurationService::createFactory);

      testEnvironment = new TestEnvironment.Builder()
          .hostCount(1)
          .testFactoryServiceMap(testFactoryServiceMap)
          .cloudStoreHelper(new CloudStoreHelper())
          .nsxClientFactory(nsxClientFactory)
          .build();
    }

    @AfterMethod
    public void tearDownTest() throws Throwable {
      if (null != testEnvironment) {
        testEnvironment.stop();
        testEnvironment = null;
      }

      startState = null;
    }

    /**
     * Verifies that the workflow succeeds to delete a virtual network.
     */
    @Test
    public void succeedsToDeleteVirtualNetwork() throws Throwable {
      nsxClientMock = new NsxClientMock.Builder()
          .listLogicalRouterPorts(true)
          .deleteLogicalRouterPort(true)
          .deleteLogicalPort(true)
          .deleteLogicalRouter(true)
          .deleteLogicalSwitch(true)
          .checkLogicalRouterPortExistence(true)
          .checkLogicalSwitchPortExistence(true)
          .checkLogicalRouterExistence(true)
          .checkLogicalSwitchExistence(true)
          .build();
      doReturn(nsxClientMock).when(nsxClientFactory).create(any(String.class), any(String.class), any(String.class));

      VirtualNetworkService.State virtualNetworkDocument = createVirtualNetworkDocumentInCloudStore(testEnvironment);
      String subnetId = ServiceUtils.getIDFromDocumentSelfLink(virtualNetworkDocument.documentSelfLink);

      startState = buildValidStartState(
          TaskState.TaskStage.CREATED,
          null,
          new ControlFlags.Builder().build(),
          subnetId);

      startDeploymentService(testEnvironment);
      startProjectService(testEnvironment);
      startResourceTicketService(testEnvironment);
      SubnetAllocatorService.State subnetAllocatorServiceState = startSubnetAllocatorService(testEnvironment);
      startDhcpSubnetService(testEnvironment, subnetId, false);
      startDhcpSubnetService(testEnvironment, DhcpSubnetService.FLOATING_IP_SUBNET_SINGLETON_LINK, true);
      startIpLeaseService(testEnvironment, SNAT_IP_ADDRESS, ServiceUtils.getIDFromDocumentSelfLink(DhcpSubnetService
          .FLOATING_IP_SUBNET_SINGLETON_LINK));

      SubnetAllocatorService.AllocateSubnet allocateSubnetPatch =
          new SubnetAllocatorService.AllocateSubnet(
              subnetId, 16L, 4L);

      Operation completedOperation = testEnvironment.sendPatchAndWait(subnetAllocatorServiceState.documentSelfLink,
          allocateSubnetPatch);

      assertThat(completedOperation.getStatusCode(), CoreMatchers.is(Operation.STATUS_CODE_OK));

      DeleteVirtualNetworkWorkflowDocument finalState =
          testEnvironment.callServiceAndWaitForState(
              DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
              startState,
              DeleteVirtualNetworkWorkflowDocument.class,
              (state) -> TaskState.TaskStage.FINISHED == state.taskState.stage);

      // Verifies the DeleteVirtualNetworkWorkflowDocument.
      assertThat(finalState.nsxAddress, is(NETWORK_MANAGER_ADDRESS));
      assertThat(finalState.nsxUsername, is(NETWORK_MANAGER_USERNAME));
      assertThat(finalState.nsxPassword, is(NETWORK_MANAGER_PASSWORD));
      assertThat(finalState.taskServiceEntity, notNullValue());
      assertThat(finalState.taskServiceEntity.documentSelfLink, notNullValue());
      assertThat(finalState.taskServiceEntity.state, is(SubnetState.DELETED));
      assertThat(finalState.taskServiceState, notNullValue());
      assertThat(finalState.taskServiceState.state, is(TaskService.State.TaskState.COMPLETED));

      // Verifies that the virtual network entity is DELETED from cloud-store.
      try {
        testEnvironment.getServiceState(
            finalState.taskServiceEntity.documentSelfLink, VirtualNetworkService.State.class);
        fail("should have failed to find deleted VirtualNetworkService document");
      } catch (DocumentNotFoundException ex) {
      }

      // Verifies that the DhcpSubnetService associated with the virtual network entity is DELETED from cloud-store.
      try {
        testEnvironment.getServiceState(
            DhcpSubnetService.FACTORY_LINK + "/" + subnetId, DhcpSubnetService.State.class);
        fail("should have failed to find deleted DhcpSubnetService document");
      } catch (DocumentNotFoundException ex) {
      }

      // Verify tombstone task was created
      assertThat(getTombstoneTaskCount(finalState.networkId), CoreMatchers.is(1));

      // Verify resource ticket was updated
      ResourceTicketService.State finalResourceTicket = testEnvironment.getServiceState(ResourceTicketServiceFactory
              .SELF_LINK + "/resource-ticket-id",
          ResourceTicketService.State.class);
      assertThat(finalResourceTicket.usageMap.get(CreateVirtualNetworkWorkflowService.SDN_RESOURCE_TICKET_KEY),
          notNullValue());
      assertThat(finalResourceTicket.usageMap
          .get(CreateVirtualNetworkWorkflowService.SDN_RESOURCE_TICKET_KEY).getValue(), is(0.0));
    }

    /**
     * Putting network in PENDING_DELETE state because there are still VMs
     * using this virtual network.
     */
    @Test
    public void pendingDeleteVirtualNetwork() throws Throwable {
      VirtualNetworkService.State virtualNetworkDocument = createVirtualNetworkDocumentInCloudStore(testEnvironment);
      String virtualNetworkId = ServiceUtils.getIDFromDocumentSelfLink(virtualNetworkDocument.documentSelfLink);

      VmService.State vm = new VmService.State();
      vm.name = "vm_name";
      vm.flavorId = "flavor_id";
      vm.imageId = "image_id";
      vm.networks = new ArrayList<>();
      vm.networks.add(virtualNetworkId);
      vm.projectId = "project_id";
      vm.vmState = VmState.STARTED;

      Operation result = testEnvironment.sendPostAndWait(VmServiceFactory.SELF_LINK, vm);
      assertThat(result.getStatusCode(), is(Operation.STATUS_CODE_OK));

      startState = buildValidStartState(
          TaskState.TaskStage.CREATED,
          null,
          new ControlFlags.Builder().build(),
          virtualNetworkId);

      startDeploymentService(testEnvironment);

      DeleteVirtualNetworkWorkflowDocument finalState =
          testEnvironment.callServiceAndWaitForState(
              DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
              startState,
              DeleteVirtualNetworkWorkflowDocument.class,
              (state) -> TaskState.TaskStage.FINISHED == state.taskState.stage);

      // Verifies the DeleteVirtualNetworkWorkflowDocument.
      assertThat(finalState.nsxAddress, nullValue());
      assertThat(finalState.nsxUsername, nullValue());
      assertThat(finalState.nsxPassword, nullValue());
      assertThat(finalState.taskServiceEntity, notNullValue());
      assertThat(finalState.taskServiceEntity.documentSelfLink, notNullValue());
      assertThat(finalState.taskServiceEntity.state, is(SubnetState.PENDING_DELETE));
      assertThat(finalState.taskServiceState, notNullValue());
      assertThat(finalState.taskServiceState.state, is(TaskService.State.TaskState.COMPLETED));

      // Verifies that the virtual network entity is NOT deleted from cloud-store.
      testEnvironment.getServiceState(
          finalState.taskServiceEntity.documentSelfLink, VirtualNetworkService.State.class);

      // Verify the no tombstone task was created
      assertThat(getTombstoneTaskCount(finalState.networkId), is(0));
    }

    /**
     * Verifies when GET_NSX_CONFIGURATION sub-stage fails, the workflow will progress to FAILED state,
     * and no NSX configuration is cached in the service document. We simulate the failure by not creating
     * a deployment service entity in cloud-store.
     */
    @Test
    public void failsToGetNsxConfiguration() throws Throwable {
      VirtualNetworkService.State virtualNetworkDocument = createVirtualNetworkDocumentInCloudStore(testEnvironment);
      startState = buildValidStartState(
          TaskState.TaskStage.CREATED,
          null,
          new ControlFlags.Builder().build(),
          ServiceUtils.getIDFromDocumentSelfLink(virtualNetworkDocument.documentSelfLink));

      DeleteVirtualNetworkWorkflowDocument finalState =
          testEnvironment.callServiceAndWaitForState(
              DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
              startState,
              DeleteVirtualNetworkWorkflowDocument.class,
              (state) -> TaskState.TaskStage.FAILED == state.taskState.stage);

      // Verifies that NSX configuration is empty in the service document.
      assertThat(finalState.nsxAddress, nullValue());
      assertThat(finalState.nsxUsername, nullValue());
      assertThat(finalState.nsxPassword, nullValue());
    }

    /**
     * Verifies when DELETE_LOGICAL_PORTS sub-stage fails, the workflow will progress to FAILED state.
     */
    @Test
    public void failsToDeleteLogicalPorts() throws Throwable {
      nsxClientMock = new NsxClientMock.Builder()
          .listLogicalRouterPorts(true)
          .deleteLogicalRouterPort(false)
          .build();
      doReturn(nsxClientMock).when(nsxClientFactory).create(any(String.class), any(String.class), any(String.class));

      VirtualNetworkService.State virtualNetworkDocument = createVirtualNetworkDocumentInCloudStore(testEnvironment);
      startState = buildValidStartState(
          TaskState.TaskStage.CREATED,
          null,
          new ControlFlags.Builder().build(),
          ServiceUtils.getIDFromDocumentSelfLink(virtualNetworkDocument.documentSelfLink));

      startDeploymentService(testEnvironment);

      DeleteVirtualNetworkWorkflowDocument finalState =
          testEnvironment.callServiceAndWaitForState(
              DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
              startState,
              DeleteVirtualNetworkWorkflowDocument.class,
              (state) -> TaskState.TaskStage.FAILED == state.taskState.stage);

      // Verifies the cached task entity document.
      assertThat(finalState.taskServiceState.state, is(TaskService.State.TaskState.ERROR));

      // Verifies that the task entity document is set to ERROR in cloud-store.
      TaskService.State taskServiceState = testEnvironment.getServiceState(finalState.taskServiceState.documentSelfLink,
          TaskService.State.class);
      assertThat(taskServiceState, notNullValue());
      assertThat(taskServiceState.state, is(TaskService.State.TaskState.ERROR));
    }

    /**
     * Verifies that when DELETE_LOGICAL_ROUTER:DELETE_ROUTER sub-stage fails, the workflow will progress to FAILED
     * state.
     */
    @Test
    public void failsToDeleteLogicalRouter() throws Throwable {
      nsxClientMock = new NsxClientMock.Builder()
          .listLogicalRouterPorts(true)
          .deleteLogicalRouterPort(true)
          .deleteLogicalPort(true)
          .checkLogicalRouterPortExistence(true)
          .checkLogicalSwitchPortExistence(true)
          .deleteLogicalRouter(false)
          .build();
      doReturn(nsxClientMock).when(nsxClientFactory).create(any(String.class), any(String.class), any(String.class));

      VirtualNetworkService.State virtualNetworkDocument = createVirtualNetworkDocumentInCloudStore(testEnvironment);
      startState = buildValidStartState(
          TaskState.TaskStage.CREATED,
          null,
          new ControlFlags.Builder().build(),
          ServiceUtils.getIDFromDocumentSelfLink(virtualNetworkDocument.documentSelfLink));

      startDeploymentService(testEnvironment);

      DeleteVirtualNetworkWorkflowDocument finalState =
          testEnvironment.callServiceAndWaitForState(
              DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
              startState,
              DeleteVirtualNetworkWorkflowDocument.class,
              (state) -> TaskState.TaskStage.FAILED == state.taskState.stage);

      // Verifies the cached task entity document.
      assertThat(finalState.taskServiceState.state, is(TaskService.State.TaskState.ERROR));

      // Verifies that the task entity document is set to ERROR in cloud-store.
      TaskService.State taskServiceState = testEnvironment.getServiceState(finalState.taskServiceState.documentSelfLink,
          TaskService.State.class);
      assertThat(taskServiceState, notNullValue());
      assertThat(taskServiceState.state, is(TaskService.State.TaskState.ERROR));
    }

    /**
     * Verifies that when DELETE_LOGICAL_ROUTER:WAIT_DELETE_ROUTER sub-stage fails,
     * the workflow will progress to FAILED state.
     */
    @Test
    public void failsToWaitForDeleteLogicalRouter() throws Throwable {
      nsxClientMock = new NsxClientMock.Builder()
          .listLogicalRouterPorts(true)
          .deleteLogicalRouterPort(true)
          .deleteLogicalPort(true)
          .checkLogicalRouterPortExistence(true)
          .checkLogicalSwitchPortExistence(true)
          .deleteLogicalRouter(true)
          .checkLogicalRouterExistence(false)
          .build();
      doReturn(nsxClientMock).when(nsxClientFactory).create(any(String.class), any(String.class), any(String.class));

      VirtualNetworkService.State virtualNetworkDocument = createVirtualNetworkDocumentInCloudStore(testEnvironment);
      startState = buildValidStartState(
          TaskState.TaskStage.CREATED,
          null,
          new ControlFlags.Builder().build(),
          ServiceUtils.getIDFromDocumentSelfLink(virtualNetworkDocument.documentSelfLink));

      startDeploymentService(testEnvironment);

      DeleteVirtualNetworkWorkflowDocument finalState =
          testEnvironment.callServiceAndWaitForState(
              DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
              startState,
              DeleteVirtualNetworkWorkflowDocument.class,
              (state) -> TaskState.TaskStage.FAILED == state.taskState.stage);

      // Verifies the cached task entity document.
      assertThat(finalState.taskServiceState.state, is(TaskService.State.TaskState.ERROR));

      // Verifies that the task entity document is set to ERROR in cloud-store.
      TaskService.State taskServiceState = testEnvironment.getServiceState(finalState.taskServiceState.documentSelfLink,
          TaskService.State.class);
      assertThat(taskServiceState, notNullValue());
      assertThat(taskServiceState.state, is(TaskService.State.TaskState.ERROR));
    }

    /**
     * Verifies that when DELETE_LOGICAL_SWITCH:DELETE_SWITCH sub-stage fails,
     * the workflow will progress to FAILED state.
     */
    @Test
    public void failsToDeleteLogicalSwitch() throws Throwable {
      nsxClientMock = new NsxClientMock.Builder()
          .listLogicalRouterPorts(true)
          .deleteLogicalRouterPort(true)
          .deleteLogicalPort(true)
          .deleteLogicalRouter(true)
          .deleteLogicalSwitch(false)
          .checkLogicalRouterPortExistence(true)
          .checkLogicalSwitchPortExistence(true)
          .checkLogicalRouterExistence(true)
          .build();
      doReturn(nsxClientMock).when(nsxClientFactory).create(any(String.class), any(String.class), any(String.class));

      VirtualNetworkService.State virtualNetworkDocument = createVirtualNetworkDocumentInCloudStore(testEnvironment);
      startState = buildValidStartState(
          TaskState.TaskStage.CREATED,
          null,
          new ControlFlags.Builder().build(),
          ServiceUtils.getIDFromDocumentSelfLink(virtualNetworkDocument.documentSelfLink));

      startDeploymentService(testEnvironment);

      DeleteVirtualNetworkWorkflowDocument finalState =
          testEnvironment.callServiceAndWaitForState(
              DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
              startState,
              DeleteVirtualNetworkWorkflowDocument.class,
              (state) -> TaskState.TaskStage.FAILED == state.taskState.stage);

      // Verifies the cached task entity document.
      assertThat(finalState.taskServiceState.state, is(TaskService.State.TaskState.ERROR));

      // Verifies that the task entity document is set to ERROR in cloud-store.
      TaskService.State taskServiceState = testEnvironment.getServiceState(finalState.taskServiceState.documentSelfLink,
          TaskService.State.class);
      assertThat(taskServiceState, notNullValue());
      assertThat(taskServiceState.state, is(TaskService.State.TaskState.ERROR));
    }

    /**
     * Verifies that when DELETE_LOGICAL_SWITCH:WAIT_DELETE_SWITCH sub-stage fails,
     * the workflow will progress to FAILED state.
     */
    @Test
    public void failsToWaitForDeleteLogicalSwitch() throws Throwable {
      nsxClientMock = new NsxClientMock.Builder()
          .listLogicalRouterPorts(true)
          .deleteLogicalRouterPort(true)
          .deleteLogicalPort(true)
          .deleteLogicalRouter(true)
          .deleteLogicalSwitch(true)
          .checkLogicalRouterPortExistence(true)
          .checkLogicalSwitchPortExistence(true)
          .checkLogicalRouterExistence(true)
          .checkLogicalSwitchExistence(false)
          .build();
      doReturn(nsxClientMock).when(nsxClientFactory).create(any(String.class), any(String.class), any(String.class));

      VirtualNetworkService.State virtualNetworkDocument = createVirtualNetworkDocumentInCloudStore(testEnvironment);
      startState = buildValidStartState(
          TaskState.TaskStage.CREATED,
          null,
          new ControlFlags.Builder().build(),
          ServiceUtils.getIDFromDocumentSelfLink(virtualNetworkDocument.documentSelfLink));

      startDeploymentService(testEnvironment);

      DeleteVirtualNetworkWorkflowDocument finalState =
          testEnvironment.callServiceAndWaitForState(
              DeleteVirtualNetworkWorkflowService.FACTORY_LINK,
              startState,
              DeleteVirtualNetworkWorkflowDocument.class,
              (state) -> TaskState.TaskStage.FAILED == state.taskState.stage);

      // Verifies the cached task entity document.
      assertThat(finalState.taskServiceState.state, is(TaskService.State.TaskState.ERROR));

      // Verifies that the task entity document is set to ERROR in cloud-store.
      TaskService.State taskServiceState = testEnvironment.getServiceState(finalState.taskServiceState.documentSelfLink,
          TaskService.State.class);
      assertThat(taskServiceState, notNullValue());
      assertThat(taskServiceState.state, is(TaskService.State.TaskState.ERROR));
    }

    private DeploymentService.State startDeploymentService(TestEnvironment testEnvironment) throws Throwable {
      DeploymentService.State deploymentStartState =
          ReflectionUtils.buildValidStartState(DeploymentService.State.class);
      deploymentStartState.sdnEnabled = true;
      deploymentStartState.networkManagerAddress = NETWORK_MANAGER_ADDRESS;
      deploymentStartState.networkManagerUsername = NETWORK_MANAGER_USERNAME;
      deploymentStartState.networkManagerPassword = NETWORK_MANAGER_PASSWORD;
      deploymentStartState.networkZoneId = NETWORK_ZONE_ID;
      deploymentStartState.networkTopRouterId = NETWORK_TOP_ROUTER_ID;

      URI hostUri = testEnvironment.getHosts()[0].getUri();
      deploymentStartState.dhcpServers = new ArrayList<>();
      deploymentStartState.dhcpServers.add(hostUri.getHost());

      return testEnvironment.callServiceAndWaitForState(
          DeploymentServiceFactory.SELF_LINK,
          deploymentStartState,
          DeploymentService.State.class,
          (state) -> true);
    }

    private ProjectService.State startProjectService(TestEnvironment testEnvironment) throws Throwable {
      ProjectService.State projectState = new ProjectService.State();
      projectState.resourceTicketId = "resource-ticket-id";
      projectState.name = "project-name";
      projectState.tenantId = "tenant-id";
      projectState.documentSelfLink = "project-id";

      return testEnvironment.callServiceAndWaitForState(
          ProjectServiceFactory.SELF_LINK,
          projectState,
          ProjectService.State.class,
          (state) -> true);
    }

    private SubnetAllocatorService.State startSubnetAllocatorService(TestEnvironment testEnvironment) throws Throwable {
      SubnetAllocatorService.State subnetAllocatorServiceState = new SubnetAllocatorService.State();
      subnetAllocatorServiceState.rootCidr = "192.168.1.1/24";
      subnetAllocatorServiceState.documentSelfLink = SubnetAllocatorService.SINGLETON_LINK;
      URI hostUri = testEnvironment.getHosts()[0].getUri();
      subnetAllocatorServiceState.dhcpAgentEndpoint =
          hostUri.getScheme() + "://" + hostUri.getHost() + ":" + hostUri.getPort();

      return testEnvironment.callServiceAndWaitForState(
          SubnetAllocatorService.FACTORY_LINK,
          subnetAllocatorServiceState,
          SubnetAllocatorService.State.class,
          (state) -> true);
    }

    private DhcpSubnetService.State startDhcpSubnetService(TestEnvironment testEnvironment,
                                                           String subnetId,
                                                           boolean isFloatingDhcpSubnet) throws Throwable {
      DhcpSubnetService.State dhcpSubnetServiceState = new DhcpSubnetService.State();
      dhcpSubnetServiceState.cidr = "0.0.0.1/26";
      dhcpSubnetServiceState.lowIp = 1L;
      dhcpSubnetServiceState.highIp = 100L;
      dhcpSubnetServiceState.lowIpDynamic = 1L;
      dhcpSubnetServiceState.highIpDynamic = 100L;
      dhcpSubnetServiceState.lowIpStatic = 1L;
      dhcpSubnetServiceState.highIpDynamic = 100L;
      dhcpSubnetServiceState.size = 128L;
      URI hostUri = testEnvironment.getHosts()[0].getUri();
      dhcpSubnetServiceState.dhcpAgentEndpoint =
          hostUri.getScheme() + "://" + hostUri.getHost() + ":" + hostUri.getPort();
      dhcpSubnetServiceState.documentSelfLink = subnetId;
      dhcpSubnetServiceState.isFloatingIpSubnet = isFloatingDhcpSubnet;
      dhcpSubnetServiceState.subnetId = subnetId;

      return testEnvironment.callServiceAndWaitForState(
          DhcpSubnetService.FACTORY_LINK,
          dhcpSubnetServiceState,
          DhcpSubnetService.State.class,
          (state) -> true);
    }

    private IpLeaseService.State startIpLeaseService(TestEnvironment testEnvironment,
                                                     String ip,
                                                     String subnetId) throws Throwable {
      IpLeaseService.State startState = new IpLeaseService.State();
      startState.ip = ip;
      startState.subnetId = subnetId;
      startState.documentSelfLink = ip.replace(".", ":");

      return testEnvironment.callServiceAndWaitForState(
          IpLeaseService.FACTORY_LINK,
          startState,
          IpLeaseService.State.class,
          (state) -> true);
    }

    private ResourceTicketService.State startResourceTicketService(TestEnvironment testEnvironment) throws Throwable {
      ResourceTicketService.State resourceTicketState = new ResourceTicketService.State();
      resourceTicketState.name = "resource-ticket-name";
      resourceTicketState.tenantId = "tenant-id";
      resourceTicketState.parentId = "parent-id";
      resourceTicketState.documentSelfLink = "resource-ticket-id";
      resourceTicketState.limitMap = new HashMap<>();
      QuotaLineItem costItem = new QuotaLineItem();
      costItem.setKey(CreateVirtualNetworkWorkflowService.SDN_RESOURCE_TICKET_KEY);
      costItem.setValue(20);
      costItem.setUnit(QuotaUnit.COUNT);
      resourceTicketState.limitMap.put(costItem.getKey(), costItem);
      resourceTicketState.usageMap = new HashMap<>();
      costItem = new QuotaLineItem();
      costItem.setKey(CreateVirtualNetworkWorkflowService.SDN_RESOURCE_TICKET_KEY);
      costItem.setValue(16);
      costItem.setUnit(QuotaUnit.COUNT);
      resourceTicketState.usageMap.put(costItem.getKey(), costItem);

      return testEnvironment.callServiceAndWaitForState(
          ResourceTicketServiceFactory.SELF_LINK,
          resourceTicketState,
          ResourceTicketService.State.class,
          (state) -> true);
    }

    private int getTombstoneTaskCount(String entityId) throws Throwable {
      ImmutableMap.Builder<String, String> termsBuilder = new ImmutableMap.Builder<>();
      termsBuilder.put("entityId", entityId);

      QueryTask.QuerySpecification querySpecification = QueryTaskUtils.buildQuerySpec(TombstoneService.State.class,
          termsBuilder.build());
      QueryTask queryTask = QueryTask.create(querySpecification);
      queryTask.setDirect(true);

      NodeGroupBroadcastResponse response = testEnvironment.sendBroadcastQueryAndWait(queryTask);
      Set<String> documentLinks = QueryTaskUtils.getBroadcastQueryDocumentLinks(response);

      return documentLinks.size();
    }

    @DataProvider(name = "hostCount")
    public Object[][] getHostCount() {
      return new Object[][]{
          {1},
          {TestEnvironment.DEFAULT_MULTI_HOST_COUNT}
      };
    }
  }
}
