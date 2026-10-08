// SPDX-License-Identifier: LGPL-3.0-only
// Adapted from the local Iris Metal port; see NOTICE.
package mcopt.metal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iris's patched GLSL (330/400 core, loose uniforms, GL interface rules) rewritten as Vulkan GLSL 450 that shaderc
 * accepts: loose value uniforms move into one std140 block (iris_Uniforms), every block and sampler gets a binding,
 * and every vertex input and varying gets an explicit location (the same one in both stages). The rules come from the
 * phase 2 spike (docs/METAL-PORT.md), which passed all 198 stages of Complementary Reimagined.
 */
public final class PackGlsl {
	public static final String UNIFORM_BLOCK = "iris_Uniforms";

	private static final Pattern LOOSE = Pattern.compile(
		"^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?(\\w+)\\s+(\\w+)\\s*(\\[[^\\]]*\\])?\\s*(?:=\\s*(.*?))?\\s*;\\s*$");
	private static final Pattern BLOCK = Pattern.compile("^\\s*layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+(\\w+)");
	private static final Pattern INTERFACE = Pattern.compile(
		"^\\s*((?:(?:flat|noperspective|smooth|centroid|invariant)\\s+)*)(in|out)\\s+(?:(?:highp|mediump|lowp)\\s+)?(\\w+)\\s+([\\w\\s,\\[\\]]+?)\\s*;\\s*$");
	private static final Pattern VERSION = Pattern.compile("^\\s*#version\\s+.*$", Pattern.MULTILINE);

	private PackGlsl() {
	}

	/** A member of iris_Uniforms, with the value its declaration gave it (null: none). */
	public record Member(String type, String name, String array, String defaultValue) {
	}

	/** One stage-interface variable: a vertex input or a varying. */
	record Varying(String qualifiers, String type, String name, String array) {
		int locations() {
			int size = switch (type) {
				case "mat2", "mat2x2", "mat2x3", "mat2x4", "dmat2" -> 2;
				case "mat3", "mat3x2", "mat3x3", "mat3x4", "dmat3" -> 3;
				case "mat4", "mat4x2", "mat4x3", "mat4x4", "dmat4" -> 4;
				default -> 1;
			};
			if (array.isEmpty()) return size;
			String n = array.replaceAll("[\\[\\]\\s]", "");
			return size * (n.isEmpty() ? 1 : Integer.parseInt(n));
		}
	}

	/** The rewritten stages, the block's members and the vertex inputs' locations (name to location). */
	record Result(String vertex, String fragment, List<Member> members, Map<String, Integer> vertexInputs, Map<String, String> vertexInputTypes) {
	}

	private static boolean maybeUniform(String line) {
		return line.contains("uniform") && line.length() < 4096;
	}

	private static boolean maybeInterface(String line) {
		String t = line.stripLeading();
		if (t.length() > 512) return false;
		return t.startsWith("in ") || t.startsWith("out ") || t.startsWith("flat ") || t.startsWith("noperspective ") || t.startsWith("smooth ")
			|| t.startsWith("centroid ") || t.startsWith("invariant ");
	}

	static boolean isOpaque(String type) {
		return type.contains("sampler") || type.contains("image");
	}

