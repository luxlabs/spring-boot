/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import org.springframework.util.FileSystemUtils;
import org.springframework.util.ReflectionUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

/**
 * Tests for {@link ApplicationTemp}.
 *
 * @author Phillip Webb
 */
public class ApplicationTempTests {

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private String userDir;

	private File applicationTempDir;

	@Before
	public void isolateApplicationTemp() throws IOException {
		// The application temp location depends on 'user.dir': use one per test
		this.userDir = System.getProperty("user.dir");
		System.setProperty("user.dir", this.temp.newFolder("user-dir").getAbsolutePath());
	}

	@After
	public void restoreUserDir() throws IOException {
		System.setProperty("user.dir", this.userDir);
		if (this.applicationTempDir != null) {
			Path path = this.applicationTempDir.toPath();
			if (Files.isSymbolicLink(path)) {
				Files.delete(path);
			}
			else {
				setPermissions(path, "rwx------");
				FileSystemUtils.deleteRecursively(this.applicationTempDir);
			}
		}
	}

	@Test
	public void generatesConsistentTemp() throws Exception {
		ApplicationTemp t1 = new ApplicationTemp();
		ApplicationTemp t2 = new ApplicationTemp();
		assertThat(t1.getDir()).isNotNull();
		assertThat(t1.getDir()).isEqualTo(t2.getDir());
		this.applicationTempDir = t1.getDir();
	}

	@Test
	public void differentBasedOnUserDir() throws Exception {
		String userDir = System.getProperty("user.dir");
		File t1 = new ApplicationTemp().getDir();
		this.applicationTempDir = t1;
		File t2;
		try {
			System.setProperty("user.dir", "abc");
			t2 = new ApplicationTemp().getDir();
		}
		finally {
			System.setProperty("user.dir", userDir);
		}
		assertThat(t1).isNotEqualTo(t2);
		FileSystemUtils.deleteRecursively(t2);
	}

	@Test
	public void getSubDir() throws Exception {
		ApplicationTemp temp = new ApplicationTemp();
		this.applicationTempDir = temp.getDir();
		assertThat(temp.getDir("abc")).isEqualTo(new File(temp.getDir(), "abc"));
	}

	@Test
	public void getDirCreatesDirectoriesWithOwnerOnlyPermissions() throws Exception {
		assumePosix();
		ApplicationTemp temp = new ApplicationTemp();
		File dir = temp.getDir();
		this.applicationTempDir = dir;
		File subDir = temp.getDir("sub");
		assertThat(getPermissions(dir.toPath())).isEqualTo("rwx------");
		assertThat(getPermissions(subDir.toPath())).isEqualTo("rwx------");
	}

	@Test
	public void getDirWhenDirectoryExistsWithWiderPermissionsThrows() throws Exception {
		assumePosix();
		File dir = new ApplicationTemp().getDir();
		this.applicationTempDir = dir;
		setPermissions(dir.toPath(), "rwxrwxrwx");
		assertGetDirThrows(new ApplicationTemp(), null, "does not have the permissions");
		setPermissions(dir.toPath(), "rwxr-x---");
		assertGetDirThrows(new ApplicationTemp(), null, "does not have the permissions");
	}

	@Test
	public void getDirWhenSubDirectoryExistsWithWiderPermissionsThrows() throws Exception {
		assumePosix();
		ApplicationTemp temp = new ApplicationTemp();
		this.applicationTempDir = temp.getDir();
		File subDir = temp.getDir("sub");
		setPermissions(subDir.toPath(), "rwxrwxrwx");
		assertGetDirThrows(new ApplicationTemp(), "sub", "does not have the permissions");
	}

	@Test
	public void getDirWhenSymbolicLinkExistsInDirectoryLocationThrows() throws Exception {
		File dir = new ApplicationTemp().getDir();
		FileSystemUtils.deleteRecursively(dir);
		createSymbolicLink(dir);
		this.applicationTempDir = dir;
		assertGetDirThrows(new ApplicationTemp(), null, "already exists but it is not a directory");
	}

	@Test
	public void getDirWhenSymbolicLinkExistsInSubDirectoryLocationThrows() throws Exception {
		ApplicationTemp temp = new ApplicationTemp();
		this.applicationTempDir = temp.getDir();
		createSymbolicLink(new File(temp.getDir(), "sub"));
		assertGetDirThrows(new ApplicationTemp(), "sub", "already exists but it is not a directory");
	}

