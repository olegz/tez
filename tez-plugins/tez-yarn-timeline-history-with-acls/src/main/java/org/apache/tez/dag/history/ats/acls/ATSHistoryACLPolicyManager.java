/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tez.dag.history.ats.acls;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.timeline.TimelineDomain;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntity;
import org.apache.hadoop.yarn.client.api.TimelineClient;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.tez.common.security.ACLConfigurationParser;
import org.apache.tez.common.security.ACLManager;
import org.apache.tez.common.security.ACLType;
import org.apache.tez.common.security.DAGAccessControls;
import org.apache.tez.common.security.HistoryACLPolicyManager;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.dag.api.TezUncheckedException;

import com.google.common.annotations.VisibleForTesting;

public class ATSHistoryACLPolicyManager implements HistoryACLPolicyManager {

  private final static Log LOG = LogFactory.getLog(ATSHistoryACLPolicyManager.class);

  TimelineClient timelineClient;
  Configuration conf;
  String user;
  final static String DOMAIN_ID_PREFIX = "Tez_ATS_";

  private void initializeTimelineClient() {
    if (this.conf == null) {
      throw new TezUncheckedException("ATSACLManager not configured");
    }
    if (timelineClient != null) {
      this.timelineClient.stop();
      this.timelineClient = null;
    }
    this.timelineClient = TimelineClient.createTimelineClient();
    this.timelineClient.init(this.conf);
    this.timelineClient.start();
    try {
      this.user = UserGroupInformation.getCurrentUser().getShortUserName();
    } catch (IOException e) {
      throw new TezUncheckedException("Unable to get Current User UGI", e);
    }
  }

  private String getMergedViewACLs(ACLConfigurationParser parser,
      DAGAccessControls dagAccessControls) {
    Map<ACLType, Set<String>> allowedUsers = parser.getAllowedUsers();
    Map<ACLType, Set<String>> allowedGroups = parser.getAllowedGroups();

    Set<String> viewUsers = new HashSet<String>();
    viewUsers.add(user);
    if (allowedUsers.containsKey(ACLType.AM_VIEW_ACL)) {
      viewUsers.addAll(allowedUsers.get(ACLType.AM_VIEW_ACL));
    }
    if (dagAccessControls != null && dagAccessControls.getUsersWithViewACLs() != null) {
      viewUsers.addAll(dagAccessControls.getUsersWithViewACLs());
    }

    if (viewUsers.contains(ACLManager.WILDCARD_ACL_VALUE)) {
      return ACLManager.WILDCARD_ACL_VALUE;
    }

    Set<String> viewGroups = new HashSet<String>();
    if (allowedGroups.containsKey(ACLType.AM_VIEW_ACL)) {
      viewGroups.addAll(allowedGroups.get(ACLType.AM_VIEW_ACL));
    }
    if (dagAccessControls != null && dagAccessControls.getGroupsWithViewACLs() != null) {
      viewGroups.addAll(dagAccessControls.getGroupsWithViewACLs());
    }

    return ACLManager.toCommaSeparatedString(viewUsers) + " " +
        ACLManager.toCommaSeparatedString(viewGroups);
  }

  private void createTimelineDomain(String domainId, Configuration tezConf,
      DAGAccessControls dagAccessControls) throws IOException {
    TimelineDomain timelineDomain = new TimelineDomain();
    timelineDomain.setId(domainId);

    ACLConfigurationParser parser = new ACLConfigurationParser(tezConf, false);
    timelineDomain.setReaders(getMergedViewACLs(parser, dagAccessControls));
    timelineDomain.setWriters(user);

    try {
      timelineClient.putDomain(timelineDomain);
    } catch (YarnException e) {
      throw new IOException(e);
    }
  }


