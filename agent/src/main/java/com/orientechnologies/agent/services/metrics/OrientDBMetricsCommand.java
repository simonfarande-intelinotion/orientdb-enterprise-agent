package com.orientechnologies.agent.services.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orientechnologies.agent.cloud.processor.tasks.EnterpriseStatsResponse;
import com.orientechnologies.agent.cloud.processor.tasks.NewEnterpriseStatsTask;
import com.orientechnologies.agent.operation.NodeResponse;
import com.orientechnologies.agent.operation.OperationResponseFromNode;
import com.orientechnologies.agent.operation.ResponseOk;
import com.orientechnologies.agent.profiler.OMetricsRegistry;
import com.orientechnologies.agent.profiler.metrics.OMetric;
import com.orientechnologies.agent.profiler.metrics.OMetricSet;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.enterprise.server.OEnterpriseServer;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.server.distributed.ODistributedConfiguration;
import com.orientechnologies.orient.server.distributed.ODistributedServerManager;
import com.orientechnologies.orient.server.network.protocol.http.OHttpRequest;
import com.orientechnologies.orient.server.network.protocol.http.OHttpResponse;
import com.orientechnologies.orient.server.network.protocol.http.OHttpUtils;
import com.orientechnologies.orient.server.network.protocol.http.command.OServerCommandAuthenticatedServerAbstract;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by Enrico Risa on 06/08/2018.
 */
public class OrientDBMetricsCommand extends OServerCommandAuthenticatedServerAbstract {

  private static final String[] NAMES = { "GET|metrics", "GET|metrics/*", "POST|metrics/config", "POST|metrics/config" };

  private OEnterpriseServer      enterpriseServer;
  private OMetricsRegistry       registry;
  private OrientDBMetricsService service;
  private ObjectMapper           mapper = new ObjectMapper();

  public OrientDBMetricsCommand(OEnterpriseServer enterpriseServer, OMetricsRegistry registry, OrientDBMetricsService settings) {
    super("server.metrics");
    this.enterpriseServer = enterpriseServer;
    this.registry = registry;
    this.service = settings;
  }

  @Override
  public boolean execute(OHttpRequest iRequest, OHttpResponse iResponse) throws Exception {
    final String[] parts = checkSyntax(iRequest.getUrl(), 1, "Syntax error: metrics");

    if (iRequest.httpMethod.equalsIgnoreCase("GET")) {
      doGet(iResponse, parts);
    } else if (iRequest.httpMethod.equalsIgnoreCase("POST")) {
      doPost(iRequest, iResponse, parts);
    }

    return false;
  }

  private ODocument calculateDBStatus(final ODistributedServerManager manager, final ODocument cfg) {

    final ODocument doc = new ODocument();
    final Collection<ODocument> members = cfg.field("members");

    Set<String> databases = new HashSet<String>();
    for (ODocument m : members) {
      final Collection<String> dbs = m.field("databases");
      for (String db : dbs) {
        databases.add(db);
      }
    }
    for (String database : databases) {
      doc.field(database, singleDBStatus(manager, database));
    }
    return doc;
  }

  private ODocument singleDBStatus(ODistributedServerManager manager, String database) {
    final ODocument entries = new ODocument();
    final ODistributedConfiguration dbCfg = manager.getDatabaseConfiguration(database, false);
    final Set<String> servers = dbCfg.getAllConfiguredServers();
    for (String serverName : servers) {
      final ODistributedServerManager.DB_STATUS databaseStatus = manager.getDatabaseStatus(serverName, database);
      entries.field(serverName, databaseStatus.toString());
    }
    return entries;
  }

