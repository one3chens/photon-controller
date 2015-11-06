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

package com.vmware.photon.controller.api;

import org.apache.commons.lang3.StringUtils;
import org.testng.annotations.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Tests {@link VmNetworks}.
 */
public class VmNetworksTest {

  @Test
  public void testNetworkConnections() throws Exception {
    VmNetworks vmNetworks = new VmNetworks();
    assertThat(getNetworkConnectionsInNetwork(vmNetworks, "public").isEmpty(), is(true));
    assertThat(getNetworkConnectionsInNetwork(vmNetworks, "private").isEmpty(), is(true));

    NetworkConnection networkConnection1 = new NetworkConnection("00:50:56:02:00:2f");
    networkConnection1.setNetwork("public");
    assertThat(vmNetworks.addNetworkConnection(networkConnection1), is(true));

    NetworkConnection networkConnection2 = new NetworkConnection("00:50:56:02:00:4f");
    networkConnection2.setNetwork("public");
    assertThat(vmNetworks.addNetworkConnection(networkConnection2), is(true));
    assertThat(getNetworkConnectionsInNetwork(vmNetworks, "public").size(), is(2));

    NetworkConnection networkConnection3 = new NetworkConnection("00:50:56:02:00:4f");
    networkConnection3.setNetwork("public");
    assertThat(vmNetworks.addNetworkConnection(networkConnection3), is(false));

    NetworkConnection networkConnection4 = new NetworkConnection("00:50:56:02:00:3f");
    networkConnection4.setNetwork("private");
    assertThat(vmNetworks.addNetworkConnection(networkConnection4), is(true));
    assertThat(getNetworkConnectionsInNetwork(vmNetworks, "private").size(), is(1));

    for (NetworkConnection networkConnection :
        new ArrayList<>(getNetworkConnectionsInNetwork(vmNetworks, "public"))) {
      assertThat(vmNetworks.removeNetworkConnection(networkConnection), is(true));
      assertThat(vmNetworks.removeNetworkConnection(networkConnection), is(false));
    }

    assertThat(getNetworkConnectionsInNetwork(vmNetworks, "public").size(), is(0));
    assertThat(getNetworkConnectionsInNetwork(vmNetworks, "private").size(), is(1));
  }

  private Set<NetworkConnection> getNetworkConnectionsInNetwork(VmNetworks vmNetworks, String networkName) {
    Set<NetworkConnection> result = new HashSet<>();
    for (NetworkConnection networkConnection : vmNetworks.getNetworkConnections()) {
      if (StringUtils.isNotBlank(networkName) &&
          networkName.equals(networkConnection.getNetwork())) {
        result.add(networkConnection);
      }
    }
    return result;
  }
}
