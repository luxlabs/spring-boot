/*
 * Copyright 2012-2026 the original author or authors.
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

/*
 * Added by LuxLabs in 2026: tests for the atomic creation of the embedded container temp directories (CVE-2022-27772).
 */

package org.springframework.boot.context.embedded;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import org.springframework.util.FileSystemUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AbstractEmbeddedServletContainerFactory#createTempDir(String)}
 * (CVE-2022-27772). A local attacker is simulated with a {@link SecurityManager} that
 * plants a directory or a symbolic link at the name the factory is about to create.
 */
public class EmbeddedServletContainerFactoryTempDirTests {

	private static final String PREFIX = "luxlabs-tempdir-probe";

	private static final int PORT = 18400;

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final List<File> cleanUp = new ArrayList<File>();

	@After
	public void deleteCreatedFiles() throws IOException {
		for (File file : this.cleanUp) {
			if (Files.isSymbolicLink(file.toPath())) {
				Files.delete(file.toPath());
			}
			else {
				FileSystemUtils.deleteRecursively(file);
			}
		}
	}

	@Test
	public void createTempDirKeepsPrefixAndPortInTheName() throws Exception {
		File dir = createTempDir(null);
		assertThat(dir.getName()).startsWith(PREFIX + ".").contains("." + PORT);
		assertThat(Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)).isTrue();
		assertThat(dir.list()).isEmpty();
	}

	@Test
	public void createTempDirDoesNotDeleteAndRecreateItsPath() throws Exception {
		Attacker recorder = new Attacker(Attack.NONE, null);
		File dir = createTempDir(recorder);
		assertThat(recorder.getEventsBeforeLastWrite(dir))
				.as("File events before the directory is created (createTempFile + delete + mkdir)")
				.doesNotContain("delete");
	}

	@Test
	public void createTempDirDoesNotUseDirectoryPlantedBetweenDeleteAndMkdir() throws Exception {
		assertNotPlanted(Attack.DIRECTORY_AFTER_DELETE);
	}

	@Test
	public void createTempDirDoesNotFollowSymbolicLinkPlantedBetweenDeleteAndMkdir() throws Exception {
		assertNotPlanted(Attack.SYMBOLIC_LINK_AFTER_DELETE);
	}

	@Test
	public void createTempDirDoesNotUseDirectoryPlantedAtTheFirstName() throws Exception {
		assertNotPlanted(Attack.DIRECTORY_AT_FIRST_WRITE);
	}

	@Test
	public void createTempDirDoesNotFollowSymbolicLinkPlantedAtTheFirstName() throws Exception {
		assertNotPlanted(Attack.SYMBOLIC_LINK_AT_FIRST_WRITE);
	}

	private void assertNotPlanted(Attack attack) throws Exception {
		File target = this.temp.newFolder("attacker");
		File marker = new File(target, "planted");
		assertThat(marker.createNewFile()).isTrue();
		Attacker attacker = new Attacker(attack, target);
		File dir = createTempDir(attacker);
		if (attack.atFirstWrite) {
			assertThat(attacker.getPlanted()).as("Planted paths").isNotEmpty();
		}
		// Without a delete before the creation there is no window to plant after delete
		assertThat(attacker.getPlanted()).as("Planted paths").doesNotContain(dir);
		assertThat(Files.isSymbolicLink(dir.toPath())).as("Symbolic link " + dir).isFalse();
		assertThat(Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)).isTrue();
		assertThat(dir.list()).as("Contents of " + dir).isEmpty();
	}

	private File createTempDir(Attacker attacker) throws Exception {
		MockEmbeddedServletContainerFactory factory = new MockEmbeddedServletContainerFactory();
		factory.setPort(PORT);
		File dir;
		if (attacker == null) {
			dir = factory.createTempDir(PREFIX);
		}
		else {
			SecurityManager previous = System.getSecurityManager();
			Assume.assumeTrue("No security manager installed", previous == null);
			try {
				System.setSecurityManager(attacker);
			}
			catch (UnsupportedOperationException ex) {
				Assume.assumeNoException("Security manager not supported", ex);
			}
			try {
				dir = factory.createTempDir(PREFIX);
			}
			finally {
				System.setSecurityManager(null);
			}
			this.cleanUp.addAll(attacker.getPlanted());
		}
		this.cleanUp.add(dir);
		return dir;
	}

	private enum Attack {

		NONE(false, false), DIRECTORY_AFTER_DELETE(true, false), SYMBOLIC_LINK_AFTER_DELETE(false, false),

		DIRECTORY_AT_FIRST_WRITE(true, true), SYMBOLIC_LINK_AT_FIRST_WRITE(false, true);

		private final boolean directory;

		private final boolean atFirstWrite;

		Attack(boolean directory, boolean atFirstWrite) {
			this.directory = directory;
			this.atFirstWrite = atFirstWrite;
		}

	}

	/**
	 * Records file events on the probe paths and plants a directory or a symbolic link at
	 * a probe path just before it is created.
	 */
	private static class Attacker extends SecurityManager {

		private final Attack attack;

		private final File target;

		private final List<String[]> events = Collections.synchronizedList(new ArrayList<String[]>());

		private final Set<String> deleted = Collections.synchronizedSet(new HashSet<String>());

		private final List<File> planted = Collections.synchronizedList(new ArrayList<File>());

		private final ThreadLocal<Boolean> planting = new ThreadLocal<Boolean>();

		Attacker(Attack attack, File target) {
			this.attack = attack;
			this.target = target;
		}

		@Override
		public void checkPermission(Permission perm) {
		}

		@Override
		public void checkPermission(Permission perm, Object context) {
		}

		@Override
		public void checkDelete(String file) {
			if (isProbe(file)) {
				this.events.add(new String[] { "delete", file });
				this.deleted.add(file);
			}
		}

		@Override
		public void checkWrite(String file) {
			if (!isProbe(file)) {
				return;
			}
			this.events.add(new String[] { "write", file });
			if (shouldPlant(file)) {
				plant(new File(file));
			}
		}

		private boolean shouldPlant(String file) {
			if (this.attack == Attack.NONE || !this.planted.isEmpty()) {
				return false;
			}
			return this.attack.atFirstWrite || this.deleted.contains(file);
		}

		private void plant(File file) {
			Path path = file.toPath();
			if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
				return;
			}
			this.planting.set(Boolean.TRUE);
			try {
				if (this.attack.directory) {
					Files.createDirectory(path);
					Files.createFile(path.resolve("planted"));
				}
				else {
					Files.createSymbolicLink(path, this.target.toPath());
				}
				this.planted.add(file);
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
			finally {
				this.planting.remove();
			}
		}

		private boolean isProbe(String file) {
			return this.planting.get() == null && new File(file).getName().startsWith(PREFIX + ".");
		}

		List<File> getPlanted() {
			return new ArrayList<File>(this.planted);
		}

		List<String> getEventsBeforeLastWrite(File dir) {
			List<String> events = new ArrayList<String>();
			List<String> result = new ArrayList<String>();
			synchronized (this.events) {
				for (String[] event : this.events) {
					if (new File(event[1]).getAbsoluteFile().equals(dir.getAbsoluteFile())) {
						events.add(event[0]);
						if ("write".equals(event[0])) {
							result = new ArrayList<String>(events.subList(0, events.size() - 1));
						}
					}
				}
			}
			return result;
		}

	}

}
