/*
 * Copyright 2026 the original author or authors.
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests of {@link UpstreamResources}, run by the LuxLabs release profile before the
 * check of the real resources. Mutations of the real upstream resources and small
 * synthetic documents must be accepted or rejected as expected; a rejected generated
 * file must not be replaced, an accepted one must end up with the upstream bytes.
 * <p>
 * Usage: {@code java UpstreamResourcesTests <upstream resources root> <work directory>}.
 */
public final class UpstreamResourcesTests {

	private static final String JSON = "META-INF/spring-configuration-metadata.json";

	private static final String PROPERTIES = "META-INF/spring-autoconfigure-metadata.properties";

	private static final String DAT = "META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat";

	private final Path work;

	private final List<String> failures = new ArrayList<>();

	private int count;

	private UpstreamResourcesTests(Path work) {
		this.work = work;
	}

	public static void main(String[] args) throws IOException {
		if (args.length != 2) {
			throw new IllegalArgumentException("Usage: UpstreamResourcesTests <upstream resources root> <work directory>");
		}
		Path root = Paths.get(args[0]);
		Path work = Paths.get(args[1]);
		if (Files.exists(work)) {
			delete(work);
		}
		UpstreamResourcesTests tests = new UpstreamResourcesTests(work);
		tests.json(Files.readAllBytes(root.resolve("spring-boot-autoconfigure").resolve(JSON)));
		tests.properties(Files.readAllBytes(root.resolve("spring-boot-autoconfigure").resolve(PROPERTIES)));
		tests.log4j(Files.readAllBytes(root.resolve("spring-boot").resolve(DAT)));
		tests.atomic(Files.readAllBytes(root.resolve("spring-boot-autoconfigure").resolve(JSON)),
				Files.readAllBytes(root.resolve("spring-boot-autoconfigure").resolve(PROPERTIES)));
		System.out.println("[luxlabs] UpstreamResourcesTests: " + tests.count + " cases, " + tests.failures.size()
				+ " failures");
		if (!tests.failures.isEmpty()) {
			throw new IllegalStateException("UpstreamResourcesTests failed: " + tests.failures);
		}
	}

