/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald;

import io.finn.signald.clientprotocol.ClientConnection;
import io.finn.signald.db.Database;
import io.finn.signald.jobs.BackgroundJobRunnerThread;
import io.finn.signald.util.FileUtil;
import io.finn.signald.util.KeyUtil;
import io.sentry.Sentry;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.security.Security;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.asamk.signal.util.SecurityProvider;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateOutput;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.newsclub.net.unix.AFUNIXSocketCredentials;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.logging.SignalProtocolLoggerProvider;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.AuthorizationFailedException;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = BuildConfig.NAME, mixinStandardHelpOptions = true, version = BuildConfig.NAME + " " + BuildConfig.VERSION)
public class Main {
  private static final Logger logger = LogManager.getLogger();

  public static void main(String[] args) {
    CommandLine.populateCommand(new Config(), args);
    try {
      Config.init();

      logger.debug("Starting {} {}", BuildConfig.NAME, BuildConfig.VERSION);

      if (getJavaVersion() < 11) {
        logger.warn(
            "Support for this version of Java may be going away. Please update your java version. For more information see https://gitlab.com/signald/signald/-/issues/219");
      }

      // Workaround for BKS truststore
      Security.insertProviderAt(new SecurityProvider(), 1);
      Security.addProvider(new BouncyCastleProvider());
      FileUtil.setDataPath();
      FileUtil.createPrivateDirectories(Config.getDataPath());
      Manager.setDataPath();

      sdnotify("STATUS=migrating database " + Config.getDb());
      var flyway = Flyway.configure().baselineOnMigrate(true).baselineVersion("0.0");
      switch (Database.GetConnectionType()) {
      case SQLITE:
        flyway.locations("db/migration/sqlite");
        break;
      case POSTGRESQL:
        flyway.locations("db/migration/postgresql");
        break;
      }
      var migrateResult = flyway.dataSource(Config.getDb(), Config.getDbUser(), Config.getDbPassword()).load().migrate();
      for (String w : migrateResult.warnings) {
        logger.warn("db migration warning: " + w);
      }
      for (MigrateOutput o : migrateResult.migrations) {
        String message = "applied migration " + o.version + "/" + migrateResult.targetSchemaVersion + ": " + o.description + " [" + o.executionTime + " ms]";
        logger.info(message);
        sdnotify("STATUS=" + message);
      }

      if (Config.getTrustAllKeys()) {
        Database.Get().IdentityKeysTable.trustAllKeys();
      }

      // Migrate data as supported from the JSON state files:
      File[] allAccounts = new File(Config.getDataPath() + "/data").listFiles();
      if (allAccounts != null) {
        Pattern e164Pattern = Pattern.compile("^\\+?[1-9]\\d{1,14}$");
        for (File f : allAccounts) {
          if (f.isDirectory()) {
            continue;
          }
          if (e164Pattern.matcher(f.getName()).matches()) {
            Database.Get().AccountsTable.importFromLegacyJSON(f);
          } else {
            logger.warn("account file {} does NOT appear to have a valid phone number in the filename!", f.getAbsolutePath());
          }
        }
      }

      for (ACI aci : Database.Get().AccountsTable.getAll()) {
        Account account = new Account(aci);
        try {
          AccountRepair.repairAccountIfNeeded(account);
          if (account.getPNI() == null) {
            account.setPNI();
          }
          if (account.getPNIIdentityKeyPair() == null && account.getDeviceId() == SignalServiceAddress.DEFAULT_DEVICE_ID) {
            // Generate a PNI identity keypair if we're the primary device on the account and a PNI identity keypair doesn't exist
            account.setPNIIdentityKeyPair(new IdentityKeyPair(KeyUtil.generateIdentityKeyPair().serialize()));
          }
        } catch (AuthorizationFailedException e) {
          logger.error("server rejected our authorization while refreshing account {}, you may wish to remove this account from signald", account.getSelf().toRedactedString());
        } catch (Exception e) {
          Sentry.captureException(e);
          logger.fatal("error syncing local account state with server:", e);
        }
      }

      logger.info("all data migrations complete");
      if (Config.isMigrateData()) {
        logger.info("exiting as requested");
        System.exit(0);
      }

      BackgroundJobRunnerThread.start();

      // Spins up one thread per inbound connection to the control socket
      File socketFile = new File(Config.getSocketPath());
      if (socketFile.exists()) {
        logger.debug("Deleting existing socket file");
        Files.delete(socketFile.toPath());
      }

      logger.info("Binding to socket {}", Config.getSocketPath());
      AFUNIXServerSocket server = AFUNIXServerSocket.newInstance();
      try {
        server.bind(AFUNIXSocketAddress.of(socketFile));
      } catch (SocketException e) {
        logger.fatal("Error creating socket at {}: {}", socketFile, e.getMessage());
        System.exit(1);
      }

      SignalProtocolLoggerProvider.setProvider(new ProtocolLogger());

      logger.info("Started {} {}", BuildConfig.NAME, BuildConfig.VERSION);
      sdnotify("READY=1");

      while (!Thread.interrupted()) {
        try {
          AFUNIXSocket socket = server.accept();
          AFUNIXSocketCredentials credentials = socket.getPeerCredentials();
          logger.debug("Connection from pid {} uid {}", credentials.getPid(), credentials.getUid());
          new Thread(new ClientConnection(socket), "connection-pid-" + credentials.getPid()).start();
        } catch (IOException e) {
          logger.catching(e);
        }
      }
    } catch (Exception e) {
      sdnotify("STATUS=" + e.getMessage());
      logger.catching(e);
      System.exit(1);
    }
  }

  // sdnotify is based on https://gist.github.com/yrro/18dc22513f1001d0ec8d
  public static void sdnotify(String arg) {
    try {
      String notifySocket = System.getenv("NOTIFY_SOCKET");
      if (notifySocket == null || !Files.isDirectory(Paths.get("/run/systemd/system"), LinkOption.NOFOLLOW_LINKS)) {
        return;
      }
      Process p = new ProcessBuilder("systemd-notify", arg).redirectErrorStream(true).start();
      if (ignoreInterruptedException(p::waitFor) == 0)
        return;

      logger.error("Failed to notify systemd of/that {}; systemd-notify exited with status {}", arg, p.exitValue());
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
        r.lines().forEach(l -> logger.error("systemd-notify: {}", l));
      }
    } catch (IOException e) {
      logger.debug("Exception while notifying socket manager: ", e);
    }
  }

  private interface ThrowingSupplier<T, E extends Throwable> {
    T get() throws E;
  }

  private static <T> T ignoreInterruptedException(ThrowingSupplier<T, InterruptedException> r) {
    for (;;) {
      try {
        return r.get();
      } catch (InterruptedException ignored) {
      }
    }
  }

  private static int getJavaVersion() {
    String version = System.getProperty("java.version");
    if (version.startsWith("1.")) {
      version = version.substring(2, 3);
    } else {
      int dot = version.indexOf(".");
      if (dot != -1) {
        version = version.substring(0, dot);
      }
    }
    return Integer.parseInt(version);
  }
}
