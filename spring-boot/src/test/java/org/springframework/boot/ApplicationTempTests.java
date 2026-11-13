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
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

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

	private void assertGetDirThrows(ApplicationTemp temp, String subDir, String message) {
		try {
			File dir = (subDir != null) ? temp.getDir(subDir) : temp.getDir();
			fail("Expected IllegalStateException for existing " + dir);
		}
		catch (IllegalStateException ex) {
			assertThat(ex.getMessage()).contains(message);
		}
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

}