	@Test
	public void getDirWhenDirectoryIsOwnedByAnotherUserThrows() throws Exception {
		// Changing the owner of a directory requires privileges: the expected owner is
		// replaced instead (ReflectionTestUtils would call toString() and so getDir())
		File dir = new ApplicationTemp().getDir();
		this.applicationTempDir = dir;
		ApplicationTemp temp = new ApplicationTemp();
		Field field = ReflectionUtils.findField(ApplicationTemp.class, "directoryOwner");
		assertThat(field).as("Expected owner of the application temp directories").isNotNull();
		ReflectionUtils.makeAccessible(field);
		ReflectionUtils.setField(field, temp, new UserPrincipal() {

			@Override
			public String getName() {
				return "another-user";
			}

		});
		assertGetDirThrows(temp, null, "is not owned by another-user");
	}

	@Test
	public void getSubDirConcurrentlyOnTheSameInstanceReturnsTheCreatedDirectory() throws Exception {
		final ApplicationTemp temp = new ApplicationTemp();
		this.applicationTempDir = temp.getDir();
		File expected = new File(temp.getDir(), "servlet-sessions");
		Callable<Object> getSubDir = new Callable<Object>() {

			@Override
			public Object call() {
				return temp.getDir("servlet-sessions");
			}

		};
		assertThat(getConcurrently(expected, getSubDir, getSubDir)).containsExactly(expected, expected);
		assertOwnerOnlyDirectory(expected);
	}

	@Test
	public void getDirConcurrentlyOnTwoInstancesReturnsTheCreatedDirectory() throws Exception {
		File expected = new ApplicationTemp().getDir();
		this.applicationTempDir = expected;
		Files.delete(expected.toPath());
		final ApplicationTemp first = new ApplicationTemp();
		final ApplicationTemp second = new ApplicationTemp();
		assertThat(getConcurrently(expected, new Callable<Object>() {

			@Override
			public Object call() {
				return first.getDir();
			}

		}, new Callable<Object>() {

			@Override
			public Object call() {
				return second.getDir();
			}

		})).containsExactly(expected, expected);
		assertOwnerOnlyDirectory(expected);
	}

	@Test
	public void getSubDirWhenSymbolicLinkIsCreatedConcurrentlyThrows() throws Exception {
		ApplicationTemp temp = new ApplicationTemp();
		this.applicationTempDir = temp.getDir();
		File subDir = new File(temp.getDir(), "sub");
		Planter planter = new Planter(subDir.toPath(), this.temp.newFolder().toPath(), null);
		Object result = getWithSecurityManager(planter, temp, "sub");
		assertThat(result).isInstanceOf(IllegalStateException.class);
		assertThat(((IllegalStateException) result).getMessage()).contains("already exists but it is not a directory");
		assertThat(planter.planted).isTrue();
	}

	@Test
	public void getSubDirWhenDirectoryWithWiderPermissionsIsCreatedConcurrentlyThrows() throws Exception {
		assumePosix();
		ApplicationTemp temp = new ApplicationTemp();
		this.applicationTempDir = temp.getDir();
		File subDir = new File(temp.getDir(), "sub");
		Planter planter = new Planter(subDir.toPath(), null, "rwxrwxrwx");
		Object result = getWithSecurityManager(planter, temp, "sub");
		assertThat(result).isInstanceOf(IllegalStateException.class);
		assertThat(((IllegalStateException) result).getMessage()).contains("does not have the permissions");
		assertThat(planter.planted).isTrue();
	}

