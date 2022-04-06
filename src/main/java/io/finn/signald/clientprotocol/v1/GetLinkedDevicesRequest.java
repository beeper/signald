/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald.clientprotocol.v1;

import io.finn.signald.Account;
import io.finn.signald.LinkedDeviceManager;
import io.finn.signald.annotations.Doc;
import io.finn.signald.annotations.ExampleValue;
import io.finn.signald.annotations.ProtocolType;
import io.finn.signald.annotations.Required;
import io.finn.signald.clientprotocol.Request;
import io.finn.signald.clientprotocol.RequestType;
import io.finn.signald.clientprotocol.v1.exceptions.*;
import io.finn.signald.clientprotocol.v1.exceptions.InternalError;
import io.finn.signald.exceptions.NoSuchAccountException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.signal.libsignal.protocol.ecc.ECPrivateKey;
import org.whispersystems.signalservice.api.push.exceptions.AuthorizationFailedException;

@ProtocolType("get_linked_devices")
@Doc("list all linked devices on a Signal account")
public class GetLinkedDevicesRequest implements RequestType<LinkedDevices> {
  private static final Logger logger = LogManager.getLogger();
  @ExampleValue(ExampleValue.LOCAL_UUID) @Doc("The account to interact with") @Required public String account;

  @Override
  public LinkedDevices run(Request request)
      throws InternalError, InvalidProxyError, ServerNotFoundError, NoSuchAccountError, InvalidRequestError, AuthorizationFailedError, SQLError {
    Account a = Common.getAccount(account);
    List<DeviceInfo> devices;
    try {
      ECPrivateKey profileKey = a.getACIIdentityKeyPair().getPrivateKey();
      devices = new LinkedDeviceManager(a).getLinkedDevices().stream().map(x -> new DeviceInfo(x, profileKey)).collect(Collectors.toList());
    } catch (io.finn.signald.exceptions.InvalidProxyException e) {
      throw new InvalidProxyError(e);
    } catch (io.finn.signald.exceptions.ServerNotFoundException e) {
      throw new ServerNotFoundError(e);
    } catch (NoSuchAccountException e) {
      throw new NoSuchAccountError(e);
    } catch (AuthorizationFailedException e) {
      throw new AuthorizationFailedError(e);
    } catch (SQLException | IOException e) {
      throw new InternalError("error getting linked devices", e);
    }
    return new LinkedDevices(devices);
  }
}
