package com.github.airblader.imap.table;

import static com.github.airblader.imap.ImapUtils.getImapProperties;
import static com.github.airblader.imap.table.MessageUtils.*;

import com.github.airblader.imap.ScanMode;
import com.sun.mail.imap.IMAPFolder;
import jakarta.mail.*;
import java.util.List;
import java.util.stream.Collectors;
import lombok.var;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.types.DataType;
import org.apache.flink.types.RowKind;

// TODO Option to mark emails seen
// TODO Exactly once semantics
public class ImapSourceFunction extends RichSourceFunction<RowData> {
  private final ImapSourceOptions connectorOptions;
  private final List<String> fieldNames;
  private final DataType rowType;

  public ImapSourceFunction(
      ImapSourceOptions connectorOptions, List<String> fieldNames, DataType rowType) {
    this.connectorOptions = connectorOptions;
    this.fieldNames = fieldNames.stream().map(String::toUpperCase).collect(Collectors.toList());
    this.rowType = rowType;
  }

  private transient volatile boolean running = false;
  private transient Store store;
  private transient IMAPFolder folder;
  private transient IdleHeartbeatThread idleHeartbeat;

  private volatile boolean supportsIdle = true;
  private FetchProfile fetchProfile;

  @Override
  public void run(SourceContext<RowData> ctx) throws Exception {
    fetchProfile = getFetchProfile();

    var session = Session.getInstance(getImapProperties(connectorOptions), null);
    store = session.getStore();
    try {
      store.connect(connectorOptions.getEffectiveUser(), connectorOptions.getEffectivePassword());
    } catch (MessagingException e) {
      throw new ImapSourceException("Failed to connect to the IMAP server.", e);
    }

    try {
      var genericFolder = store.getFolder(connectorOptions.getFolder());
      folder = (IMAPFolder) genericFolder;
    } catch (MessagingException e) {
      throw new ImapSourceException("Could not get folder " + folder.getName(), e);
    } catch (ClassCastException e) {
      throw new ImapSourceException(
          "Folder " + folder.getName() + " is not an " + IMAPFolder.class.getSimpleName(), e);
    }

    openFolder();
    if (!folder.exists()) {
      throw new ImapSourceException("Folder " + folder.getName() + " does not exist.");
    }

    if (connectorOptions.getMode() == ScanMode.ALL) {
      fetchExistingMessages(ctx);
    }

    folder.addMessageCountListener(
        new MessageCollector(
            (rowKind, messages) -> collectMessages(ctx, rowKind, messages),
            connectorOptions.isDeletions()));

    running = true;
    enterWaitLoop();
  }

  @Override
  public void cancel() {
    running = false;
    stopIdleHeartbeat();
  }

  @Override
  public void close() {
    try {
      if (folder != null) {
        folder.close(false);
      }

      if (store != null) {
        store.close();
      }
    } catch (MessagingException ignored) {
    }
  }

  private void fetchExistingMessages(SourceContext<RowData> ctx) throws MessagingException {
    int currentNum = 1;
    int numberOfMessages = folder.getMessageCount();

    // We need to loop to ensure we're not missing any messages coming in while we're processing
    // these.
    // See https://eclipse-ee4j.github.io/mail/FAQ#addlistener.
    while (currentNum <= numberOfMessages) {
      collectMessages(ctx, RowKind.INSERT, folder.getMessages(currentNum, numberOfMessages));

      currentNum = numberOfMessages + 1;
      numberOfMessages = folder.getMessageCount();
    }
  }

  private void collectMessages(SourceContext<RowData> ctx, RowKind rowKind, Message[] messages) {
    try {
      folder.fetch(messages, fetchProfile);
    } catch (MessagingException ignored) {
    }

    for (Message message : messages) {
      try {
        collectMessage(ctx, rowKind, message);
      } catch (Exception ignored) {
      }
    }
  }