	private void json(byte[] original) throws IOException {
		String text = new String(original, StandardCharsets.UTF_8);
		Object tree = UpstreamResources.Json.parse(text);
		accept("json-original", JSON, original, original);
		accept("json-compact", JSON, original, utf8(UpstreamResources.Json.write(tree)));
		accept("json-crlf", JSON, original, utf8(text.replace("\n", "\r\n")));
		accept("json-reorder", JSON, original, utf8(UpstreamResources.Json.write(reverse(tree))));
		String needle = "\"name\": \"";
		int name = text.indexOf(needle) + needle.length();
		check(text.charAt(name) == 'l', "first name starts with 'l'");
		reject("json-changed-name", JSON, original, utf8(insert(text, name, "CHANGED-")));
		reject("json-unknown-escape", JSON, original, utf8(insert(text, name, "\\")));
		reject("json-nonhex-unicode-escape", JSON, original, utf8(replace(text, name, 1, "\\u+06c")));
		reject("json-nonascii-hex-unicode-escape", JSON, original, utf8(replace(text, name, 1, "\\u\uFF10\uFF10\uFF16c")));
		reject("json-short-unicode-escape", JSON, original, utf8(replace(text, name, 1, "\\u06c")));
		accept("json-unicode-escape", JSON, original, utf8(replace(text, name, 1, "\\u006c")));
		accept("json-unicode-escape-upper", JSON, original, utf8(replace(text, name, 1, "\\u006C")));
		reject("json-raw-control-in-name", JSON, original, utf8(insert(text, name, "\u0001")));
		int escapedNewline = text.indexOf("\\n");
		if (escapedNewline >= 0) {
			reject("json-raw-newline", JSON, original, utf8(replace(text, escapedNewline, 2, "\n")));
		}
		int escapedTab = text.indexOf("\\t");
		if (escapedTab >= 0) {
			reject("json-raw-tab", JSON, original, utf8(replace(text, escapedTab, 2, "\t")));
		}
		reject("json-nonjson-whitespace-vtab", JSON, original, utf8("\u000b" + text));
		reject("json-nonjson-whitespace-file-separator", JSON, original, utf8("\u001c" + text));
		reject("json-nonjson-whitespace-em-space", JSON, original, utf8("\u2003" + text));
		reject("json-nonjson-whitespace-nbsp", JSON, original, utf8("\u00a0" + text));
		reject("json-byte-order-mark", JSON, original, utf8("\ufeff" + text));
		String number = "\"defaultValue\": 100,";
		check(text.contains(number), "numeric default 100 present");
		reject("json-number-plus", JSON, original, utf8(text.replaceFirst(number, "\"defaultValue\": +100,")));
		reject("json-number-leading-zero", JSON, original, utf8(text.replaceFirst(number, "\"defaultValue\": 00100,")));
		reject("json-number-trailing-dot", JSON, original, utf8(text.replaceFirst(number, "\"defaultValue\": 100.,")));
		reject("json-number-leading-dot", JSON, original, utf8(text.replaceFirst(number, "\"defaultValue\": .100,")));
		reject("json-number-incomplete-exponent", JSON, original, utf8(text.replaceFirst(number, "\"defaultValue\": 1e,")));
		reject("json-number-signed-incomplete-exponent", JSON, original,
				utf8(text.replaceFirst(number, "\"defaultValue\": 1e+,")));
		reject("json-number-minus-only", JSON, original, utf8(text.replaceFirst(number, "\"defaultValue\": -,")));
		reject("json-number-hex", JSON, original, utf8(text.replaceFirst(number, "\"defaultValue\": 0x64,")));
		accept("json-number-valid-exponent", JSON, original, utf8(text.replaceFirst(number, "\"defaultValue\": 1e2,")));
		accept("json-number-valid-fraction", JSON, original, utf8(text.replaceFirst(number, "\"defaultValue\": 100.0,")));
		accept("json-number-valid-signed-exponent", JSON, original,
				utf8(text.replaceFirst(number, "\"defaultValue\": 1000E-1,")));
		reject("json-changed-default", JSON, original, utf8(text.replaceFirst(number, "\"defaultValue\": 101,")));
		reject("json-trailing-data", JSON, original, utf8(text + " true"));
		reject("json-trailing-comma", JSON, original, utf8(text.replaceFirst(number, "\"defaultValue\": 100,,")));
		reject("json-truncated", JSON, original, Arrays.copyOf(original, original.length - 50));
		reject("json-extra-member", JSON, original, utf8("{\"ADDED\":true," + text.substring(1)));
		reject("json-duplicate-member", JSON, original, utf8("{\"hints\": []," + text.substring(1)));
		reject("json-invalid-literal", JSON, original, utf8(text.replaceFirst(number, "\"defaultValue\": nul,")));
		reject("json-invalid-utf8", JSON, original, replaceByte(original, utf8(text.substring(0, name)).length, (byte) 0xff));
		@SuppressWarnings("unchecked")
		Map<String, Object> root = (Map<String, Object>) tree;
		@SuppressWarnings("unchecked")
		List<Object> properties = (List<Object>) root.get("properties");
		Map<String, Object> removed = new LinkedHashMap<>(root);
		removed.put("properties", new ArrayList<>(properties.subList(1, properties.size())));
		reject("json-removed-property", JSON, original, utf8(UpstreamResources.Json.write(removed)));
		Map<String, Object> duplicated = new LinkedHashMap<>(root);
		List<Object> more = new ArrayList<>(properties);
		more.add(properties.get(0));
		duplicated.put("properties", more);
		reject("json-duplicate-array-item", JSON, original, utf8(UpstreamResources.Json.write(duplicated)));
		reject("json-missing-generated", JSON, original, null);
		// Synthetic documents for unescaped control characters in strings
		byte[] expected = utf8("{\"name\":\"a\\nb\"}");
		reject("synthetic-raw-newline", "META-INF/test.json", expected, utf8("{\"name\":\"a\nb\"}"));
		reject("synthetic-raw-tab", "META-INF/test.json", utf8("{\"name\":\"a\\tb\"}"), utf8("{\"name\":\"a\tb\"}"));
		reject("synthetic-raw-nul", "META-INF/test.json", utf8("{\"name\":\"a\\u0000b\"}"), utf8("{\"name\":\"a\u0000b\"}"));
		accept("synthetic-escapes", "META-INF/test.json", utf8("{\"name\":\"a\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00e9\"}"),
				utf8("{\"name\":\"a\\u0022\\u005c/\\u0008\\u000c\\u000a\\u000d\\u0009\u00e9\"}"));
		accept("synthetic-literals", "META-INF/test.json", utf8("{\"a\":[true,false,null,-0.5e1]}"),
				utf8(" {\r\n\t\"a\" : [ null , -5 , false , true ] } "));
		reject("synthetic-null-vs-string", "META-INF/test.json", utf8("{\"a\":null}"), utf8("{\"a\":\"null\"}"));
	}

