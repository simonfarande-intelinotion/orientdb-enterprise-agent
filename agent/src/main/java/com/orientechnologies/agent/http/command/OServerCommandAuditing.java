/*
 * Copyright 2010-2013 Orient Technologies LTD (info--at--orientechnologies.com)
 * All Rights Reserved. Commercial License.
 * 
 * NOTICE:  All information contained herein is, and remains the property of
 * Orient Technologies LTD and its suppliers, if any.  The intellectual and
 * technical concepts contained herein are proprietary to
 * Orient Technologies LTD and its suppliers and may be covered by United
 * Kingdom and Foreign Patents, patents in process, and are protected by trade
 * secret or copyright law.
 * 
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Orient Technologies LTD.
 * 
 * For more information: http://www.orientechnologies.com
 */
package com.orientechnologies.agent.http.command;

import com.orientechnologies.agent.OEnterpriseAgent;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery;
import com.orientechnologies.orient.server.network.protocol.http.OHttpRequest;
import com.orientechnologies.orient.server.network.protocol.http.OHttpResponse;
import com.orientechnologies.orient.server.network.protocol.http.OHttpUtils;
import com.orientechnologies.orient.server.security.OServerSecurity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class OServerCommandAuditing extends OServerCommandDistributedScope {
  private static final String[] NAMES = { "GET|auditing/*", "POST|auditing/*" };
  private OServerSecurity security;

  public OServerCommandAuditing(OServerSecurity security) {
    super("server.profiler");
    this.security = security;
  }

  @Override
  public boolean execute(final OHttpRequest iRequest, OHttpResponse iResponse) throws Exception {
    final String[] parts = checkSyntax(iRequest.getUrl(), 3, "Syntax error: auditing/<db>/<action>");

    iRequest.data.commandInfo = "Auditing information";

    String db = parts[1];
    String action = parts[2];

    if (isLocalNode(iRequest)) {
      if ("GET".equals(iRequest.httpMethod)) {
        if (action.equalsIgnoreCase("config")) {
          doGet(iRequest, iResponse, parts[1]);
        }
      } else if ("POST".equals(iRequest.httpMethod)) {
        if (action.equalsIgnoreCase("config")) {
          doPost(iRequest, iResponse, db);
        } else if (action.equalsIgnoreCase("query")) {
          doGetData(iRequest, iResponse, db);

        }
      }
    } else {
      proxyRequest(iRequest, iResponse);
    }
    return false;
  }

  private void doGetData(OHttpRequest iRequest, OHttpResponse iResponse, String db) throws IOException, InterruptedException {

    ODocument params = new ODocument().fromJSON(iRequest.content);

    iRequest.databaseName = db;
    ODatabaseDocumentTx databaseDocumentTx = getProfiledDatabaseInstance(iRequest);

    String clazz = params.field("clazz");
    Collection<ODocument> documents = new ArrayList<ODocument>();
    if (databaseDocumentTx.getMetadata().getSchema().existsClass(clazz)) {
      documents = databaseDocumentTx.command(new OSQLSynchQuery<ODocument>(buildQuery(params))).execute(params.toMap());
    }
    iResponse.writeResult(documents);
  }

  private String buildQuery(ODocument params) {
    String query = "select user.name as username,* from :clazz :where order by date desc limit :limit";

    List<String> whereConditions = new ArrayList<String>();
    String clazz = params.field("clazz");
    Integer limit = params.field("limit");

    query = query.replace(":clazz", clazz);

    if (isNotNullNotEmpty(params, "operation")) {
      whereConditions.add("operation = :operation");
    }
    if (isNotNullNotEmpty(params, "user")) {
      whereConditions.add("user.name = :user");
    }
    if (isNotNullNotEmpty(params, "record")) {
      whereConditions.add("record = :record");
    }
    if (isNotNullNotEmpty(params, "note")) {
      String note = params.field("note");
      note = "%" + note + "%";
      params.field("note", note);
      whereConditions.add("note LIKE :note");
    }
    if (params.containsField("fromDate")) {
      whereConditions.add("date > :fromDate");
    }
    if (params.containsField("toDate")) {
      whereConditions.add("date < :toDate");
    }
    query = query.replace(":where", buildWhere(whereConditions));

    query = query.replace(":limit", "" + limit);
    return query;
  }

  private boolean isNotNullNotEmpty(ODocument params, String field) {

    boolean valid = params.field(field) != null;
    if (valid) {
      Object val = params.field(field);

      if (val instanceof String) {
        valid = !((String) val).isEmpty();
      }
    }
    return valid;
  }

  private String buildWhere(List<String> whereConditions) {
    String where = "";
    int i = 0;
    for (String whereCondition : whereConditions) {
      if (i != 0) {
        where += " and ";
      } else {
        where += "where ";
      }
      where += whereCondition;
      i++;
    }
    return where;
  }

  private void doPost(OHttpRequest iRequest, OHttpResponse iResponse, String db) throws Exception {

    ODocument config = new ODocument().fromJSON(iRequest.content, "noMap");
    iRequest.databaseName = db;
    getProfiledDatabaseInstance(iRequest);

    if(security.getAuditing() != null)
    	security.getAuditing().changeConfig(db, config);

    iResponse.send(OHttpUtils.STATUS_OK_CODE, "OK", OHttpUtils.CONTENT_JSON, config.toJSON("prettyPrint"), null);
  }

  private void doGet(OHttpRequest iRequest, OHttpResponse iResponse, String db) throws Exception {
    iRequest.databaseName = db;
    getProfiledDatabaseInstance(iRequest);

    ODocument config = null;

    if(security.getAuditing() != null)
    	config = security.getAuditing().getConfig(db);

    iResponse.send(OHttpUtils.STATUS_OK_CODE, "OK", OHttpUtils.CONTENT_JSON, config.toJSON("prettyPrint"), null);

  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
