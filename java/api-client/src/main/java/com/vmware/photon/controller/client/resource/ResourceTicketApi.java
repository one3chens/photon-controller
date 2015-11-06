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

import com.vmware.photon.controller.api.ResourceList;
import com.vmware.photon.controller.api.ResourceTicket;
import com.vmware.photon.controller.api.Task;
import com.vmware.photon.controller.client.RestClient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.util.concurrent.FutureCallback;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;

import java.io.IOException;

/**
 * Resource Ticket Api.
 */
public class ResourceTicketApi extends ApiBase {
  public ResourceTicketApi(RestClient restClient) {
    super(restClient);
  }

  @Override
  public String getBasePath() {
    return "/resource-tickets";
  }

  /**
   * Get details about the specified resource ticket.
   *
   * @param resourceTicketId
   * @return Resource ticket details
   * @throws IOException
   */
  public ResourceTicket getResourceTicket(String resourceTicketId) throws IOException {
    String path = String.format("%s/%s", getBasePath(), resourceTicketId);

    HttpResponse httpResponse = this.restClient.perform(RestClient.Method.GET, path, null);
    this.restClient.checkResponse(httpResponse, HttpStatus.SC_OK);

    return this.restClient.parseHttpResponse(
        httpResponse,
        new TypeReference<ResourceTicket>() {
        }
    );
  }

  /**
   * Get details about the specified resource ticket.
   *
   * @param resourceTicketId
   * @param responseCallback
   * @throws IOException
   */
  public void getResourceTicketAsync(final String resourceTicketId, final FutureCallback<ResourceTicket>
      responseCallback) throws
      IOException {
    final String path = String.format("%s/%s", getBasePath(), resourceTicketId);

    getObjectByPathAsync(path, responseCallback, new TypeReference<ResourceTicket>() {
    });
  }

  /**
   * Get tasks associated with the specified resource ticket.
   *
   * @param resourceTicketId
   * @return {@link ResourceList} of {@link Task}
   * @throws IOException
   */
  public ResourceList<Task> getTasksForResourceTicket(String resourceTicketId) throws IOException {
    String path = String.format("%s/%s/tasks", getBasePath(), resourceTicketId);

    HttpResponse httpResponse = this.restClient.perform(RestClient.Method.GET, path, null);
    this.restClient.checkResponse(httpResponse, HttpStatus.SC_OK);

    return this.restClient.parseHttpResponse(
        httpResponse,
        new TypeReference<ResourceList<Task>>() {
        }
    );
  }

  /**
   * Get tasks associated with the specified resource ticket.
   *
   * @param resourceTicketId
   * @param responseCallback
   * @throws IOException
   */
  public void getTasksForResourceTicketAsync(final String resourceTicketId, final FutureCallback<ResourceList<Task>>
      responseCallback) throws
      IOException {
    final String path = String.format("%s/%s/tasks", getBasePath(), resourceTicketId);

    getObjectByPathAsync(path, responseCallback, new TypeReference<ResourceList<Task>>() {
    });
  }
}