	private static final Pattern OUTPUT = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)(\\s*(?:flat\\s+)?out\\b)");

	static Result rewrite(String vertex, String fragment) {
		return rewrite(vertex, fragment, null);
	}

	/** outputRemap: fragment output location i becomes outputRemap[i] (a gbuffer program's slot among the pass's attachments). */
	static Result rewrite(String vertex, String fragment, int[] outputRemap) {
		if (outputRemap != null) {
			Matcher o = OUTPUT.matcher(fragment);
			StringBuilder b = new StringBuilder();
			while (o.find()) {
				int at = Integer.parseInt(o.group(1));
				int to = at < outputRemap.length ? outputRemap[at] : at;
				o.appendReplacement(b, Matcher.quoteReplacement("layout(location = " + to + ")" + o.group(2)));
			}
			o.appendTail(b);
			fragment = b.toString();
		}
		Map<String, Member> loose = new TreeMap<>();
		List<String> samplers = new ArrayList<>();
		List<String> blocks = new ArrayList<>();
		for (String text : new String[] {vertex, fragment}) {
			for (String line : text.split("\n", -1)) {
				if (!maybeUniform(line)) continue;
				Matcher m = LOOSE.matcher(line);
				if (m.matches()) {
					String type = m.group(1), name = m.group(2);
					if (isOpaque(type)) {
						if (!samplers.contains(name)) samplers.add(name);
					} else {
						loose.putIfAbsent(name, new Member(type, name, m.group(3) == null ? "" : m.group(3), m.group(4)));
					}
					continue;
				}
				Matcher b = BLOCK.matcher(line);
				if (b.find() && !blocks.contains(b.group(1))) blocks.add(b.group(1));
			}
		}
		Map<String, Integer> binding = new LinkedHashMap<>();
		binding.put(UNIFORM_BLOCK, 0);
		for (String block : blocks) binding.putIfAbsent(block, binding.size());
		for (String sampler : samplers) binding.putIfAbsent(sampler, binding.size());

		StringBuilder block = new StringBuilder();
		if (!loose.isEmpty()) {
			block.append("layout(std140, set = 0, binding = 0) uniform ").append(UNIFORM_BLOCK).append(" {\n");
			for (Member member : loose.values()) block.append('\t').append(member.type()).append(' ').append(member.name()).append(member.array()).append(";\n");
			block.append("};\n");
		}

		// Varyings: one location per name, the same in both stages, in name order.
		Map<String, Varying> vertexOut = interfaceOf(vertex, "out"), fragmentIn = interfaceOf(fragment, "in");
		Map<String, Varying> varyings = new TreeMap<>(vertexOut);
		fragmentIn.forEach(varyings::putIfAbsent);
		Map<String, Integer> varyingLocation = new LinkedHashMap<>();
		int next = 0;
		for (Varying v : varyings.values()) {
			varyingLocation.put(v.name(), next);
			next += v.locations();
		}
		// A fragment input the vertex stage never writes: Metal links stages by location, so declare it there too.
		StringBuilder missingOutputs = new StringBuilder();
		for (Varying v : fragmentIn.values()) {
			if (!vertexOut.containsKey(v.name())) {
				missingOutputs.append("layout(location = ").append(varyingLocation.get(v.name())).append(") ").append(v.qualifiers())
					.append("out ").append(v.type()).append(' ').append(v.name()).append(v.array()).append(";\n");
			}
		}

		Map<String, Integer> inputs = new LinkedHashMap<>();
		Map<String, String> inputTypes = new LinkedHashMap<>();
		for (Varying v : interfaceOf(vertex, "in").values()) {
			inputs.put(v.name(), inputs.size());
			inputTypes.put(v.name(), v.type());
		}

		String vs = rewriteStage(vertex, true, binding, block.toString() + missingOutputs, varyingLocation, inputs);
		String fs = rewriteStage(fragment, false, binding, block.toString(), varyingLocation, Map.of());
		return new Result(vs, fs, List.copyOf(loose.values()), inputs, inputTypes);
	}

	/** The single-name declarations of one storage qualifier (in or out) without a layout, by name. */
	private static Map<String, Varying> interfaceOf(String text, String storage) {
		Map<String, Varying> result = new LinkedHashMap<>();
		for (String line : text.split("\n", -1)) {
			if (!maybeInterface(line)) continue;
			Matcher m = INTERFACE.matcher(line);
			if (!m.matches() || !m.group(2).equals(storage)) continue;
			for (String declarator : m.group(4).split(",")) {
				String d = declarator.trim();
				int bracket = d.indexOf('[');
				String name = bracket < 0 ? d : d.substring(0, bracket).trim();
				String array = bracket < 0 ? "" : d.substring(bracket).trim();
				if (!name.isEmpty()) result.put(name, new Varying(m.group(1), m.group(3), name, array));
			}
		}
		return result;
	}

	private static String rewriteStage(String text, boolean vertex, Map<String, Integer> binding, String header,
									   Map<String, Integer> varyingLocation, Map<String, Integer> inputs) {
		StringBuilder out = new StringBuilder(text.length() + header.length() + 1024);
		String storage = vertex ? "out" : "in";
		for (String line : text.split("\n", -1)) {
			Matcher m = maybeUniform(line) ? LOOSE.matcher(line) : null;
			if (m != null && m.matches()) {
				String type = m.group(1), name = m.group(2);
				if (isOpaque(type)) {
					out.append("layout(set = 0, binding = ").append(binding.get(name)).append(") uniform ").append(type).append(' ').append(name)
						.append(m.group(3) == null ? "" : m.group(3)).append(";\n");
				}
				continue; // value uniforms live in iris_Uniforms now
			}
			Matcher b = maybeUniform(line) ? BLOCK.matcher(line) : null;
			if (b != null && b.find()) {
				out.append(line.replaceFirst("layout\\s*\\(\\s*std140\\s*\\)", "layout(std140, set = 0, binding = " + binding.get(b.group(1)) + ")")).append('\n');
				continue;
			}
			Matcher io = maybeInterface(line) ? INTERFACE.matcher(line) : null;
			if (io != null && io.matches()) {
				String quals = io.group(1), dir = io.group(2), type = io.group(3);
				StringBuilder decl = new StringBuilder();
				for (String declarator : io.group(4).split(",")) {
					String d = declarator.trim();
					int bracket = d.indexOf('[');
					String name = bracket < 0 ? d : d.substring(0, bracket).trim();
					Integer location = dir.equals(storage) ? varyingLocation.get(name) : vertex && dir.equals("in") ? inputs.get(name) : null;
					if (location == null) {
						decl.append(quals).append(dir).append(' ').append(type).append(' ').append(d).append(";\n");
					} else {
						decl.append("layout(location = ").append(location).append(") ").append(quals).append(dir).append(' ').append(type).append(' ').append(d).append(";\n");
					}
				}
				out.append(decl);
				continue;
			}
			out.append(line).append('\n');
		}
		String body = out.toString()
			.replaceAll("\\bgl_VertexID\\b", "gl_VertexIndex")
			.replaceAll("\\bgl_InstanceID\\b", "gl_InstanceIndex");
		Matcher version = VERSION.matcher(body);
		if (!version.find()) throw new IllegalArgumentException("No #version directive");
		// The block goes before the first line that isn't a preprocessor line, so #extension lines stay first.
		String rest = body.substring(version.end());
		int insertAt = 0;
		for (String line : rest.split("\n", -1)) {
			String t = line.trim();
			if (!t.isEmpty() && !t.startsWith("#extension")) break;
			insertAt += line.length() + 1;
		}
		insertAt = Math.min(insertAt, rest.length());
		return "#version 450 core\n" + rest.substring(0, insertAt) + header + rest.substring(insertAt);
	}

	/** A declaration's initializer as a number (bools as 0/1), or NaN when there is none or it isn't a literal. */
	public static double literal(String value) {
		if (value == null) return Double.NaN;
		String v = value.trim();
		if (v.equals("true")) return 1;
		if (v.equals("false")) return 0;
		v = v.replaceAll("[fFuU]$", "");
		try {
			return Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}
}