	private void properties(byte[] original) throws IOException {
		String text = new String(original, StandardCharsets.ISO_8859_1);
		accept("properties-original", PROPERTIES, original, original);
		List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n")));
		Collections.reverse(lines);
		accept("properties-reorder-comments", PROPERTIES, original,
				latin1("# harmless comment\n" + String.join("\n", lines) + "\n"));
		reject("properties-extra-key", PROPERTIES, original, latin1(text + "\nnew.unexpected.key=value\n"));
		String line = null;
		for (String candidate : text.split("\n")) {
			if (!candidate.isEmpty() && !candidate.startsWith("#") && candidate.contains("=")) {
				line = candidate;
				break;
			}
		}
		check(line != null, "property line present");
		reject("properties-changed-value", PROPERTIES, original, latin1(text.replace(line, line + "CHANGED")));
		reject("properties-missing-key", PROPERTIES, original, latin1(text.replace(line + "\n", "")));
	}

	private void log4j(byte[] original) throws IOException {
		List<Object[]> categories = readCache(original);
		check(Arrays.equals(writeCache(categories), original), "plugin cache round trip");
		accept("log4j-original", DAT, original, original);
		List<Object[]> reordered = copy(categories);
		Collections.reverse(reordered);
		for (Object[] category : reordered) {
			Collections.reverse(entries(category));
		}
		accept("log4j-reorder", DAT, original, writeCache(reordered));
		String[] fields = { "key", "class", "name", "printable", "defer" };
		for (int i = 0; i < fields.length; i++) {
			List<Object[]> changed = copy(categories);
			Object[] entry = entries(changed.get(0)).get(0);
			entry[i] = (i < 3) ? "CHANGED" + entry[i] : !((Boolean) entry[i]);
			reject("log4j-changed-" + fields[i], DAT, original, writeCache(changed));
		}
		List<Object[]> category = copy(categories);
		category.get(0)[0] = "other-category";
		reject("log4j-changed-category", DAT, original, writeCache(category));
		List<Object[]> missing = copy(categories);
		List<Object[]> missingEntries = entries(missing.get(0));
		missingEntries.remove(missingEntries.size() - 1);
		reject("log4j-missing-entry", DAT, original, writeCache(missing));
		List<Object[]> duplicate = copy(categories);
		entries(duplicate.get(0)).add(entries(duplicate.get(0)).get(0).clone());
		reject("log4j-duplicate-entry", DAT, original, writeCache(duplicate));
		reject("log4j-trailing-data", DAT, original, Arrays.copyOf(original, original.length + 1));
		reject("log4j-truncated", DAT, original, Arrays.copyOf(original, original.length - 1));
	}

	/**
	 * With one valid and one invalid generated file nothing is replaced.
	 */
	private void atomic(byte[] json, byte[] properties) throws IOException {
		this.count++;
		String label = "atomic-one-invalid-file";
		Path generated = this.work.resolve(label).resolve("generated");
		Path upstream = this.work.resolve(label).resolve("upstream");
		byte[] reorderedProperties = latin1("# comment\n" + new String(properties, StandardCharsets.ISO_8859_1));
		byte[] invalidJson = utf8("\u000b" + new String(json, StandardCharsets.UTF_8));
		write(upstream.resolve(JSON), json);
		write(upstream.resolve(PROPERTIES), properties);
		write(generated.resolve(JSON), invalidJson);
		write(generated.resolve(PROPERTIES), reorderedProperties);
		boolean failed = runFails(generated, upstream);
		boolean unchanged = Arrays.equals(Files.readAllBytes(generated.resolve(JSON)), invalidJson)
				&& Arrays.equals(Files.readAllBytes(generated.resolve(PROPERTIES)), reorderedProperties);
		record(label, failed && unchanged, "rejected=" + failed + " nothing replaced=" + unchanged);
	}

	private void accept(String label, String name, byte[] upstream, byte[] generated) throws IOException {
		this.count++;
		Path generatedFile = prepare(label, name, upstream, generated);
		boolean failed = runFails(this.work.resolve(label).resolve("generated"), upstreamDir(label));
		boolean replaced = Arrays.equals(Files.readAllBytes(generatedFile), upstream);
		record(label, !failed && replaced, "accepted=" + !failed + " upstream bytes=" + replaced);
	}

