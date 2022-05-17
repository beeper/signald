/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald.clientprotocol.v1;

import io.finn.signald.Account;
import io.finn.signald.annotations.ExampleValue;
import io.finn.signald.annotations.ProtocolType;
import io.finn.signald.annotations.Required;
import io.finn.signald.clientprotocol.Request;
import io.finn.signald.clientprotocol.RequestType;
import io.finn.signald.clientprotocol.v1.exceptions.*;
import io.finn.signald.clientprotocol.v1.exceptions.InternalError;
import io.finn.signald.db.Database;
import java.sql.SQLException;

@ProtocolType("list_groups")
public class ListGroupsRequest implements RequestType<GroupList> {

  @Required @ExampleValue(ExampleValue.LOCAL_UUID) public String account;

  @Override
  public GroupList run(Request request) throws InternalError, InvalidProxyError, ServerNotFoundError, NoSuchAccountError, InvalidRequestError, AuthorizationFailedError, SQLError {
    GroupList groups = new GroupList();
    Account a = Common.getAccount(account);

    try {
      for (var g : Database.Get(a.getACI()).GroupsTable.getAll()) {
        groups.add(g.getJsonGroupV2Info());
      }
    } catch (SQLException e) {
      throw new InternalError("error listing groups", e);
    }

    return groups;
  }
}
