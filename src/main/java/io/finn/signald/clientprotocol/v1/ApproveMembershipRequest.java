/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald.clientprotocol.v1;

import io.finn.signald.Account;
import io.finn.signald.Manager;
import io.finn.signald.annotations.Doc;
import io.finn.signald.annotations.ErrorDoc;
import io.finn.signald.annotations.ExampleValue;
import io.finn.signald.annotations.ProtocolType;
import io.finn.signald.annotations.Required;
import io.finn.signald.clientprotocol.Request;
import io.finn.signald.clientprotocol.RequestType;
import io.finn.signald.clientprotocol.v1.exceptions.*;
import io.finn.signald.clientprotocol.v1.exceptions.InternalError;
import io.finn.signald.db.Database;
import io.finn.signald.db.Recipient;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.signal.storageservice.protos.groups.GroupChange;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations;
import org.whispersystems.signalservice.api.util.UuidUtil;

@ProtocolType("approve_membership")
@Doc("approve a request to join a group")
@ErrorDoc(error = AuthorizationFailedError.class, doc = AuthorizationFailedError.DEFAULT_ERROR_DOC)
@ErrorDoc(error = GroupPatchNotAcceptedError.class, doc = GroupPatchNotAcceptedError.DEFAULT_ERROR_DOC)
public class ApproveMembershipRequest implements RequestType<JsonGroupV2Info> {
  @ExampleValue(ExampleValue.LOCAL_UUID) @Doc("The account to interact with") @Required public String account;

  @ExampleValue(ExampleValue.GROUP_ID) @Required public String groupID;

  @Required @Doc("list of requesting members to approve") public List<JsonAddress> members;

  @Override
  public JsonGroupV2Info run(Request request) throws NoSuchAccountError, ServerNotFoundError, InvalidProxyError, UnknownGroupError, InternalError, GroupVerificationError,
                                                     InvalidRequestError, AuthorizationFailedError, SQLError, GroupPatchNotAcceptedError, NetworkError {
    Manager m = Common.getManager(account);
    Account a = Common.getAccount(account);

    var group = Common.getGroup(a, groupID);

    var recipientsTable = Database.Get(m.getACI()).RecipientsTable;
    List<Recipient> recipients;
    try {
      recipients = group.getMembers();
    } catch (SQLException | IOException e) {
      throw new InternalError("error looking up recipients", e);
    }

    for (JsonAddress member : members) {
      try {
        recipients.add(recipientsTable.get(member));
      } catch (SQLException | IOException e) {
        throw new InternalError("error looking up new member", e);
      }
    }

    GroupsV2Operations.GroupOperations groupOperations = Common.getGroupOperations(a, group);

    Set<UUID> membersToApprove = members.stream().map(JsonAddress::getUUID).collect(Collectors.toSet());
    GroupChange.Actions.Builder change = groupOperations.createApproveGroupJoinRequest(membersToApprove);
    change.setSourceUuid(UuidUtil.toByteString(m.getUUID()));

    Common.updateGroup(a, group, change);

    return group.getJsonGroupV2Info();
  }
}
