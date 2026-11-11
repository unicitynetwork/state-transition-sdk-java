package org.unicitylabs.sdk.api.bft.trustbase.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseCacheKey;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseStore;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseStoreException;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseStoreException.Reason;

/** The file adapter: names, atomicity, locking, budgets, failures. */
class FileTrustBaseStoreTest {

  private static final String KEY = "utb/v1/7/ab88deef/test";
  private static final byte[] A = {1, 2, 3};
  private static final byte[] B = {4, 5, 6, 7};

  @TempDir
  Path tmp;

  private FileTrustBaseStore store(Path dir) {
    return new FileTrustBaseStore(dir, Runnable::run);
  }

  private static <T> T get(CompletableFuture<T> future) {
    try {
      return future.get(10, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw new IllegalStateException(e.getCause());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Reason reasonOf(Runnable action) {
    return assertThrows(TrustBaseStoreException.class, action::run).getReason();
  }

  private static List<String> names(Path dir) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
    }
  }

  @Test
  void namesFilesAndLocksAsTheOtherAdaptersDo() {
    FileTrustBaseStore s = store(this.tmp);
    String stem = "utb-" + org.unicitylabs.sdk.util.HexConverter.encode(
        TrustBaseCacheKey.sha256Of(KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertEquals(this.tmp.resolve(stem + ".cbor"), s.filePath(KEY));
    assertEquals(this.tmp.resolve(stem + ".lock"), s.lockPath(KEY));
    // the known answer shared with the JS and Rust adapters: sha256 of the empty key
    assertEquals("utb-e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855.cbor",
        s.filePath("").getFileName().toString());
    // the namespace never reaches the filesystem
    assertFalse(s.filePath("utb/v1/7/ab/secret-namespace").toString().contains("secret"));
  }

  @Test
  void readsAbsentAsEmptyWritesFromAbsentAndRefusesAStaleRevision() {
    FileTrustBaseStore s = store(this.tmp);
    assertFalse(get(s.read(KEY, 100)).isPresent());
    assertEquals(TrustBaseStore.CasOutcome.CHANGED, get(s.compareAndSwap(KEY, "x", A)),
        "a revision for an absent key is stale");
    assertEquals(TrustBaseStore.CasOutcome.WRITTEN, get(s.compareAndSwap(KEY, null, A)));
    TrustBaseStore.Stored first = get(s.read(KEY, 100)).get();
    assertArrayEquals(A, first.getBytes());
    assertEquals(TrustBaseCacheKey.revision(A), first.getRevision());

    assertEquals(TrustBaseStore.CasOutcome.CHANGED, get(s.compareAndSwap(KEY, null, B)),
        "absent expected, present found");
    assertEquals(TrustBaseStore.CasOutcome.WRITTEN,
        get(s.compareAndSwap(KEY, first.getRevision(), B)));
    assertEquals(TrustBaseStore.CasOutcome.CHANGED,
        get(s.compareAndSwap(KEY, first.getRevision(), A)), "the old revision is stale now");
    assertArrayEquals(B, get(s.read(KEY, 100)).get().getBytes());
  }

  @Test
  void createsTheDirectoryWithItsParentsOnTheFirstWrite() {
    Path deep = this.tmp.resolve("a").resolve("b").resolve("c");
    FileTrustBaseStore s = store(deep);
    assertFalse(get(s.read(KEY, 10)).isPresent(), "an absent directory reads as absent");
    assertEquals(TrustBaseStore.CasOutcome.WRITTEN, get(s.compareAndSwap(KEY, null, A)));
    assertTrue(Files.isRegularFile(s.filePath(KEY)));
  }

  @Test
  void aStorePathThatIsAFileIsAnIoErrorNotABusyLock() throws IOException {
    Path file = this.tmp.resolve("not-a-directory");
    Files.write(file, A);
    FileTrustBaseStore s = store(file);
    assertEquals(Reason.IO, reasonOf(() -> get(s.compareAndSwap(KEY, null, B))));
  }

  @Test
  void leavesNoLockAndNoTemporaryFileBehind() throws IOException {
    FileTrustBaseStore s = store(this.tmp);
    get(s.compareAndSwap(KEY, null, A));
    get(s.compareAndSwap(KEY, TrustBaseCacheKey.revision(A), B));
    assertEquals(List.of(s.filePath(KEY).getFileName().toString()), names(this.tmp));
  }

  @Test
  void judgesTheSizeBeforeReadingAndRefusesAnOversizeFile() throws IOException {
    FileTrustBaseStore s = store(this.tmp);
    get(s.compareAndSwap(KEY, null, new byte[10]));
    assertEquals(10, get(s.read(KEY, 10)).get().getBytes().length, "at the budget");
    assertEquals(Reason.OVERSIZE, reasonOf(() -> get(s.read(KEY, 9))));
  }

  @Test
  void theSizeIsJudgedBeforeAnyByteIsReadAndTheReadItselfIsBounded() throws IOException {
    FileTrustBaseStore s = store(this.tmp);
    get(s.compareAndSwap(KEY, null, new byte[10]));
    java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
    s.afterSizeCheck = reads::incrementAndGet;
    assertEquals(Reason.OVERSIZE, reasonOf(() -> get(s.read(KEY, 9))));
    assertEquals(0, reads.get(), "an oversize file is refused before it is read");

    // a file that grows after the size check is caught by the bounded read
    s.afterSizeCheck = () -> {
      try {
        Files.write(s.filePath(KEY), new byte[15]);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    };
    assertEquals(Reason.OVERSIZE, reasonOf(() -> get(s.read(KEY, 10))));
  }

  @Test
  void removesTemporaryFilesACrashedWriterLeftUnderTheLock() throws IOException {
    FileTrustBaseStore s = store(this.tmp);
    String stem = s.filePath(KEY).getFileName().toString().replace(".cbor", "");
    Path orphan = this.tmp.resolve(stem + ".tmp-orphan");
    Path other = this.tmp.resolve("unrelated.tmp-orphan");
    Files.write(orphan, A);
    Files.write(other, A);
    assertFalse(get(s.read(KEY, 10)).isPresent(), "an orphan is ignored by readers");
    get(s.compareAndSwap(KEY, null, B));
    assertFalse(Files.exists(orphan));
    assertTrue(Files.exists(other), "only this key's temporaries are removed");
  }

  @Test
  void aHeldLockIsBusyAtOnceAndIsNeverStolen() throws IOException {
    FileTrustBaseStore s = store(this.tmp);
    get(s.compareAndSwap(KEY, null, A));
    Files.createDirectory(s.lockPath(KEY));
    long started = System.nanoTime();
    assertEquals(Reason.BUSY,
        reasonOf(() -> get(s.compareAndSwap(KEY, TrustBaseCacheKey.revision(A), B))));
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000, "it never waits");
    assertTrue(Files.isDirectory(s.lockPath(KEY)), "and the lock is not stolen");
    assertArrayEquals(A, get(s.read(KEY, 10)).get().getBytes(), "nothing was written");
    // reads do not take the lock
    assertTrue(get(s.read(KEY, 10)).isPresent());
  }

  @Test
  void exactlyOneOfManyConcurrentWritersFromOneRevisionWins() throws Exception {
    FileTrustBaseStore seed = store(this.tmp);
    get(seed.compareAndSwap(KEY, null, A));
    String revision = TrustBaseCacheKey.revision(A);
    int writers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    try {
      FileTrustBaseStore s = new FileTrustBaseStore(this.tmp, pool);
      CountDownLatch go = new CountDownLatch(1);
      List<CompletableFuture<Object>> results = new ArrayList<>();
      for (int i = 0; i < writers; i++) {
        byte[] mine = {(byte) (10 + i)};
        results.add(CompletableFuture.supplyAsync(() -> {
          try {
            go.await();
            return (Object) s.compareAndSwap(KEY, revision, mine).get();
          } catch (ExecutionException e) {
            return e.getCause();
          } catch (InterruptedException e) {
            return e;
          }
        }));
      }
      go.countDown();
      int written = 0;
      for (CompletableFuture<Object> r : results) {
        Object outcome = r.get(20, TimeUnit.SECONDS);
        if (outcome == TrustBaseStore.CasOutcome.WRITTEN) {
          written++;
        } else {
          assertTrue(outcome == TrustBaseStore.CasOutcome.CHANGED
              || (outcome instanceof TrustBaseStoreException
              && ((TrustBaseStoreException) outcome).getReason() == Reason.BUSY), String.valueOf(outcome));
        }
      }
      assertEquals(1, written);
      assertEquals(1, get(s.read(KEY, 10)).get().getBytes().length);
      assertEquals(List.of(s.filePath(KEY).getFileName().toString()), names(this.tmp));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void keepsTheOldBytesWhenTheWriteIsInterruptedBeforeTheMoveAndReleasesTheLock()
      throws IOException {
    FileTrustBaseStore s = store(this.tmp);
    get(s.compareAndSwap(KEY, null, A));
    s.beforeMove = () -> {
      throw new IllegalStateException("crash");
    };
    assertThrows(IllegalStateException.class,
        () -> get(s.compareAndSwap(KEY, TrustBaseCacheKey.revision(A), B)));
    s.beforeMove = null;
    assertArrayEquals(A, get(s.read(KEY, 10)).get().getBytes());
    assertEquals(List.of(s.filePath(KEY).getFileName().toString()), names(this.tmp),
        "neither a temporary file nor the lock is left");
    assertEquals(TrustBaseStore.CasOutcome.WRITTEN,
        get(s.compareAndSwap(KEY, TrustBaseCacheKey.revision(A), B)), "and the store works again");
  }

  @Test
  void aCancelledFutureDoesNotReleaseTheLockBeforeTheWriteFinishes() throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      FileTrustBaseStore s = new FileTrustBaseStore(this.tmp, pool);
      CountDownLatch inside = new CountDownLatch(1);
      CountDownLatch proceed = new CountDownLatch(1);
      s.beforeMove = () -> {
        inside.countDown();
        try {
          proceed.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      };
      CompletableFuture<TrustBaseStore.CasOutcome> write = s.compareAndSwap(KEY, null, A);
      assertTrue(inside.await(10, TimeUnit.SECONDS));
      write.cancel(true);
      assertTrue(Files.isDirectory(s.lockPath(KEY)), "the lock is held while the write runs");
      assertEquals(Reason.BUSY, reasonOf(() -> get(store(this.tmp).compareAndSwap(KEY, null, B))));
      s.beforeMove = null;
      proceed.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
      assertArrayEquals(A, get(store(this.tmp).read(KEY, 10)).get().getBytes(),
          "the started write completed");
      assertFalse(Files.exists(s.lockPath(KEY)), "and released the lock");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aDeniedDirectoryIsPermissionAndNeverFallsBackToAnUnlockedWrite() throws IOException {
    assumeTrue(Files.getFileStore(this.tmp).supportsFileAttributeView("posix"));
    Path locked = this.tmp.resolve("locked");
    Files.createDirectory(locked);
    FileTrustBaseStore s = store(locked);
    get(s.compareAndSwap(KEY, null, A));
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      assumeTrue(!Files.isWritable(locked), "running as a user the mode does not bind");
      assertEquals(Reason.PERMISSION,
          reasonOf(() -> get(s.compareAndSwap(KEY, TrustBaseCacheKey.revision(A), B))));
      assertArrayEquals(A, get(s.read(KEY, 10)).get().getBytes(), "nothing was written");
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  @Test
  void mapsAFullDiskToQuotaAndADeniedAccessToPermission() {
    assertEquals(Reason.QUOTA, FileTrustBaseStore.map(
        new FileSystemException("f", null, "No space left on device")).getReason());
    assertEquals(Reason.QUOTA, FileTrustBaseStore.map(
        new FileSystemException("f", null, "Disk quota exceeded")).getReason());
    assertEquals(Reason.PERMISSION, FileTrustBaseStore.map(
        new java.nio.file.AccessDeniedException("f")).getReason());
    assertEquals(Reason.PERMISSION, FileTrustBaseStore.map(
        new FileSystemException("f", null, "Permission denied")).getReason());
    TrustBaseStoreException io = FileTrustBaseStore.map(new IOException("boom"));
    assertEquals(Reason.IO, io.getReason());
    assertNotNull(io.getCause());
    assertNull(null);
    assertInstanceOf(IOException.class, io.getCause());
  }
}
