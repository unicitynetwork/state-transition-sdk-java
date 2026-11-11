package org.unicitylabs.sdk.api.bft.trustbase.file;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseCacheKey;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseStore;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseStoreException;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseStoreException.Reason;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * The optional on-disk {@link TrustBaseStore}. It is not part of the core: nothing in the core
 * refers to it, and an application selects it explicitly (it needs {@code java.nio.file}, so an
 * Android application chooses a directory of its own and an executor that is not the UI thread).
 *
 * <p>One file per cache key in a caller-chosen directory, named exactly as the JavaScript and Rust
 * adapters name it: {@code utb-<sha256(key)>.cbor}, with the exclusive lock the directory
 * {@code utb-<sha256(key)>.lock}; the key is hashed, so a namespace never reaches the filesystem as
 * a path component. This makes the Java adapter coordinate with theirs over one directory.
 *
 * <ul>
 *   <li>Reads are bounded (the size is judged before the bytes are read, and the read itself is
 *   bounded) and take no lock. Replacement is atomic (a same-directory temporary file, flushed, then
 *   an atomic move), so a reader sees the old or the new complete bytes.
 *   <li>A compare-and-swap takes the exclusive lock by creating the lock directory (atomic).
 *   Contention returns {@link Reason#BUSY} at once: it never waits. A lock left by a crash is
 *   <b>never stolen</b>: it is an application-visible maintenance condition (see
 *   {@link #lockPath}), not permission to write unlocked.
 *   <li>There is no non-atomic fallback: a file system that cannot move atomically is a store
 *   failure. A failure to flush the directory after the move is not reported as a failed write (the
 *   bytes are already replaced; the next flush finds the same tip and writes nothing).
 * </ul>
 *
 * <p>All file work runs on the executor given at construction, never on the caller's thread; a
 * task that has started finishes its write and releases its lock even if the returned future is
 * cancelled. Portability: tested on the platform of the build; atomic replacement and directory
 * flushing depend on the file system, and an unsupported combination is reported, not papered over.
 */
public final class FileTrustBaseStore implements TrustBaseStore {

  /** Largest stored value a compare-and-swap rereads under the lock. */
  static final long MAX_CAS_READ = 64L << 20;

  private final Path dir;
  private final Executor executor;
  /** Test hook: runs after the temporary file is complete and before it is moved into place. */
  volatile Runnable beforeMove;
  /** Test hook: runs after the size of a stored file was judged and before it is read. */
  volatile Runnable afterSizeCheck;

  /**
   * Create a store in a directory.
   *
   * @param dir the directory, created with its parents on the first write when missing
   * @param executor where the file work runs: a bounded executor of the application
   */
  public FileTrustBaseStore(Path dir, Executor executor) {
    this.dir = Objects.requireNonNull(dir, "dir cannot be null");
    this.executor = Objects.requireNonNull(executor, "executor cannot be null");
  }

  private static String stem(String key) {
    return "utb-" + HexConverter.encode(
        TrustBaseCacheKey.sha256Of(key.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * The file holding a key.
   *
   * @param key the cache key
   * @return the path, {@code utb-<sha256(key)>.cbor} in the directory
   */
  public Path filePath(String key) {
    return this.dir.resolve(stem(key) + ".cbor");
  }

  /**
   * The lock directory of a key. If it exists while no writer is running (a crash), the application
   * decides to remove it; the store never does.
   *
   * @param key the cache key
   * @return the path, {@code utb-<sha256(key)>.lock} in the directory
   */
  public Path lockPath(String key) {
    return this.dir.resolve(stem(key) + ".lock");
  }

  @Override
  public CompletableFuture<Optional<Stored>> read(String key, int maxBytes) {
    Objects.requireNonNull(key, "key cannot be null");
    return submit(() -> {
      byte[] bytes = readFile(filePath(key), maxBytes);
      return bytes == null ? Optional.<Stored>empty()
          : Optional.of(new Stored(bytes, TrustBaseCacheKey.revision(bytes)));
    });
  }

  @Override
  public CompletableFuture<CasOutcome> compareAndSwap(String key, String expectedRevision,
      byte[] bytes) {
    Objects.requireNonNull(key, "key cannot be null");
    Objects.requireNonNull(bytes, "bytes cannot be null");
    byte[] copy = bytes.clone();
    return submit(() -> {
      Path lock = acquire(key);
      try {
        byte[] current = readFile(filePath(key), MAX_CAS_READ);
        String revision = current == null ? null : TrustBaseCacheKey.revision(current);
        if (!Objects.equals(revision, expectedRevision)) {
          return CasOutcome.CHANGED;
        }
        writeAtomic(key, copy);
        return CasOutcome.WRITTEN;
      } finally {
        try {
          Files.deleteIfExists(lock);
        } catch (IOException ignored) {
          // the lock directory stays and the next writer sees Busy; never silently stolen
        }
      }
    });
  }

  private <T> CompletableFuture<T> submit(IoTask<T> task) {
    CompletableFuture<T> future = new CompletableFuture<>();
    try {
      this.executor.execute(() -> {
        if (future.isCancelled()) {
          return; // cancelled before it started: nothing was done
        }
        try {
          future.complete(task.run());
        } catch (TrustBaseStoreException e) {
          future.completeExceptionally(e);
        } catch (IOException e) {
          future.completeExceptionally(map(e));
        } catch (RuntimeException e) {
          future.completeExceptionally(e);
        }
      });
    } catch (RuntimeException e) {
      future.completeExceptionally(e);
    }
    return future;
  }

  private interface IoTask<T> {
    T run() throws IOException;
  }

  private byte[] readFile(Path path, long max) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      if (channel.size() > max) {
        throw new TrustBaseStoreException(Reason.OVERSIZE, "The stored value exceeds " + max
            + " bytes.");
      }
      Runnable hook = this.afterSizeCheck;
      if (hook != null) {
        hook.run();
      }
      // the file may grow after the check: the read itself is bounded too
      ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(channel.size(), max));
      ByteBuffer buffer = ByteBuffer.allocate(8192);
      long total = 0;
      for (;;) {
        ((java.nio.Buffer) buffer).clear(); // not ByteBuffer.clear(): absent before Java 9
        int read = channel.read(buffer);
        if (read < 0) {
          break;
        }
        total += read;
        if (total > max) {
          throw new TrustBaseStoreException(Reason.OVERSIZE, "The stored value exceeds " + max
              + " bytes.");
        }
        out.write(buffer.array(), 0, read);
      }
      return out.toByteArray();
    } catch (NoSuchFileException e) {
      return null;
    }
  }

  private Path acquire(String key) throws IOException {
    Files.createDirectories(this.dir);
    Path lock = lockPath(key);
    try {
      Files.createDirectory(lock);
    } catch (FileAlreadyExistsException e) {
      throw new TrustBaseStoreException(Reason.BUSY, "Another writer holds the store.", e);
    }
    return lock;
  }

  /** Remove this key's temporary files left by a crashed writer, under the exclusive lock. */
  private void removeStaleTemps(String key) {
    String prefix = stem(key) + ".tmp-";
    try (DirectoryStream<Path> files = Files.newDirectoryStream(this.dir, prefix + "*")) {
      for (Path file : files) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException ignored) {
          // best effort: an orphan is ignored by readers
        }
      }
    } catch (IOException ignored) {
      // best effort
    }
  }

  private void writeAtomic(String key, byte[] bytes) throws IOException {
    removeStaleTemps(key);
    Path target = filePath(key);
    Path temp = this.dir.resolve(stem(key) + ".tmp-" + UUID.randomUUID());
    boolean moved = false;
    try {
      try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      Runnable hook = this.beforeMove;
      if (hook != null) {
        hook.run();
      }
      try {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        // no non-atomic fallback: the application chooses another directory
        throw new TrustBaseStoreException(Reason.IO,
            "The file system cannot replace a file atomically.", e);
      }
      moved = true;
    } finally {
      if (!moved) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
          // best effort
        }
      }
    }
    // Make the move durable where the platform allows a directory flush. The bytes are already
    // replaced, so a failure here is not reported as a failed write.
    try (FileChannel directory = FileChannel.open(this.dir, StandardOpenOption.READ)) {
      directory.force(true);
    } catch (IOException | RuntimeException ignored) {
      // unsupported on this platform (for example Windows)
    }
  }

  /** The typed failure of an I/O error: permission, out of space or quota, anything else. */
  static TrustBaseStoreException map(IOException e) {
    if (e instanceof AccessDeniedException) {
      return new TrustBaseStoreException(Reason.PERMISSION, "Permission denied.", e);
    }
    String text = (e.getMessage() == null ? "" : e.getMessage()).toLowerCase(Locale.ROOT);
    if (e instanceof FileSystemException && ((FileSystemException) e).getReason() != null) {
      text += " " + ((FileSystemException) e).getReason().toLowerCase(Locale.ROOT);
    }
    if (text.contains("permission denied") || text.contains("operation not permitted")) {
      return new TrustBaseStoreException(Reason.PERMISSION, "Permission denied.", e);
    }
    if (text.contains("no space left") || text.contains("disk quota")
        || text.contains("not enough space") || text.contains("disk is full")) {
      return new TrustBaseStoreException(Reason.QUOTA, "Out of space or quota.", e);
    }
    return new TrustBaseStoreException(Reason.IO, String.valueOf(e.getMessage()), e);
  }
}
