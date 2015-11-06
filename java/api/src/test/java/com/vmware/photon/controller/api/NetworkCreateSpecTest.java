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

import com.vmware.photon.controller.api.helpers.Validator;
import static com.vmware.photon.controller.api.helpers.JsonHelpers.asJson;
import static com.vmware.photon.controller.api.helpers.JsonHelpers.fromJson;
import static com.vmware.photon.controller.api.helpers.JsonHelpers.jsonFixture;

import com.google.common.collect.ImmutableList;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests {@link NetworkCreateSpec}.
 */
public class NetworkCreateSpecTest {

  private Validator validator = new Validator();

  private NetworkCreateSpec networkCreateSpec;

  /**
   * Dummy test case to make Intellij recognize this as a test class.
   */
  @Test
  private void dummy() {
  }

  private NetworkCreateSpec createValidNetworkCreateSpec() {
    NetworkCreateSpec networkCreateSpec = new NetworkCreateSpec();
    networkCreateSpec.setName("network1");
    networkCreateSpec.setDescription("VM Network");
    networkCreateSpec.setPortGroups(ImmutableList.of("PG1", "PG2"));
    return networkCreateSpec;
  }

  /**
   * Tests for {@link NetworkCreateSpec#portGroups}.
   */
  public class PortGroupsTest {

    @BeforeMethod
    public void setUp() {
      networkCreateSpec = createValidNetworkCreateSpec();
    }

    @Test(dataProvider = "invalidPortGroups")
    public void testInvalidGateway(List<String> portGroups, String violation) {
      networkCreateSpec.setPortGroups(portGroups);
      ImmutableList<String> violations = validator.validate(networkCreateSpec);

      assertThat(violations.size(), is(1));
      assertThat(violations.get(0), is(violation));
    }

    @DataProvider(name = "invalidPortGroups")
    public Object[][] getInvalidPortGroups() {
      return new Object[][]{
          {null, "portGroups may not be null (was null)"},
          {new ArrayList<>(), "portGroups size must be between 1 and 2147483647 (was [])"},
      };
    }

    @Test
    public void testValidPortGroups() {
      networkCreateSpec.setPortGroups(ImmutableList.of("PG1", "PG2"));
      ImmutableList<String> violations = validator.validate(networkCreateSpec);

      assertTrue(violations.isEmpty());
    }
  }

  /**
   * Tests JSON serialization.
   */
  public class SerializationTest {

    private static final String JSON_FILE = "fixtures/network-create-spec.json";

    @BeforeMethod
    public void setUp() {
      networkCreateSpec = createValidNetworkCreateSpec();
    }

    @Test
    public void testSerialization() throws Exception {
      String json = jsonFixture(JSON_FILE);

      assertThat(asJson(networkCreateSpec), is(equalTo(json)));
      assertThat(fromJson(json, NetworkCreateSpec.class), is(networkCreateSpec));
    }
  }
}
