package com.orientechnologies.agent.http.command;

import com.orientechnologies.agent.proxy.HttpProxy;
import com.orientechnologies.agent.proxy.HttpProxyListener;
import com.orientechnologies.orient.core.db.ODatabaseDocumentInternal;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.metadata.security.OUser;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.serialization.serializer.OStringSerializerHelper;
import com.orientechnologies.orient.server.distributed.ODistributedServerManager;
import com.orientechnologies.orient.server.network.protocol.http.OHttpRequest;
import com.orientechnologies.orient.server.network.protocol.http.OHttpResponse;
import com.orientechnologies.orient.server.network.protocol.http.command.OServerCommandAuthenticatedServerAbstract;

import java.io.IOException;
import java.util.List;

/**
 * Created by Enrico Risa on 16/11/15.
 */
public abstract class OServerCommandDistributedScope extends OServerCommandAuthenticatedServerAbstract {

  HttpProxy proxy = new HttpProxy();

  protected OServerCommandDistributedScope(String iRequiredResource) {
    super(iRequiredResource);

  }

  boolean isLocalNode(OHttpRequest iRequest) {

    ODistributedServerManager distributedManager = server.getDistributedManager();
    String node = iRequest.getParameter("node");

    if (node == null || distributedManager == null) {
      return true;
    }
    return distributedManager.getLocalNodeName().equalsIgnoreCase(node);
  }

  public void proxyRequest(OHttpRequest iRequest, OHttpResponse iResponse) throws IOException {
    proxyRequest(iRequest, iResponse, null);
  }

  public void proxyRequest(OHttpRequest iRequest, OHttpResponse iResponse, HttpProxyListener listener) throws IOException {

    ODistributedServerManager manager = server.getDistributedManager();
    String node = iRequest.getParameter("node");
    if ("_all".equalsIgnoreCase(node)) {
      proxy.broadcastRequest(manager, iRequest, iResponse);
    } else {
      proxy.proxyRequest(manager, node, iRequest, iResponse, listener);
    }
  }

  protected ODatabaseDocumentTx getProfiledDatabaseInstance(final OHttpRequest iRequest) throws InterruptedException {
    // after authentication, if current login user is different compare with current DB user, reset DB user to login user
    ODatabaseDocumentInternal localDatabase = ODatabaseRecordThreadLocal.INSTANCE.getIfDefined();

    if (localDatabase == null) {
      final List<String> parts = OStringSerializerHelper.split(iRequest.authorization, ':');
      localDatabase = (ODatabaseDocumentTx) server.openDatabase(iRequest.databaseName, parts.get(0), parts.get(1));
    } else {

      String currentUserId = iRequest.data.currentUserId;
      if (currentUserId != null && currentUserId.length() > 0 && localDatabase != null && localDatabase.getUser() != null) {
        if (!currentUserId.equals(localDatabase.getUser().getIdentity().toString())) {
          ODocument userDoc = localDatabase.load(new ORecordId(currentUserId));
          localDatabase.setUser(new OUser(userDoc));
        }
      }
    }

    iRequest.data.lastDatabase = localDatabase.getName();
    iRequest.data.lastUser = localDatabase.getUser() != null ? localDatabase.getUser().getName() : null;
    return (ODatabaseDocumentTx) ((ODatabaseDocumentInternal) localDatabase).getDatabaseOwner();
  }

}