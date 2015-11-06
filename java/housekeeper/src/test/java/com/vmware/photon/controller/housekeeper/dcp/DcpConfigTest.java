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

import com.vmware.photon.controller.common.config.BadConfigException;
import com.vmware.photon.controller.common.config.ConfigBuilder;
import com.vmware.photon.controller.housekeeper.Config;

import org.testng.annotations.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.testng.Assert.fail;

/**
 * Tests {@link DcpConfig}.
 */
public class DcpConfigTest {

  private DcpConfig dcpConfig;

  @Test
  public void testBatchSize() throws BadConfigException {
    dcpConfig = ConfigBuilder.build(Config.class,
        DcpConfigTest.class.getResource("/config.yml").getPath()).getDcp();
    assertThat(dcpConfig.getImageCopyBatchSize(), is(20));
  }

  @Test
  public void testDefaultBatchSize() throws BadConfigException {
    dcpConfig = ConfigBuilder.build(Config.class,
        DcpConfigTest.class.getResource("/config_min.yml").getPath()).getDcp();
    assertThat(dcpConfig.getImageCopyBatchSize(), is(5));
  }

  @Test
  public void testDcpStoragePath() throws BadConfigException {
    dcpConfig = ConfigBuilder.build(Config.class,
        DcpConfigTest.class.getResource("/config.yml").getPath()).getDcp();
    assertThat(dcpConfig.getStoragePath(), is("/tmp/dcp/housekeeper/"));
  }

  @Test
  public void testInvalidBatchSize() {
    try {
      dcpConfig = ConfigBuilder.build(DcpConfig.class,
          DcpConfigTest.class.getResource("/dcpConfig_invalid.yml").getPath());
      fail();
    } catch (BadConfigException e) {
    }
  }
}