	private void reject(String label, String name, byte[] upstream, byte[] generated) throws IOException {
		this.count++;
		Path generatedFile = prepare(label, name, upstream, generated);
		boolean failed = runFails(this.work.resolve(label).resolve("generated"), upstreamDir(label));
		boolean unchanged = (generated != null) ? Arrays.equals(Files.readAllBytes(generatedFile), generated)
				: !Files.exists(generatedFile);
		record(label, failed && unchanged, "rejected=" + failed + " not replaced=" + unchanged);
	}

	private Path prepare(String label, String name, byte[] upstream, byte[] generated) throws IOException {
		write(upstreamDir(label).resolve(name), upstream);
		Path generatedFile = this.work.resolve(label).resolve("generated").resolve(name);
		Files.createDirectories(generatedFile.getParent());
		if (generated != null) {
			Files.write(generatedFile, generated);
		}
		return generatedFile;
	}

	private Path upstreamDir(String label) {
		return this.work.resolve(label).resolve("upstream");
	}

	private boolean runFails(Path generated, Path upstream) throws IOException {
		try {
			UpstreamResources.run(generated, upstream);
			return false;
		}
		catch (IllegalStateException ex) {
			return true;
		}
	}

	private void record(String label, boolean passed, String detail) {
		System.out.println("[luxlabs] " + (passed ? "ok   " : "FAIL ") + label + " (" + detail + ")");
		if (!passed) {
			this.failures.add(label);
		}
	}

	private static void check(boolean condition, String description) {
		if (!condition) {
			throw new IllegalStateException("Test precondition not met: " + description);
		}
	}

	private static Object reverse(Object value) {
		if (value instanceof Map) {
			List<Map.Entry<?, ?>> members = new ArrayList<>(((Map<?, ?>) value).entrySet());
			Collections.reverse(members);
			Map<String, Object> result = new LinkedHashMap<>();
			for (Map.Entry<?, ?> member : members) {
				result.put((String) member.getKey(), reverse(member.getValue()));
			}
			return result;
		}
		if (value instanceof List) {
			List<Object> result = new ArrayList<>();
			for (Object element : (List<?>) value) {
				result.add(0, reverse(element));
			}
			return result;
		}
		return value;
	}

	private static List<Object[]> readCache(byte[] bytes) throws IOException {
		List<Object[]> categories = new ArrayList<>();
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
			int count = in.readInt();
			for (int i = 0; i < count; i++) {
				String category = in.readUTF();
				List<Object[]> entries = new ArrayList<>();
				int entryCount = in.readInt();
				for (int j = 0; j < entryCount; j++) {
					entries.add(new Object[] { in.readUTF(), in.readUTF(), in.readUTF(), in.readBoolean(),
							in.readBoolean() });
				}
				categories.add(new Object[] { category, entries });
			}
		}
		return categories;
	}

	private static byte[] writeCache(List<Object[]> categories) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(categories.size());
			for (Object[] category : categories) {
				out.writeUTF((String) category[0]);
				List<Object[]> entries = entries(category);
				out.writeInt(entries.size());
				for (Object[] entry : entries) {
					out.writeUTF((String) entry[0]);
					out.writeUTF((String) entry[1]);
					out.writeUTF((String) entry[2]);
					out.writeBoolean((Boolean) entry[3]);
					out.writeBoolean((Boolean) entry[4]);
				}
			}
		}
		return bytes.toByteArray();
	}

	@SuppressWarnings("unchecked")
	private static List<Object[]> entries(Object[] category) {
		return (List<Object[]>) category[1];
	}

	private static List<Object[]> copy(List<Object[]> categories) {
		List<Object[]> result = new ArrayList<>();
		for (Object[] category : categories) {
			List<Object[]> entries = new ArrayList<>();
			for (Object[] entry : entries(category)) {
				entries.add(entry.clone());
			}
			result.add(new Object[] { category[0], entries });
		}
		return result;
	}

	private static String insert(String text, int index, String value) {
		return text.substring(0, index) + value + text.substring(index);
	}

	private static String replace(String text, int index, int length, String value) {
		return text.substring(0, index) + value + text.substring(index + length);
	}

	private static byte[] replaceByte(byte[] bytes, int index, byte value) {
		byte[] result = bytes.clone();
		result[index] = value;
		return result;
	}

	private static byte[] utf8(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] latin1(String text) {
		return text.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static void write(Path file, byte[] bytes) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
	}

	private static void delete(Path path) throws IOException {
		if (Files.isDirectory(path)) {
			try (java.util.stream.Stream<Path> children = Files.list(path)) {
				for (Path child : (Iterable<Path>) children::iterator) {
					delete(child);
				}
			}
		}
		Files.delete(path);
	}

}
