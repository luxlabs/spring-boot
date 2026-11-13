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
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.util.EnumSet;

import org.springframework.lang.UsesJava7;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Provides access to an application specific temporary directory. Generally speaking
 * different Spring Boot applications will get different locations, however, simply
 * restarting an application will give the same location.
 * <p>
 * Directories are created with {@code rwx------} permissions where the file system
 * supports POSIX attributes. An existing directory must be a directory (not a symbolic
 * link) owned by the user running the JVM and, with POSIX attributes, must have exactly
 * {@code rwx------} permissions; otherwise an {@link IllegalStateException} is thrown.
 * Without POSIX attributes (for example on Windows) directories are created with the
 * default access control of {@code java.io.tmpdir} and only their type and owner are
 * checked, the latter where {@link Files#getOwner} is supported.
 *
 * @author Phillip Webb
 * @since 1.3.0
 */
@UsesJava7
public class ApplicationTemp {

	private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

	private static final FileAttribute<?>[] NO_FILE_ATTRIBUTES = {};

	private static final EnumSet<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

	private final Class<?> sourceClass;

	private final UserPrincipal directoryOwner;

	private volatile File dir;

	/**
	 * Create a new {@link ApplicationTemp} instance.
	 */
	public ApplicationTemp() {
		this(null);
	}

	/**
	 * Create a new {@link ApplicationTemp} instance for the specified source class.
	 * @param sourceClass the source class or {@code null}
	 */
	public ApplicationTemp(Class<?> sourceClass) {
		this.sourceClass = sourceClass;
		this.directoryOwner = directoryOwner();
	}

	private static UserPrincipal directoryOwner() {
		try {
			Path tempFile = Files.createTempFile("application-temp", "-owner");
			UserPrincipal owner = Files.getOwner(tempFile);
			Files.delete(tempFile);
			return owner;
		}
		catch (UnsupportedOperationException ex) {
			return null;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unable to determine the owner of application temp directories", ex);
		}
	}

	@Override
	public String toString() {
		return getDir().getAbsolutePath();
	}

	/**
	 * Return a sub-directory of the application temp.
	 * @param subDir the sub-directory name
	 * @return a sub-directory
	 */
	public File getDir(String subDir) {
		return createDirectory(new File(getDir(), subDir));
	}

	/**
	 * Return the directory to be used for application specific temp files.
	 * @return the application temp directory
	 */
	public File getDir() {
		if (this.dir == null) {
			synchronized (this) {
				if (this.dir == null) {
					byte[] hash = generateHash(this.sourceClass);
					this.dir = createDirectory(new File(getTempDirectory(), toHexString(hash)));
				}
			}
		}
		return this.dir;
	}

	private File createDirectory(File dir) {
		Path path = dir.toPath();
		try {
			FileSystem fileSystem = path.getFileSystem();
			if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
				Files.createDirectory(path, asFileAttributes(fileSystem, DIRECTORY_PERMISSIONS));
			}
			else {
				if (supportsPosixView(fileSystem)) {
					PosixFileAttributes attributes = Files.readAttributes(path, PosixFileAttributes.class,
							LinkOption.NOFOLLOW_LINKS);
					Assert.state(attributes.isDirectory(), "'" + path + "' already exists but it is not a directory");
					Assert.state(DIRECTORY_PERMISSIONS.equals(attributes.permissions()),
							"Existing directory '" + path + "' does not have the permissions " + DIRECTORY_PERMISSIONS);
					assertDirectoryOwnership(attributes.owner(), path);
				}
				else {
					Assert.state(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS),
							"'" + path + "' already exists but it is not a directory");
					try {
						assertDirectoryOwnership(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS), path);
					}
					catch (UnsupportedOperationException ex) {
						// Ownership check not supported. Continue.
					}
				}
			}
			return dir;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unable to create application temp directory " + path, ex);
		}
	}

	private void assertDirectoryOwnership(UserPrincipal owner, Path path) {
		if (this.directoryOwner != null && !this.directoryOwner.equals(owner)) {
			throw new IllegalStateException(
					"Existing directory '" + path + "' is not owned by " + this.directoryOwner.getName());
		}
	}

	private FileAttribute<?>[] asFileAttributes(FileSystem fileSystem, EnumSet<PosixFilePermission> ownerReadWrite) {
		if (!supportsPosixView(fileSystem)) {
			return NO_FILE_ATTRIBUTES;
		}
		return new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(ownerReadWrite) };
	}

	private boolean supportsPosixView(FileSystem fileSystem) {
		return fileSystem.supportedFileAttributeViews().contains("posix");
	}

	private File getTempDirectory() {
		String property = System.getProperty("java.io.tmpdir");
		Assert.state(StringUtils.hasLength(property), "No 'java.io.tmpdir' property set");
		File file = new File(property);
		Assert.state(file.exists(), "Temp directory" + file + " does not exist");
		Assert.state(file.isDirectory(), "Temp location " + file + " is not a directory");
		return file;
	}

	private byte[] generateHash(Class<?> sourceClass) {
		ApplicationHome home = new ApplicationHome(sourceClass);
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-1");
			update(digest, home.getSource());
			update(digest, home.getDir());
			update(digest, System.getProperty("user.dir"));
			update(digest, System.getProperty("java.home"));
			update(digest, System.getProperty("java.class.path"));
			update(digest, System.getProperty("sun.java.command"));
			update(digest, System.getProperty("sun.boot.class.path"));
			return digest.digest();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void update(MessageDigest digest, Object source) {
		if (source != null) {
			digest.update(getUpdateSourceBytes(source));
		}
	}

	private byte[] getUpdateSourceBytes(Object source) {
		if (source instanceof File) {
			return getUpdateSourceBytes(((File) source).getAbsolutePath());
		}
		return source.toString().getBytes();
	}

	private String toHexString(byte[] bytes) {
		char[] hex = new char[bytes.length * 2];
		for (int i = 0; i < bytes.length; i++) {
			int b = bytes[i] & 0xFF;
			hex[i * 2] = HEX_CHARS[b >>> 4];
			hex[i * 2 + 1] = HEX_CHARS[b & 0x0F];
		}
		return new String(hex);
	}

}
