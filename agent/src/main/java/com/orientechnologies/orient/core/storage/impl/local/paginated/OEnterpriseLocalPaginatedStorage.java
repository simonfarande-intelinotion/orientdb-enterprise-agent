/*
 * Copyright 2015 OrientDB LTD (info(at)orientdb.com)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *   For more information: http://www.orientdb.com
 */

package com.orientechnologies.orient.core.storage.impl.local.paginated;

import com.orientechnologies.agent.Utils;
import com.orientechnologies.common.collection.closabledictionary.OClosableLinkedContainer;
import com.orientechnologies.common.concur.lock.OInterruptedException;
import com.orientechnologies.common.concur.lock.OModificationOperationProhibitedException;
import com.orientechnologies.common.exception.OErrorCode;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.io.OFileUtils;
import com.orientechnologies.common.io.OIOUtils;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.serialization.types.OByteSerializer;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.common.util.OCallable;
import com.orientechnologies.common.util.OPair;
import com.orientechnologies.common.util.OQuarto;
import com.orientechnologies.orient.core.config.OContextConfiguration;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.record.ORecordOperation;
import com.orientechnologies.orient.core.exception.*;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.storage.ORawBuffer;
import com.orientechnologies.orient.core.storage.ORecordCallback;
import com.orientechnologies.orient.core.storage.OStorageOperationResult;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.cache.OReadCache;
import com.orientechnologies.orient.core.storage.config.OClusterBasedStorageConfiguration;
import com.orientechnologies.orient.core.storage.disk.OLocalPaginatedStorage;
import com.orientechnologies.orient.core.storage.fs.OFile;
import com.orientechnologies.orient.core.storage.impl.local.OStorageConfigurationSegment;
import com.orientechnologies.orient.core.storage.impl.local.paginated.base.ODurablePage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.MetaDataRecord;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OLogSequenceNumber;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OWriteAheadLog;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import com.orientechnologies.orient.core.tx.OTransactionInternal;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class OEnterpriseLocalPaginatedStorage extends OLocalPaginatedStorage {

  private static final String INCREMENTAL_BACKUP_LOCK = "backup.ibl";

  private static final String ALGORITHM_NAME = "AES";
  private static final String TRANSFORMATION = "AES/CTR/NoPadding";

  private static final ThreadLocal<Cipher> CIPHER =
      ThreadLocal.withInitial(OEnterpriseLocalPaginatedStorage::getCipherInstance);

  private static final String IBU_EXTENSION_V3 = ".ibu3";
  private static final int INCREMENTAL_BACKUP_VERSION = 423;
  private static final String CONF_ENTRY_NAME = "database.ocf";
  private static final String INCREMENTAL_BACKUP_DATEFORMAT = "yyyy-MM-dd-HH-mm-ss";
  private static final String CONF_UTF_8_ENTRY_NAME = "database_utf8.ocf";

  private volatile int backupRunning = 0;

  private static final String ENCRYPTION_IV = "encryption.iv";

  private final List<OEnterpriseStorageOperationListener> listeners = new CopyOnWriteArrayList<>();

  public OEnterpriseLocalPaginatedStorage(
      String name,
      String filePath,
      String mode,
      int id,
      OReadCache readCache,
      OClosableLinkedContainer<Long, OFile> files,
      long walMaxSize,
      long doubleWriteLogMaxSize) {
    super(name, filePath, mode, id, readCache, files, walMaxSize, doubleWriteLogMaxSize);
    OLogManager.instance().info(this, "Enterprise storage installed correctly.");
  }

  @Override
  public String incrementalBackup(final String backupDirectory, OCallable<Void, Void> started) {
    return incrementalBackup(new File(backupDirectory), started);
  }

  @Override
  public boolean supportIncremental() {
    return true;
  }

  @Override
  public void fullIncrementalBackup(final OutputStream stream)
      throws UnsupportedOperationException {
    try {
      incrementalBackup(stream, null, false);
    } catch (IOException e) {
      throw OException.wrapException(new OStorageException("Error during incremental backup"), e);
    }
  }

  public boolean isLastBackupCompatibleWithUUID(final File backupDirectory) throws IOException {
    if (!backupDirectory.exists()) {
      return true;
    }

    final Path fileLockPath = backupDirectory.toPath().resolve(INCREMENTAL_BACKUP_LOCK);
    try (FileChannel lockChannel =
        FileChannel.open(fileLockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      try (final FileLock fileLock = lockChannel.lock()) {
        final String[] files = fetchIBUFiles(backupDirectory);
        if (files.length > 0) {
          UUID backupUUID =
              extractDbInstanceUUID(backupDirectory, files[0], configuration.getCharset());
          try {
            checkDatabaseInstanceId(backupUUID);
          } catch (OInvalidInstanceIdException ex) {
            return false;
          }
        }
      } catch (final OverlappingFileLockException e) {
        OLogManager.instance()
            .error(
                this,
                "Another incremental backup process is in progress, please wait till it will be finished",
                null);
      } catch (final IOException e) {
        throw OException.wrapException(new OStorageException("Error during incremental backup"), e);
      }

      try {
        Files.deleteIfExists(fileLockPath);
      } catch (IOException e) {
        throw OException.wrapException(new OStorageException("Error during incremental backup"), e);
      }
    }
    return true;
  }

  private String incrementalBackup(
      final File backupDirectory, final OCallable<Void, Void> started) {
    String fileName = "";

    if (!backupDirectory.exists()) {
      if (!backupDirectory.mkdirs()) {
        throw new OStorageException(
            "Backup directory "
                + backupDirectory.getAbsolutePath()
                + " does not exist and can not be created");
      }
    }
    checkNoBackupInStorageDir(backupDirectory);

    final Path fileLockPath = backupDirectory.toPath().resolve(INCREMENTAL_BACKUP_LOCK);
    try (final FileChannel lockChannel =
        FileChannel.open(fileLockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      try (@SuppressWarnings("unused")
          final FileLock fileLock = lockChannel.lock()) {
        RandomAccessFile rndIBUFile = null;
        try {
          final String[] files = fetchIBUFiles(backupDirectory);

          final OLogSequenceNumber lastLsn;
          long nextIndex;
          final UUID backupUUID;

          if (files.length == 0) {
            lastLsn = null;
            nextIndex = 0;
            backupUUID = null;
          } else {
            lastLsn = extractIBULsn(backupDirectory, files[files.length - 1]);
            nextIndex = extractIndexFromIBUFile(backupDirectory, files[files.length - 1]) + 1;
            backupUUID =
                extractDbInstanceUUID(backupDirectory, files[0], configuration.getCharset());
            checkDatabaseInstanceId(backupUUID);
          }

          final SimpleDateFormat dateFormat = new SimpleDateFormat(INCREMENTAL_BACKUP_DATEFORMAT);
          if (lastLsn != null) {
            fileName =
                getName()
                    + "_"
                    + dateFormat.format(new Date())
                    + "_"
                    + nextIndex
                    + IBU_EXTENSION_V3;
          } else {
            fileName =
                getName()
                    + "_"
                    + dateFormat.format(new Date())
                    + "_"
                    + nextIndex
                    + "_full"
                    + IBU_EXTENSION_V3;
          }

          final File ibuFile = new File(backupDirectory, fileName);

          if (started != null) started.call(null);
          rndIBUFile = new RandomAccessFile(ibuFile, "rw");
          try {
            final FileChannel ibuChannel = rndIBUFile.getChannel();

            final ByteBuffer versionBuffer = ByteBuffer.allocate(OIntegerSerializer.INT_SIZE);
            versionBuffer.putInt(INCREMENTAL_BACKUP_VERSION);
            versionBuffer.rewind();

            OIOUtils.writeByteBuffer(versionBuffer, ibuChannel, 0);

            ibuChannel.position(
                2 * OIntegerSerializer.INT_SIZE
                    + 2 * OLongSerializer.LONG_SIZE
                    + OByteSerializer.BYTE_SIZE);

            OutputStream stream = Channels.newOutputStream(ibuChannel);
            OLogSequenceNumber maxLsn;
            try {
              maxLsn = incrementalBackup(stream, lastLsn, true);
              final ByteBuffer dataBuffer =
                  ByteBuffer.allocate(
                      OIntegerSerializer.INT_SIZE
                          + 2 * OLongSerializer.LONG_SIZE
                          + OByteSerializer.BYTE_SIZE);

              dataBuffer.putLong(nextIndex);
              dataBuffer.putLong(maxLsn.getSegment());
              dataBuffer.putInt(maxLsn.getPosition());

              if (lastLsn == null) dataBuffer.put((byte) 1);
              else dataBuffer.put((byte) 0);

              dataBuffer.rewind();

              ibuChannel.position(OIntegerSerializer.INT_SIZE);
              ibuChannel.write(dataBuffer);

            } finally {
              Utils.safeClose(this, stream);
            }
          } catch (RuntimeException e) {
            rndIBUFile.close();

            if (!ibuFile.delete()) {
              OLogManager.instance()
                  .error(
                      this, ibuFile.getAbsolutePath() + " is closed but can not be deleted", null);
            }

            throw e;
          }
        } catch (IOException e) {
          throw OException.wrapException(
              new OStorageException("Error during incremental backup"), e);
        } finally {
          try {
            if (rndIBUFile != null) rndIBUFile.close();
          } catch (IOException e) {
            OLogManager.instance().error(this, "Can not close %s file", e, fileName);
          }
        }
      }
    } catch (final OverlappingFileLockException e) {
      OLogManager.instance()
          .error(
              this,
              "Another incremental backup process is in progress, please wait till it will be finished",
              null);
    } catch (final IOException e) {
      throw OException.wrapException(new OStorageException("Error during incremental backup"), e);
    }

    try {
      Files.deleteIfExists(fileLockPath);
    } catch (IOException e) {
      throw OException.wrapException(new OStorageException("Error during incremental backup"), e);
    }

    return fileName;
  }

  private UUID extractDbInstanceUUID(File backupDirectory, String file, String charset)
      throws IOException {
    final File ibuFile = new File(backupDirectory, file);
    final RandomAccessFile rndIBUFile;
    try {
      rndIBUFile = new RandomAccessFile(ibuFile, "r");
    } catch (FileNotFoundException e) {
      throw OException.wrapException(new OStorageException("Backup file was not found"), e);
    }

    try {
      final FileChannel ibuChannel = rndIBUFile.getChannel();
      ibuChannel.position(3 * OLongSerializer.LONG_SIZE + 1);

      final InputStream inputStream = Channels.newInputStream(ibuChannel);
      final BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
      final ZipInputStream zipInputStream =
          new ZipInputStream(bufferedInputStream, Charset.forName(charset));

      ZipEntry zipEntry;
      while ((zipEntry = zipInputStream.getNextEntry()) != null) {
        if (zipEntry.getName().equals("database_instance.uuid")) {
          DataInputStream dis = new DataInputStream(zipInputStream);
          UUID uuid = UUID.fromString(dis.readUTF());
          return uuid;
        }
      }
    } finally {
      rndIBUFile.close();
    }
    return null;
  }

  private void checkNoBackupInStorageDir(final File backupDirectory) {
    if (getStoragePath() == null || backupDirectory == null) {
      return;
    }

    boolean invalid = false;
    try {
      final File storageDir = getStoragePath().toFile();
      if (backupDirectory.equals(storageDir)) {
        invalid = true;
      }
    } catch (final Exception e) {
    }
    if (invalid) {
      throw new OStorageException("Backup cannot be performed in the storage path");
    }
  }

  public void registerStorageListener(OEnterpriseStorageOperationListener listener) {
    this.listeners.add(listener);
  }

  public void unRegisterStorageListener(OEnterpriseStorageOperationListener listener) {
    this.listeners.remove(listener);
  }

  private String[] fetchIBUFiles(final File backupDirectory) throws IOException {
    final String[] files =
        backupDirectory.list(
            (dir, name) ->
                new File(dir, name).length() > 0
                    && name.toLowerCase(configuration.getLocaleInstance())
                        .endsWith(IBU_EXTENSION_V3));

    if (files == null)
      throw new OStorageException(
          "Can not read list of backup files from directory " + backupDirectory.getAbsolutePath());

    final List<OPair<Long, String>> indexedFiles = new ArrayList<>(files.length);

    for (String file : files) {
      final long fileIndex = extractIndexFromIBUFile(backupDirectory, file);
      indexedFiles.add(new OPair<>(fileIndex, file));
    }

    Collections.sort(indexedFiles);

    final String[] sortedFiles = new String[files.length];

    int index = 0;
    for (OPair<Long, String> indexedFile : indexedFiles) {
      sortedFiles[index] = indexedFile.getValue();
      index++;
    }

    return sortedFiles;
  }

  private OLogSequenceNumber extractIBULsn(File backupDirectory, String file) {
    final File ibuFile = new File(backupDirectory, file);
    final RandomAccessFile rndIBUFile;
    try {
      rndIBUFile = new RandomAccessFile(ibuFile, "r");
    } catch (FileNotFoundException e) {
      throw OException.wrapException(new OStorageException("Backup file was not found"), e);
    }

    try {
      try {
        final FileChannel ibuChannel = rndIBUFile.getChannel();
        ibuChannel.position(OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE);

        ByteBuffer lsnData =
            ByteBuffer.allocate(OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE);
        ibuChannel.read(lsnData);
        lsnData.rewind();

        final long segment = lsnData.getLong();
        final int position = lsnData.getInt();

        return new OLogSequenceNumber(segment, position);
      } finally {
        rndIBUFile.close();
      }
    } catch (IOException e) {
      throw OException.wrapException(new OStorageException("Error during read of backup file"), e);
    } finally {
      try {
        rndIBUFile.close();
      } catch (IOException e) {
        OLogManager.instance().error(this, "Error during read of backup file", e);
      }
    }
  }

  private long extractIndexFromIBUFile(final File backupDirectory, final String fileName)
      throws IOException {
    final File file = new File(backupDirectory, fileName);

    try (final RandomAccessFile rndFile = new RandomAccessFile(file, "r")) {
      rndFile.seek(OIntegerSerializer.INT_SIZE);
      return validateLongIndex(rndFile.readLong());
    }
  }

  private long validateLongIndex(final long index) {
    return index < 0 ? 0 : Math.abs(index);
  }

  private OLogSequenceNumber incrementalBackup(
      final OutputStream stream, final OLogSequenceNumber fromLsn, final boolean singleThread)
      throws IOException {
    OLogSequenceNumber lastLsn;

    checkOpennessAndMigration();

    if (singleThread && checkBackup()) {
      throw new OBackupInProgressException(
          "You are trying to start incremental backup but it is in progress now, please wait till it will be finished",
          getName(),
          OErrorCode.BACKUP_IN_PROGRESS);
    }
    startBackup();
    stateLock.readLock().lock();
    try {

      checkOpennessAndMigration();
      final long freezeId;

      if (!isWriteAllowedDuringIncrementalBackup())
        freezeId =
            atomicOperationsManager.freezeAtomicOperations(
                OModificationOperationProhibitedException.class, "Incremental backup in progress");
      else freezeId = -1;

      try {
        final BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(stream);
        try {
          final ZipOutputStream zipOutputStream =
              new ZipOutputStream(
                  bufferedOutputStream, Charset.forName(configuration.getCharset()));
          try {
            final long startSegment;
            final OLogSequenceNumber freezeLsn;

            if (fromLsn == null) {
              try {
                UUID databaseInstanceUUID = super.readDatabaseInstanceId();
                if (databaseInstanceUUID == null) {
                  atomicOperationsManager.executeInsideAtomicOperation(
                      null,
                      atomicOperation -> {
                        generateDatabaseInstanceId(atomicOperation);
                      });
                  databaseInstanceUUID = super.readDatabaseInstanceId();
                }
                final ZipEntry zipEntry = new ZipEntry("database_instance.uuid");

                zipOutputStream.putNextEntry(zipEntry);
                zipOutputStream.flush();
                DataOutputStream dos = new DataOutputStream(zipOutputStream);
                dos.writeUTF(databaseInstanceUUID.toString());
                dos.flush();
                //                dos.close();
              } finally {
                zipOutputStream.flush();
              }
            }

            final long newSegmentFreezeId =
                atomicOperationsManager.freezeAtomicOperations(null, null);
            try {
              final OLogSequenceNumber startLsn = writeAheadLog.end();

              if (startLsn != null) freezeLsn = startLsn;
              else freezeLsn = new OLogSequenceNumber(0, 0);

              writeAheadLog.addCutTillLimit(freezeLsn);

              writeAheadLog.appendNewSegment();
              startSegment = writeAheadLog.activeSegment();

              getLastMetadata()
                  .ifPresent(
                      metadata -> {
                        try {
                          writeAheadLog.log(new MetaDataRecord(metadata));
                        } catch (final IOException e) {
                          throw new IllegalStateException("Error during write of metadata", e);
                        }
                      });
            } finally {
              atomicOperationsManager.releaseAtomicOperations(newSegmentFreezeId);
            }

            try {
              backupIv(zipOutputStream);

              final byte[] encryptionIv = new byte[16];
              final SecureRandom secureRandom = new SecureRandom();
              secureRandom.nextBytes(encryptionIv);

              backupEncryptedIv(zipOutputStream, encryptionIv);

              final String aesKeyEncoded =
                  getConfiguration()
                      .getContextConfiguration()
                      .getValueAsString(OGlobalConfiguration.STORAGE_ENCRYPTION_KEY);
              final byte[] aesKey =
                  aesKeyEncoded == null ? null : Base64.getDecoder().decode(aesKeyEncoded);

              if (aesKey != null
                  && aesKey.length != 16
                  && aesKey.length != 24
                  && aesKey.length != 32) {
                throw new OInvalidStorageEncryptionKeyException(
                    "Invalid length of the encryption key, provided size is " + aesKey.length);
              }

              lastLsn = backupPagesWithChanges(fromLsn, zipOutputStream, encryptionIv, aesKey);
              final OLogSequenceNumber lastWALLsn =
                  copyWALToIncrementalBackup(zipOutputStream, startSegment);

              if (lastWALLsn != null && (lastLsn == null || lastWALLsn.compareTo(lastLsn) > 0)) {
                lastLsn = lastWALLsn;
              }
            } finally {
              writeAheadLog.removeCutTillLimit(freezeLsn);
            }
          } finally {
            try {
              zipOutputStream.flush();
            } catch (IOException e) {
              OLogManager.instance().warn(this, "Failed to flush resource " + zipOutputStream);
            }
          }
        } finally {
          try {
            bufferedOutputStream.flush();
          } catch (IOException e) {
            OLogManager.instance().warn(this, "Failed to flush resource " + bufferedOutputStream);
          }
        }
      } finally {
        if (!isWriteAllowedDuringIncrementalBackup())
          atomicOperationsManager.releaseAtomicOperations(freezeId);
      }
    } finally {
      stateLock.readLock().unlock();
      endBackup();
    }

    return lastLsn;
  }

  private static void doEncryptionDecryption(
      final int mode,
      final byte[] aesKey,
      final long pageIndex,
      final long fileId,
      final byte[] backUpPage,
      final byte[] encryptionIv) {
    try {
      final Cipher cipher = CIPHER.get();
      final SecretKey secretKey = new SecretKeySpec(aesKey, ALGORITHM_NAME);

      final byte[] updatedIv = new byte[16];
      for (int i = 0; i < OLongSerializer.LONG_SIZE; i++) {
        updatedIv[i] = (byte) (encryptionIv[i] ^ ((pageIndex >>> i) & 0xFF));
      }

      for (int i = 0; i < OLongSerializer.LONG_SIZE; i++) {
        updatedIv[i + OLongSerializer.LONG_SIZE] =
            (byte) (encryptionIv[i + OLongSerializer.LONG_SIZE] ^ ((fileId >>> i) & 0xFF));
      }

      cipher.init(mode, secretKey, new IvParameterSpec(updatedIv));

      final byte[] data =
          cipher.doFinal(
              backUpPage, OLongSerializer.LONG_SIZE, backUpPage.length - OLongSerializer.LONG_SIZE);
      System.arraycopy(
          data,
          0,
          backUpPage,
          OLongSerializer.LONG_SIZE,
          backUpPage.length - OLongSerializer.LONG_SIZE);
    } catch (InvalidKeyException e) {
      throw OException.wrapException(new OInvalidStorageEncryptionKeyException(e.getMessage()), e);
    } catch (InvalidAlgorithmParameterException e) {
      throw new IllegalArgumentException("Invalid IV.", e);
    } catch (IllegalBlockSizeException | BadPaddingException e) {
      throw new IllegalStateException("Unexpected exception during CRT encryption.", e);
    }
  }

  private void backupEncryptedIv(final ZipOutputStream zipOutputStream, final byte[] encryptionIv)
      throws IOException {
    final ZipEntry zipEntry = new ZipEntry(ENCRYPTION_IV);
    zipOutputStream.putNextEntry(zipEntry);

    zipOutputStream.write(encryptionIv);
    zipOutputStream.closeEntry();
  }

  private void backupIv(final ZipOutputStream zipOutputStream) throws IOException {
    final ZipEntry zipEntry = new ZipEntry(IV_NAME);
    zipOutputStream.putNextEntry(zipEntry);

    zipOutputStream.write(this.iv);
    zipOutputStream.closeEntry();
  }

  private byte[] restoreIv(final ZipInputStream zipInputStream) throws IOException {
    final byte[] iv = new byte[16];
    OIOUtils.readFully(zipInputStream, iv, 0, iv.length);

    return iv;
  }

  private OLogSequenceNumber backupPagesWithChanges(
      final OLogSequenceNumber changeLsn,
      final ZipOutputStream stream,
      final byte[] encryptionIv,
      final byte[] aesKey)
      throws IOException {
    OLogSequenceNumber lastLsn = changeLsn;

    final Map<String, Long> files = writeCache.files();
    final int pageSize = writeCache.pageSize();

    for (Map.Entry<String, Long> entry : files.entrySet()) {
      final String fileName = entry.getKey();

      long fileId = entry.getValue();
      fileId = writeCache.externalFileId(writeCache.internalFileId(fileId));

      final long filledUpTo = writeCache.getFilledUpTo(fileId);
      final ZipEntry zipEntry = new ZipEntry(fileName);

      stream.putNextEntry(zipEntry);

      final byte[] binaryFileId = new byte[OLongSerializer.LONG_SIZE];
      OLongSerializer.INSTANCE.serialize(fileId, binaryFileId, 0);
      stream.write(binaryFileId, 0, binaryFileId.length);

      for (int pageIndex = 0; pageIndex < filledUpTo; pageIndex++) {
        final OCacheEntry cacheEntry =
            readCache.silentLoadForRead(fileId, pageIndex, writeCache, true);
        cacheEntry.acquireSharedLock();
        try {
          final OLogSequenceNumber pageLsn =
              ODurablePage.getLogSequenceNumberFromPage(
                  cacheEntry.getCachePointer().getBufferDuplicate());

          if (changeLsn == null || pageLsn.compareTo(changeLsn) > 0) {

            final byte[] data = new byte[pageSize + OLongSerializer.LONG_SIZE];
            OLongSerializer.INSTANCE.serializeNative(pageIndex, data, 0);
            ODurablePage.getPageData(
                cacheEntry.getCachePointer().getBufferDuplicate(),
                data,
                OLongSerializer.LONG_SIZE,
                pageSize);

            if (aesKey != null) {
              doEncryptionDecryption(
                  Cipher.ENCRYPT_MODE, aesKey, fileId, pageIndex, data, encryptionIv);
            }

            stream.write(data);

            if (lastLsn == null || pageLsn.compareTo(lastLsn) > 0) {
              lastLsn = pageLsn;
            }
          }
        } finally {
          cacheEntry.releaseSharedLock();
          readCache.releaseFromRead(cacheEntry);
        }
      }

      stream.closeEntry();
    }

    return lastLsn;
  }

  public void restoreFromIncrementalBackup(final String filePath) {
    restoreFromIncrementalBackup(new File(filePath));
  }

  @Override
  public void restoreFullIncrementalBackup(final InputStream stream)
      throws UnsupportedOperationException {
    try {
      final String aesKeyEncoded =
          getConfiguration()
              .getContextConfiguration()
              .getValueAsString(OGlobalConfiguration.STORAGE_ENCRYPTION_KEY);
      final byte[] aesKey =
          aesKeyEncoded == null ? null : Base64.getDecoder().decode(aesKeyEncoded);

      if (aesKey != null && aesKey.length != 16 && aesKey.length != 24 && aesKey.length != 32) {
        throw new OInvalidStorageEncryptionKeyException(
            "Invalid length of the encryption key, provided size is " + aesKey.length);
      }

      final OQuarto<Locale, OContextConfiguration, String, Locale> quarto =
          preprocessingIncrementalRestore();
      final Locale serverLocale = quarto.one;
      final OContextConfiguration contextConfiguration = quarto.two;
      final String charset = quarto.three;
      final Locale locale = quarto.four;

      restoreFromIncrementalBackup(
          charset, serverLocale, locale, contextConfiguration, aesKey, stream, true);

      postProcessIncrementalRestore(contextConfiguration);
    } catch (IOException e) {
      throw OException.wrapException(
          new OStorageException("Error during restore from incremental backup"), e);
    }
  }

  private OQuarto<Locale, OContextConfiguration, String, Locale> preprocessingIncrementalRestore()
      throws IOException {
    final Locale serverLocale = configuration.getLocaleInstance();
    final OContextConfiguration contextConfiguration = configuration.getContextConfiguration();
    final String charset = configuration.getCharset();
    final Locale locale = configuration.getLocaleInstance();

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation -> {
          closeClusters(false);
          closeIndexes(atomicOperation, false);
          ((OClusterBasedStorageConfiguration) configuration).close(atomicOperation);
        });

    configuration = null;

    return new OQuarto<>(serverLocale, contextConfiguration, charset, locale);
  }

  private void restoreFromIncrementalBackup(final File backupDirectory) {
    if (!backupDirectory.exists()) {
      throw new OStorageException(
          "Directory which should contain incremental backup files (files with extension '"
              + IBU_EXTENSION_V3
              + "') is absent. It should be located at '"
              + backupDirectory.getAbsolutePath()
              + "'");
    }

    try {
      final String[] files = fetchIBUFiles(backupDirectory);
      if (files.length == 0) {
        throw new OStorageException(
            "Cannot find incremental backup files (files with extension '"
                + IBU_EXTENSION_V3
                + "') in directory '"
                + backupDirectory.getAbsolutePath()
                + "'");
      }

      stateLock.writeLock().lock();
      try {

        final String aesKeyEncoded =
            getConfiguration()
                .getContextConfiguration()
                .getValueAsString(OGlobalConfiguration.STORAGE_ENCRYPTION_KEY);
        final byte[] aesKey =
            aesKeyEncoded == null ? null : Base64.getDecoder().decode(aesKeyEncoded);

        if (aesKey != null && aesKey.length != 16 && aesKey.length != 24 && aesKey.length != 32) {
          throw new OInvalidStorageEncryptionKeyException(
              "Invalid length of the encryption key, provided size is " + aesKey.length);
        }

        final OQuarto<Locale, OContextConfiguration, String, Locale> quarto =
            preprocessingIncrementalRestore();
        final Locale serverLocale = quarto.one;
        final OContextConfiguration contextConfiguration = quarto.two;
        final String charset = quarto.three;
        final Locale locale = quarto.four;

        UUID restoreUUID = extractDbInstanceUUID(backupDirectory, files[0], charset);

        for (String file : files) {
          UUID fileUUID = extractDbInstanceUUID(backupDirectory, files[0], charset);
          if ((restoreUUID == null && fileUUID == null)
              || (restoreUUID != null && restoreUUID.equals(fileUUID))) {
            final File ibuFile = new File(backupDirectory, file);

            RandomAccessFile rndIBUFile = new RandomAccessFile(ibuFile, "rw");
            try {
              final FileChannel ibuChannel = rndIBUFile.getChannel();
              final ByteBuffer versionBuffer = ByteBuffer.allocate(OIntegerSerializer.INT_SIZE);
              OIOUtils.readByteBuffer(versionBuffer, ibuChannel);
              versionBuffer.rewind();

              final int backupVersion = versionBuffer.getInt();
              if (backupVersion != INCREMENTAL_BACKUP_VERSION) {
                throw new OStorageException(
                    "Invalid version of incremental backup version was provided. Expected "
                        + INCREMENTAL_BACKUP_VERSION
                        + " , provided "
                        + backupVersion);
              }

              ibuChannel.position(2 * OIntegerSerializer.INT_SIZE + 2 * OLongSerializer.LONG_SIZE);
              final ByteBuffer buffer = ByteBuffer.allocate(1);
              ibuChannel.read(buffer);
              buffer.rewind();

              final boolean fullBackup = buffer.get() == 1;

              final InputStream inputStream = Channels.newInputStream(ibuChannel);
              try {
                restoreFromIncrementalBackup(
                    charset,
                    serverLocale,
                    locale,
                    contextConfiguration,
                    aesKey,
                    inputStream,
                    fullBackup);
              } finally {
                Utils.safeClose(this, inputStream);
              }
            } finally {
              try {
                rndIBUFile.close();
              } catch (IOException e) {
                OLogManager.instance().warn(this, "Failed to close resource " + rndIBUFile);
              }
            }
          } else {
            OLogManager.instance()
                .warn(
                    this,
                    "Skipped file '"
                        + file
                        + "' is not a backup of the same database of previous backups");
          }

          postProcessIncrementalRestore(contextConfiguration);
        }
      } finally {
        stateLock.writeLock().unlock();
      }
    } catch (IOException e) {
      throw OException.wrapException(
          new OStorageException("Error during restore from incremental backup"), e);
    }
  }

  private void postProcessIncrementalRestore(OContextConfiguration contextConfiguration)
      throws IOException {
    if (OClusterBasedStorageConfiguration.exists(writeCache)) {
      configuration = new OClusterBasedStorageConfiguration(this);
      atomicOperationsManager.executeInsideAtomicOperation(
          null,
          atomicOperation ->
              ((OClusterBasedStorageConfiguration) configuration)
                  .load(contextConfiguration, atomicOperation));
    } else {
      if (Files.exists(getStoragePath().resolve("database.ocf"))) {
        final OStorageConfigurationSegment oldConfig = new OStorageConfigurationSegment(this);
        oldConfig.load(contextConfiguration);

        final OClusterBasedStorageConfiguration atomicConfiguration =
            new OClusterBasedStorageConfiguration(this);
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                atomicConfiguration.create(atomicOperation, contextConfiguration, oldConfig));
        configuration = atomicConfiguration;

        oldConfig.close();
        Files.deleteIfExists(getStoragePath().resolve("database.ocf"));
      }

      if (configuration == null) {
        configuration = new OClusterBasedStorageConfiguration(this);
        atomicOperationsManager.executeInsideAtomicOperation(
            null,
            atomicOperation ->
                ((OClusterBasedStorageConfiguration) configuration)
                    .load(contextConfiguration, atomicOperation));
      }
    }

    atomicOperationsManager.executeInsideAtomicOperation(null, this::openClusters);
    sbTreeCollectionManager.close();
    sbTreeCollectionManager.load();
    openIndexes();

    flushAllData();

    atomicOperationsManager.executeInsideAtomicOperation(
        null,
        atomicOperation2 -> {
          generateDatabaseInstanceId(atomicOperation2);
        });
  }

  private void restoreFromIncrementalBackup(
      final String charset,
      final Locale serverLocale,
      final Locale locale,
      final OContextConfiguration contextConfiguration,
      final byte[] aesKey,
      final InputStream inputStream,
      final boolean isFull)
      throws IOException {
    stateLock.writeLock().lock();
    try {

      final List<String> currentFiles = new ArrayList<>(writeCache.files().keySet());

      final BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
      final ZipInputStream zipInputStream =
          new ZipInputStream(bufferedInputStream, Charset.forName(charset));
      final int pageSize = writeCache.pageSize();

      ZipEntry zipEntry;
      OLogSequenceNumber maxLsn = null;

      List<String> processedFiles = new ArrayList<>();

      if (isFull) {
        final Map<String, Long> files = writeCache.files();
        for (Map.Entry<String, Long> entry : files.entrySet()) {
          final long fileId = writeCache.fileIdByName(entry.getKey());

          assert entry.getValue().equals(fileId);
          readCache.deleteFile(fileId, writeCache);
        }
      }

      final File walTempDir = createWalTempDirectory();

      byte[] encryptionIv = null;
      byte[] walIv = null;

      entryLoop:
      while ((zipEntry = zipInputStream.getNextEntry()) != null) {
        if (zipEntry.getName().equals(IV_NAME)) {
          walIv = restoreIv(zipInputStream);
          continue;
        }

        if (zipEntry.getName().equals(ENCRYPTION_IV)) {
          encryptionIv = restoreEncryptionIv(zipInputStream);
          continue;
        }

        if (zipEntry.getName().equals(CONF_ENTRY_NAME)) {
          replaceConfiguration(zipInputStream);

          continue;
        }

        if (zipEntry.getName().equalsIgnoreCase("database_instance.uuid")) {
          continue;
        }

        if (zipEntry.getName().equals(CONF_UTF_8_ENTRY_NAME)) {
          replaceConfiguration(zipInputStream);

          continue;
        }

        if (zipEntry
            .getName()
            .toLowerCase(serverLocale)
            .endsWith(CASDiskWriteAheadLog.WAL_SEGMENT_EXTENSION)) {
          final String walName = zipEntry.getName();
          final int segmentIndex =
              walName.lastIndexOf(
                  ".", walName.length() - CASDiskWriteAheadLog.WAL_SEGMENT_EXTENSION.length() - 1);
          final String storageName = getName();

          if (segmentIndex < 0) {
            throw new IllegalStateException("Can not find index of WAL segment");
          }

          addFileToDirectory(
              storageName + walName.substring(segmentIndex), zipInputStream, walTempDir);
          continue;
        }

        if (aesKey != null && encryptionIv == null) {
          throw new OSecurityException("IV can not be null if encryption key is provided");
        }

        final byte[] binaryFileId = new byte[OLongSerializer.LONG_SIZE];
        OIOUtils.readFully(zipInputStream, binaryFileId, 0, binaryFileId.length);

        final long expectedFileId = OLongSerializer.INSTANCE.deserialize(binaryFileId, 0);
        long fileId;

        if (!writeCache.exists(zipEntry.getName())) {
          fileId = readCache.addFile(zipEntry.getName(), expectedFileId, writeCache);
        } else {
          fileId = writeCache.fileIdByName(zipEntry.getName());
        }

        if (!writeCache.fileIdsAreEqual(expectedFileId, fileId))
          throw new OStorageException(
              "Can not restore database from backup because expected and actual file ids are not the same");

        while (true) {
          final byte[] data = new byte[pageSize + OLongSerializer.LONG_SIZE];

          int rb = 0;

          while (rb < data.length) {
            final int b = zipInputStream.read(data, rb, data.length - rb);

            if (b == -1) {
              if (rb > 0)
                throw new OStorageException("Can not read data from file " + zipEntry.getName());
              else {
                processedFiles.add(zipEntry.getName());
                continue entryLoop;
              }
            }

            rb += b;
          }

          final long pageIndex = OLongSerializer.INSTANCE.deserializeNative(data, 0);

          if (aesKey != null) {
            doEncryptionDecryption(
                Cipher.DECRYPT_MODE, aesKey, expectedFileId, pageIndex, data, encryptionIv);
          }

          OCacheEntry cacheEntry =
              readCache.loadForWrite(fileId, pageIndex, true, writeCache, true, null);

          if (cacheEntry == null) {
            do {
              if (cacheEntry != null) readCache.releaseFromWrite(cacheEntry, writeCache, true);

              cacheEntry = readCache.allocateNewPage(fileId, writeCache, null);
            } while (cacheEntry.getPageIndex() != pageIndex);
          }

          try {
            final ByteBuffer buffer = cacheEntry.getCachePointer().getBufferDuplicate();
            final OLogSequenceNumber backedUpPageLsn =
                ODurablePage.getLogSequenceNumber(OLongSerializer.LONG_SIZE, data);
            if (isFull) {
              buffer.position(0);
              buffer.put(data, OLongSerializer.LONG_SIZE, data.length - OLongSerializer.LONG_SIZE);

              if (maxLsn == null || maxLsn.compareTo(backedUpPageLsn) < 0) {
                maxLsn = backedUpPageLsn;
              }
            } else {
              final OLogSequenceNumber currentPageLsn =
                  ODurablePage.getLogSequenceNumberFromPage(buffer);
              if (backedUpPageLsn.compareTo(currentPageLsn) > 0) {
                buffer.position(0);
                buffer.put(
                    data, OLongSerializer.LONG_SIZE, data.length - OLongSerializer.LONG_SIZE);

                if (maxLsn == null || maxLsn.compareTo(backedUpPageLsn) < 0) {
                  maxLsn = backedUpPageLsn;
                }
              }
            }

          } finally {
            readCache.releaseFromWrite(cacheEntry, writeCache, true);
          }
        }
      }

      currentFiles.removeAll(processedFiles);

      for (String file : currentFiles) {
        if (writeCache.exists(file)) {
          final long fileId = writeCache.fileIdByName(file);
          readCache.deleteFile(fileId, writeCache);
        }
      }

      final OWriteAheadLog restoreLog =
          createWalFromIBUFiles(walTempDir, contextConfiguration, locale, walIv);

      if (restoreLog != null) {
        final OLogSequenceNumber beginLsn = restoreLog.begin();
        restoreFrom(restoreLog, beginLsn);
      }

      if (maxLsn != null && writeAheadLog != null) {
        writeAheadLog.moveLsnAfter(maxLsn);
      }

      OFileUtils.deleteRecursively(walTempDir);
    } finally {
      stateLock.writeLock().unlock();
    }
  }

  private byte[] restoreEncryptionIv(final ZipInputStream zipInputStream) throws IOException {
    final byte[] iv = new byte[16];
    int read = 0;
    while (read < iv.length) {
      final int localRead = zipInputStream.read(iv, read, iv.length - read);

      if (localRead < 0) {
        throw new OStorageException(
            "End of stream is reached but IV data were not completely read");
      }

      read += localRead;
    }

    return iv;
  }

  @Override
  public OStorageOperationResult<ORawBuffer> readRecord(
      ORecordId iRid,
      String iFetchPlan,
      boolean iIgnoreCache,
      boolean prefetchRecords,
      ORecordCallback<ORawBuffer> iCallback) {

    try {
      return super.readRecord(iRid, iFetchPlan, iIgnoreCache, prefetchRecords, iCallback);
    } finally {
      listeners.forEach(OEnterpriseStorageOperationListener::onRead);
    }
  }

  @Override
  public List<ORecordOperation> commit(OTransactionInternal clientTx, boolean allocated) {
    List<ORecordOperation> operations = super.commit(clientTx, allocated);
    listeners.forEach((l) -> l.onCommit(operations));
    return operations;
  }

  private void replaceConfiguration(ZipInputStream zipInputStream) throws IOException {
    byte[] buffer = new byte[1024];

    int rb = 0;
    while (true) {
      final int b = zipInputStream.read(buffer, rb, buffer.length - rb);

      if (b == -1) break;

      rb += b;

      if (rb == buffer.length) {
        byte[] oldBuffer = buffer;

        buffer = new byte[buffer.length << 1];
        System.arraycopy(oldBuffer, 0, buffer, 0, oldBuffer.length);
      }
    }
  }

  private static Cipher getCipherInstance() {
    try {
      return Cipher.getInstance(TRANSFORMATION);
    } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
      throw OException.wrapException(
          new OSecurityException("Implementation of encryption " + TRANSFORMATION + " is absent"),
          e);
    }
  }

  private synchronized void startBackup() {
    this.backupRunning += 1;
  }

  private synchronized void endBackup() {
    assert this.backupRunning > 0;
    this.backupRunning -= 1;
    if (this.backupRunning == 0) {
      this.notifyAll();
    }
  }

  private synchronized boolean checkBackup() {
    return this.backupRunning > 0;
  }

  private synchronized void waitBackup() {
    if (checkBackup()) {
      try {
        this.wait();
      } catch (InterruptedException e) {
        throw OException.wrapException(
            new OInterruptedException("Interrupted wait for backup to finish"), e);
      }
    }
  }

  @Override
  protected void checkBackupRunning() {
    waitBackup();
  }
}