  private void collectMessage(SourceContext<RowData> ctx, RowKind rowKind, Message message)
      throws MessagingException {
    var row = new GenericRowData(fieldNames.size());
    row.setRowKind(rowKind);

    for (int i = 0; i < fieldNames.size(); i++) {
      var typeRoot = rowType.getChildren().get(i).getLogicalType().getTypeRoot();

      switch (fieldNames.get(i)) {
        case "SUBJECT":
          row.setField(i, StringData.fromString(message.getSubject()));
          break;
        case "SENT":
          row.setField(i, TimestampData.fromInstant(message.getSentDate().toInstant()));
          break;
        case "RECEIVED":
          row.setField(i, TimestampData.fromInstant(message.getReceivedDate().toInstant()));
          break;
        case "TO":
          row.setField(
              i,
              mapAddressItems(
                  message.getRecipients(Message.RecipientType.TO),
                  typeRoot,
                  connectorOptions.getAddressFormat()));
          break;
        case "CC":
          row.setField(
              i,
              mapAddressItems(
                  message.getRecipients(Message.RecipientType.CC),
                  typeRoot,
                  connectorOptions.getAddressFormat()));
          break;
        case "BCC":
          row.setField(
              i,
              mapAddressItems(
                  message.getRecipients(Message.RecipientType.BCC),
                  typeRoot,
                  connectorOptions.getAddressFormat()));
          break;
        case "RECIPIENTS":
          row.setField(
              i, mapAddressItems(message.getAllRecipients(), connectorOptions.getAddressFormat()));
          break;
        case "REPLYTO":
          row.setField(
              i,
              mapAddressItems(message.getReplyTo(), typeRoot, connectorOptions.getAddressFormat()));
          break;
        case "HEADERS":
          row.setField(i, mapHeaders(message.getAllHeaders()));
          break;
        case "FROM":
          row.setField(
              i, mapAddressItems(message.getFrom(), typeRoot, connectorOptions.getAddressFormat()));
          break;
        case "BYTES":
          row.setField(i, message.getSize());
          break;
        case "CONTENTTYPE":
          row.setField(i, StringData.fromString(message.getContentType()));
          break;
        case "CONTENT":
          row.setField(i, StringData.fromString(getMessageContent(message)));
          break;
        case "SEEN":
          row.setField(i, message.getFlags().contains(Flags.Flag.SEEN));
          break;
        case "DRAFT":
          row.setField(i, message.getFlags().contains(Flags.Flag.DRAFT));
        case "ANSWERED":
          row.setField(i, message.getFlags().contains(Flags.Flag.ANSWERED));
      }
    }

    ctx.collect(row);
  }

  private FetchProfile getFetchProfile() {
    var fetchProfile = new FetchProfile();
    fetchProfile.add(FetchProfile.Item.ENVELOPE);

    if (fieldNames.contains("CONTENT_TYPE") || fieldNames.contains("CONTENT")) {
      fetchProfile.add(FetchProfile.Item.CONTENT_INFO);
    }

    if (fieldNames.contains("BYTES")) {
      fetchProfile.add(FetchProfile.Item.SIZE);
    }

    if (fieldNames.contains("SEEN")
        || fieldNames.contains("DRAFT")
        || fieldNames.contains("ANSWERED")) {
      fetchProfile.add(FetchProfile.Item.FLAGS);
    }

    return fetchProfile;
  }

  private void enterWaitLoop() throws Exception {
    if (connectorOptions.isIdle() && connectorOptions.isHeartbeat()) {
      idleHeartbeat = new IdleHeartbeatThread(folder, connectorOptions.getHeartbeatInterval());
      idleHeartbeat.setDaemon(true);
      idleHeartbeat.start();
    }

    long nextReadTimeMs = System.currentTimeMillis();
    while (running) {
      if (connectorOptions.isIdle() && supportsIdle) {
        try {
          folder.idle();
        } catch (MessagingException ignored) {
          supportsIdle = false;
          stopIdleHeartbeat();
        } catch (IllegalStateException ignored) {
          openFolder();
        }
      } else {
        try {
          // Trigger some IMAP request to force the server to send a notification
          folder.getMessageCount();
        } catch (MessagingException ignored) {
        }

        nextReadTimeMs += connectorOptions.getInterval().toMillis();
        Thread.sleep(Math.max(0, nextReadTimeMs - System.currentTimeMillis()));
      }
    }
  }

  private void openFolder() {
    try {
      if (!folder.isOpen()) {
        folder.open(Folder.READ_ONLY);
      }
    } catch (MessagingException e) {
      throw new ImapSourceException("Could not open folder " + folder.getName(), e);
    }
  }

  private void stopIdleHeartbeat() {
    if (idleHeartbeat != null && idleHeartbeat.isAlive()) {
      idleHeartbeat.interrupt();
    }
  }
}