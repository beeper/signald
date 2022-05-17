/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald.clientprotocol.v1;

import io.finn.signald.Account;
import io.finn.signald.Empty;
import io.finn.signald.annotations.Doc;
import io.finn.signald.annotations.ExampleValue;
import io.finn.signald.annotations.ProtocolType;
import io.finn.signald.annotations.Required;
import io.finn.signald.clientprotocol.Request;
import io.finn.signald.clientprotocol.RequestType;
import io.finn.signald.clientprotocol.v1.exceptions.*;
import io.finn.signald.clientprotocol.v1.exceptions.InternalError;
import io.finn.signald.exceptions.InvalidProxyException;
import io.finn.signald.exceptions.NoSuchAccountException;
import io.finn.signald.exceptions.ServerNotFoundException;
import java.io.IOException;
import java.sql.SQLException;

@ProtocolType("delete_account")
@Doc(
    "delete all account data signald has on disk, and optionally delete the account from the server as well. Note that this is not \"unlink\" and will delete the entire account, even from a linked device.")
public class DeleteAccountRequest implements RequestType<Empty> {
  @ExampleValue(ExampleValue.LOCAL_UUID) @Doc("The account to delete") @Required public String account;

  @Doc("delete account information from the server as well (default false)") public boolean server = false;

  @Override
  public Empty run(Request request) throws InternalError, InvalidProxyError, ServerNotFoundError, NoSuchAccountError, SQLError {
    Account a = Common.getAccount(account);
    try {
      a.delete(server);
    } catch (IOException e) {
      throw new InternalError("error deleting account", e);
    } catch (SQLException e) {
      throw new SQLError(e);
    } catch (NoSuchAccountException e) {
      throw new NoSuchAccountError(e);
    } catch (ServerNotFoundException e) {
      throw new ServerNotFoundError(e);
    } catch (InvalidProxyException e) {
      throw new InvalidProxyError(e);
    }
    return new Empty();
  }
}
