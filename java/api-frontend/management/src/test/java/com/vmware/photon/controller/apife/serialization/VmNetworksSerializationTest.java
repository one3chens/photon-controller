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

package com.vmware.photon.controller.apife.serialization;

import com.vmware.photon.controller.api.NetworkConnection;
import com.vmware.photon.controller.api.VmNetworks;

import static com.vmware.photon.controller.apife.helpers.JsonHelpers.asJson;
import static com.vmware.photon.controller.apife.helpers.JsonHelpers.fromJson;
import static com.vmware.photon.controller.apife.helpers.JsonHelpers.jsonFixture;

import org.testng.annotations.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static uk.co.datumedge.hamcrest.json.SameJSONAs.sameJSONAs;

/**
 * Tests {@link VmNetworks}.
 */
public class VmNetworksSerializationTest {

  private static final String JSON_FILE = "fixtures/vm-networks.json";

  @Test
  public void testSerialization() throws Exception {
    VmNetworks vmNetworks = new VmNetworks();
    NetworkConnection networkConnection1 = new NetworkConnection("00:50:56:02:00:30");
    networkConnection1.setNetwork("public");
    networkConnection1.setIpAddress("10.146.30.120");
    networkConnection1.setIsConnected(NetworkConnection.Connected.False);
    networkConnection1.setNetmask("255.255.255.128");
    vmNetworks.addNetworkConnection(networkConnection1);

    NetworkConnection networkConnection2 = new NetworkConnection("00:50:56:02:00:32");
    networkConnection2.setNetwork("private");
    networkConnection2.setIpAddress("10.146.30.122");
    networkConnection2.setIsConnected(NetworkConnection.Connected.True);
    networkConnection2.setNetmask("255.255.255.128");
    vmNetworks.addNetworkConnection(networkConnection2);

    String json = jsonFixture(JSON_FILE);

    assertThat(fromJson(json, VmNetworks.class), is(vmNetworks));
    assertThat(asJson(vmNetworks), sameJSONAs(json).allowingAnyArrayOrdering());
  }

}