	/**
	 * Run two calls that both see the location missing and then reach its creation
	 * together.
	 */
	private List<Object> getConcurrently(File location, Callable<Object> first, Callable<Object> second)
			throws Exception {
		Rendezvous rendezvous = new Rendezvous(location.toPath());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		List<Object> results = new ArrayList<Object>();
		try {
			installSecurityManager(rendezvous);
			try {
				List<Future<Object>> futures = new ArrayList<Future<Object>>();
				futures.add(executor.submit(first));
				futures.add(executor.submit(second));
				for (Future<Object> future : futures) {
					try {
						results.add(future.get(30, TimeUnit.SECONDS));
					}
					catch (ExecutionException ex) {
						results.add(ex.getCause());
					}
				}
			}
			finally {
				System.setSecurityManager(null);
			}
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(rendezvous.arrivals).as("Calls reaching the creation of " + location).hasSize(2);
		return results;
	}

	private Object getWithSecurityManager(SecurityManager securityManager, ApplicationTemp temp, String subDir) {
		installSecurityManager(securityManager);
		try {
			return temp.getDir(subDir);
		}
		catch (IllegalStateException ex) {
			return ex;
		}
		finally {
			System.setSecurityManager(null);
		}
	}

	private void installSecurityManager(SecurityManager securityManager) {
		Assume.assumeTrue("No security manager installed", System.getSecurityManager() == null);
		try {
			System.setSecurityManager(securityManager);
		}
		catch (UnsupportedOperationException ex) {
			Assume.assumeNoException("Security manager not supported", ex);
		}
	}

	private void assertOwnerOnlyDirectory(File dir) throws IOException {
		assertThat(Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)).isTrue();
		if (dir.toPath().getFileSystem().supportedFileAttributeViews().contains("posix")) {
			assertThat(getPermissions(dir.toPath())).isEqualTo("rwx------");
		}
	}

	private void assertGetDirThrows(ApplicationTemp temp, String subDir, String message) {
		try {
			File dir = (subDir != null) ? temp.getDir(subDir) : temp.getDir();
			fail("Expected IllegalStateException for existing " + dir);
		}
		catch (IllegalStateException ex) {
			assertThat(ex.getMessage()).contains(message);
		}
	}

	private static boolean isLocation(String file, Path location) {
		return Paths.get(file).toAbsolutePath().equals(location.toAbsolutePath());
	}

	private void createSymbolicLink(File link) throws IOException {
		File target = this.temp.newFolder();
		try {
			Files.createSymbolicLink(link.toPath(), target.toPath());
		}
		catch (Exception ex) {
			Assume.assumeNoException("Symbolic link creation not supported", ex);
		}
	}

	private void assumePosix() {
		Assume.assumeTrue("POSIX file attributes not supported",
				this.temp.getRoot().toPath().getFileSystem().supportedFileAttributeViews().contains("posix"));
	}

	private String getPermissions(Path path) throws IOException {
		return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
	}

	private void setPermissions(Path path, String permissions) throws IOException {
		if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			Set<PosixFilePermission> set = PosixFilePermissions.fromString(permissions);
			Files.setPosixFilePermissions(path, set);
		}
	}

	/**
	 * Holds the first two threads that are about to create a location until both have
	 * arrived.
	 */
	private static class Rendezvous extends SecurityManager {

		private final Path location;

		private final CyclicBarrier barrier = new CyclicBarrier(2);

		private final Set<Thread> arrivals = Collections.synchronizedSet(new HashSet<Thread>());

		Rendezvous(Path location) {
			this.location = location;
		}

		@Override
		public void checkPermission(Permission perm) {
		}

		@Override
		public void checkPermission(Permission perm, Object context) {
		}

		@Override
		public void checkWrite(String file) {
			if (isLocation(file, this.location) && this.arrivals.size() < 2
					&& this.arrivals.add(Thread.currentThread())) {
				try {
					this.barrier.await(10, TimeUnit.SECONDS);
				}
				catch (Exception ex) {
					throw new IllegalStateException("Concurrent creation not reached", ex);
				}
			}
		}

	}

	/**
	 * Plants a symbolic link or a directory with the given permissions at a location just
	 * before it is created.
	 */
	private static class Planter extends SecurityManager {

		private final Path location;

		private final Path linkTarget;

		private final String permissions;

		private volatile boolean planted;

		Planter(Path location, Path linkTarget, String permissions) {
			this.location = location;
			this.linkTarget = linkTarget;
			this.permissions = permissions;
		}

		@Override
		public void checkPermission(Permission perm) {
		}

		@Override
		public void checkPermission(Permission perm, Object context) {
		}

		@Override
		public synchronized void checkWrite(String file) {
			if (this.planted || !isLocation(file, this.location)) {
				return;
			}
			this.planted = true;
			try {
				if (this.linkTarget != null) {
					Files.createSymbolicLink(this.location, this.linkTarget);
				}
				else {
					Files.createDirectory(this.location);
					Files.setPosixFilePermissions(this.location, PosixFilePermissions.fromString(this.permissions));
				}
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
		}

	}

}