  private void doGet(OHttpResponse iResponse, String[] parts) throws IOException {
    if (parts.length == 1) {

      ODocument metrics = new ODocument();
      ODistributedServerManager manager = server.getDistributedManager();

      if (manager != null) {
        metrics = manager.getClusterConfiguration();
        final Collection<ODocument> documents = metrics.field("members");
        List<String> servers = new ArrayList<String>(documents.size());
        for (ODocument document : documents)
          servers.add(document.field("name"));

        Set<String> databases = manager.getServerInstance().listDatabases();
        if (databases.isEmpty()) {
          OLogManager.instance().warn(this, "Cannot load stats, no databases on this server");

        } else {

          Map<String, ODocument> responses = enterpriseServer.getNodesManager().sendAll(new NewEnterpriseStatsTask()).stream()
              .collect(Collectors.toMap(OperationResponseFromNode::getSenderNodeName, (r) -> {
                NodeResponse node = r.getNodeResponse();
                if (node != null && node.getResponseType() == 1) {
                  ResponseOk ok = (ResponseOk) node;
                  EnterpriseStatsResponse response = (EnterpriseStatsResponse) ok.getPayload();
                  return new ODocument().fromJSON(response.getStats());
                }
                return new ODocument();
              }));

          metrics.field("clusterStats", responses);

          metrics.field("databasesStatus", calculateDBStatus(manager, metrics));
        }

      } else {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Map<String, ODocument> singleNodeStats = new HashMap<>();
        registry.toJSON(buffer);
        singleNodeStats.put("orientdb", new ODocument().fromJSON(buffer.toString()));
        metrics.field("clusterStats", singleNodeStats);
      }
      metrics.setProperty("distributed", manager != null);
      iResponse
          .send(OHttpUtils.STATUS_OK_CODE, OHttpUtils.STATUS_OK_DESCRIPTION, OHttpUtils.CONTENT_JSON, metrics.toJSON(""), null);
    } else {
      String command = parts[1];

      OrientDBMetricsSettings settings = service.getSettings();
      switch (command) {
      case "prometheus":
        if (settings.reporters.prometheus.enabled) {
          try (StringWriter writer = new StringWriter()) {
            TextFormat.write004(writer, CollectorRegistry.defaultRegistry.filteredMetricFamilySamples(Collections.emptySet()));
            iResponse
                .send(OHttpUtils.STATUS_OK_CODE, OHttpUtils.STATUS_OK_DESCRIPTION, OHttpUtils.CONTENT_TEXT_PLAIN, writer.toString(),
                    null);
          }
        }
        break;
      case "config":
        String valueAsString = mapper.writeValueAsString(settings);
        iResponse.send(OHttpUtils.STATUS_OK_CODE, OHttpUtils.STATUS_OK_DESCRIPTION, OHttpUtils.CONTENT_JSON, valueAsString, null);
        break;
      case "list":
        Map<String, String> metrics = getMetricsLists(registry.getMetrics(), "");
        String metricsAsString = mapper.writeValueAsString(metrics);
        iResponse.send(OHttpUtils.STATUS_OK_CODE, OHttpUtils.STATUS_OK_DESCRIPTION, OHttpUtils.CONTENT_JSON, metricsAsString, null);
        break;
      }

    }
  }

  private Map<String, String> getMetricsLists(Map<String, OMetric> metrics, String prefix) {

    Map<String, String> m = new HashMap<>();
    metrics.forEach((k, v) -> {
      if (v instanceof OMetricSet) {
        m.putAll(getMetricsLists(((OMetricSet) v).getMetrics(), ((OMetricSet) v).prefix()));
      } else {
        if (prefix != null && !prefix.isEmpty()) {
          m.put(prefix + "." + k, v.getDescription());
        } else {
          m.put(k, v.getDescription());
        }
      }
    });
    return m;

  }

  private void doPost(OHttpRequest iRequest, OHttpResponse iResponse, String[] parts) throws IOException {
    OrientDBMetricsSettings settings = mapper.readValue(iRequest.content, OrientDBMetricsSettings.class);
    service.changeSettings(settings);
    iResponse.send(OHttpUtils.STATUS_OK_CODE, OHttpUtils.STATUS_OK_DESCRIPTION, OHttpUtils.CONTENT_JSON, null, null);
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}