  private Map<String, String> createSessionDomain(Configuration tezConf,
      ApplicationId applicationId, DAGAccessControls dagAccessControls)
      throws IOException {
    String domainId =
        tezConf.get(TezConfiguration.YARN_ATS_ACL_SESSION_DOMAIN_ID);
    if (!tezConf.getBoolean(TezConfiguration.TEZ_AM_ACLS_ENABLED,
        TezConfiguration.TEZ_AM_ACLS_ENABLED_DEFAULT)) {
      if (domainId != null) {
        throw new TezUncheckedException("ACLs disabled but DomainId is specified"
            + ", aclsEnabled=true, domainId=" + domainId);
      }
      return null;
    }

    boolean autoCreateDomain = tezConf.getBoolean(TezConfiguration.YARN_ATS_ACL_DOMAINS_AUTO_CREATE,
        TezConfiguration.YARN_ATS_ACL_DOMAINS_AUTO_CREATE_DEFAULT);

    if (domainId != null) {
      // do nothing
      LOG.info("Using specified domainId with Timeline, domainId=" + domainId);
      return null;
    } else {
      if (!autoCreateDomain) {
        // Error - Cannot fallback to default as that leaves ACLs open
        throw new TezUncheckedException("Timeline DomainId is not specified and auto-create"
            + " Domains is disabled");
      }
      domainId = DOMAIN_ID_PREFIX + applicationId.toString();
      createTimelineDomain(domainId, tezConf, dagAccessControls);
      LOG.info("Created Timeline Domain for History ACLs, domainId=" + domainId);
      return Collections.singletonMap(TezConfiguration.YARN_ATS_ACL_SESSION_DOMAIN_ID, domainId);
    }
  }

  private Map<String, String> createDAGDomain(Configuration tezConf,
      ApplicationId applicationId, String dagName, DAGAccessControls dagAccessControls)
      throws IOException {
    if (dagAccessControls == null) {
      // No DAG specific ACLs
      return null;
    }

    String domainId =
        tezConf.get(TezConfiguration.YARN_ATS_ACL_DAG_DOMAIN_ID);
    if (!tezConf.getBoolean(TezConfiguration.TEZ_AM_ACLS_ENABLED,
        TezConfiguration.TEZ_AM_ACLS_ENABLED_DEFAULT)) {
      if (domainId != null) {
        throw new TezUncheckedException("ACLs disabled but domainId for DAG is specified"
            + ", aclsEnabled=true, domainId=" + domainId);
      }
      return null;
    }

    boolean autoCreateDomain = tezConf.getBoolean(TezConfiguration.YARN_ATS_ACL_DOMAINS_AUTO_CREATE,
        TezConfiguration.YARN_ATS_ACL_DOMAINS_AUTO_CREATE_DEFAULT);

    if (domainId != null) {
      // do nothing
      LOG.info("Using specified domainId with Timeline, domainId=" + domainId);
      return null;
    } else {
      if (!autoCreateDomain) {
        // Error - Cannot fallback to default as that leaves ACLs open
        throw new TezUncheckedException("Timeline DomainId is not specified and auto-create"
            + " Domains is disabled");
      }

      domainId = DOMAIN_ID_PREFIX + applicationId.toString() + "_" + dagName;
      createTimelineDomain(domainId, tezConf, dagAccessControls);
      LOG.info("Created Timeline Domain for DAG-specific History ACLs, domainId=" + domainId);
      return Collections.singletonMap(TezConfiguration.YARN_ATS_ACL_DAG_DOMAIN_ID, domainId);
    }
  }


  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
    initializeTimelineClient();
  }

  @Override
  public Configuration getConf() {
    return this.conf;
  }

  @Override
  public Map<String, String> setupSessionACLs(Configuration conf, ApplicationId applicationId)
      throws IOException {
    return createSessionDomain(conf, applicationId, null);
  }

  @Override
  public Map<String, String> setupNonSessionACLs(Configuration conf, ApplicationId applicationId,
      DAGAccessControls dagAccessControls) throws IOException {
    return createSessionDomain(conf, applicationId, dagAccessControls);
  }

  @Override
  public Map<String, String> setupSessionDAGACLs(Configuration conf, ApplicationId applicationId,
      String dagName, DAGAccessControls dagAccessControls) throws IOException {
    return createDAGDomain(conf, applicationId, dagName, dagAccessControls);
  }

  @Override
  public void updateTimelineEntityDomain(Object timelineEntity, String domainId) {
    if (!(timelineEntity instanceof TimelineEntity)) {
      throw new UnsupportedOperationException("Invalid object provided of type"
          + timelineEntity.getClass().getName());
    }
    TimelineEntity entity = (TimelineEntity) timelineEntity;
    entity.setDomainId(domainId);
  }

}
