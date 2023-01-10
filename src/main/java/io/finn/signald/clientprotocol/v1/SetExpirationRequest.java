/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald.clientprotocol.v1;

import static io.finn.signald.annotations.ExactlyOneOfRequired.RECIPIENT;

import io.finn.signald.Account;
import io.finn.signald.Manager;
import io.finn.signald.annotations.*;
import io.finn.signald.clientprotocol.Request;
import io.finn.signald.clientprotocol.RequestType;
import io.finn.signald.clientprotocol.v1.exceptions.*;
import io.finn.signald.clientprotocol.v1.exceptions.InternalError;
import io.finn.signald.db.Database;
import io.finn.signald.db.Recipient;
import java.io.IOException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;

@ProtocolType("set_expiration")
@ErrorDoc(error = AuthorizationFailedError.class, doc = AuthorizationFailedError.DEFAULT_ERROR_DOC)
@ErrorDoc(error = GroupPatchNotAcceptedError.class, doc = "If updating a group, caused when server rejects the group update.")
@Doc("Set the message expiration timer for a thread. Expiration must be specified in seconds, set to 0 to disable timer")
public class SetExpirationRequest implements RequestType<SendResponse> {

  @ExampleValue(ExampleValue.LOCAL_UUID) @Doc("The account to use") @Required public String account;
  @ExactlyOneOfRequired(RECIPIENT) public JsonAddress address;
  @ExampleValue(ExampleValue.GROUP_ID) @ExactlyOneOfRequired(RECIPIENT) public String group;
  @ExampleValue("604800") @Required public int expiration;

  @Override
  public SendResponse run(Request request) throws InternalError, InvalidProxyError, ServerNotFoundError, NoSuchAccountError, UnknownGroupError, GroupVerificationError,
                                                  InvalidRequestError, AuthorizationFailedError, UnregisteredUserError, SQLError, GroupPatchNotAcceptedError, UnsupportedGroupError,
                                                  NetworkError, InvalidRecipientError, ProofRequiredError, RateLimitError, SignalServerError {
    List<SendMessageResult> results;
    Account a = Common.getAccount(account);

    if (group != null) {
      if (group.length() != 44) {
        throw new UnsupportedGroupError();
      }
      var storedGroup = Common.getGroup(a, group);
      GroupsV2Operations.GroupOperations groupOperations = Common.getGroupOperations(a, storedGroup);
      results = Common.updateGroup(a, storedGroup, groupOperations.createModifyGroupTimerChange(expiration));

    } else {
      Recipient recipient = Common.getRecipient(a.getACI(), address);
      try {
        a.getDB().ContactsTable.update(recipient, null, null, null, expiration, null);
        SignalServiceDataMessage.Builder messageBuilder = SignalServiceDataMessage.newBuilder().asExpirationUpdate().withExpiration(expiration);
        results = Common.send(a, messageBuilder, recipient, null, null);
      } catch (SQLException e) {
        throw new InternalError("error setting expiration", e);
      }
    }

    return new SendResponse(results, 0);
  }
}
