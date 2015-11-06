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

package com.vmware.photon.controller.apife.entities;

import com.vmware.photon.controller.api.Flavor;
import com.vmware.photon.controller.api.FlavorState;
import com.vmware.photon.controller.api.QuotaLineItem;
import com.vmware.photon.controller.api.common.entities.base.TagEntity;
import com.vmware.photon.controller.api.common.entities.base.VisibleModelEntity;

import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Flavor entity.
 */
@Entity(name = "Flavor")
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"name", "kind"}))
@NamedQueries({
    @NamedQuery(
        name = "Flavor.listAll",
        query = "SELECT f from Flavor f"
    ),
    @NamedQuery(
        name = "Flavor.findByNameAndKind",
        query = "SELECT f FROM Flavor f WHERE f.name = :name AND f.kind = :kind"
    ),
    @NamedQuery(
        name = "Flavor.findByName",
        query = "SELECT f FROM Flavor f WHERE f.name = :name"
    ),
    @NamedQuery(
        name = "Flavor.findByKind",
        query = "SELECT f FROM Flavor f WHERE f.kind = :kind"
    )
})
public class FlavorEntity extends VisibleModelEntity {

  private String kind;

  @Enumerated(EnumType.STRING)
  private FlavorState state;

  @ElementCollection
  private List<QuotaLineItemEntity> cost = new ArrayList<>();

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public List<QuotaLineItemEntity> getCost() {
    return cost;
  }

  public void setCost(List<QuotaLineItemEntity> cost) {
    this.cost = cost;
  }

  public FlavorState getState() {
    return state;
  }

  public void setState(FlavorState state) {
    EntityStateValidator.validateStateChange(this.getState(), state, FlavorState.PRECONDITION_STATES);
    this.state = state;
  }

  public Flavor toApiRepresentation() {
    Flavor flavor = new Flavor();
    flavor.setId(getId());
    flavor.setName(getName());
    flavor.setKind(getKind());
    flavor.setState(getState());

    List<QuotaLineItem> costs = new ArrayList<>();
    for (QuotaLineItemEntity costEntity : getCost()) {
      costs.add(new QuotaLineItem(costEntity.getKey(), costEntity.getValue(), costEntity.getUnit()));
    }
    flavor.setCost(costs);

    Set<String> tags = new HashSet<>();
    for (TagEntity tag : getTags()) {
      tags.add(tag.getValue());
    }
    flavor.setTags(tags);
    return flavor;
  }
}
