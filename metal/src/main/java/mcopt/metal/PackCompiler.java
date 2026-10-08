// SPDX-License-Identifier: LGPL-3.0-only
// Adapted from the local Iris Metal port; see NOTICE.
package mcopt.metal;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spvc.*;

/**
 * Vulkan GLSL (from PackGlsl) to SPIR-V with shaderc, then to MSL 3.0 with SPIRV-Cross, with Metal slots chosen by
 * resource name so both stages agree: buffers (uniform blocks) from slot 0 in name order with iris_Uniforms first,
 * textures and their samplers from slot 0 in name order. Only resources the stages actually use get a slot. Shadow
 * samplers use runtime Metal comparison sampler states.
 * Vertex Y is flipped like mcopt's own pipelines, so render targets keep OpenGL's row order.
 */
public final class PackCompiler {
	/** Metal allows 16 sampler states per stage. */
	static final int MAX_TEXTURES = 16;

	private PackCompiler() {
	}

	/** A member of iris_Uniforms as laid out in the buffer. baseType: 'f' float, 'i' int, 'u' uint, 'b' bool. */
	public record Field(String name, int offset, char baseType, int vectorSize, int columns, int matrixStride, int arrayLength, int arrayStride) {
	}

	public record Stage(String msl, String entry) {
	}

	/** Both stages of a program, translated, and where everything sits. */
	public record Program(String name, Stage vertex, Stage fragment, Map<String, Integer> bufferSlots, Map<String, Integer> textureSlots,
				   Set<String> compareSamplers, Map<String, Field> fields, int uniformSize, Map<String, Integer> vertexInputs,
				   Map<String, String> vertexInputTypes, List<PackGlsl.Member> members) {
		public int uniformSlot() {
			return this.bufferSlots.getOrDefault(PackGlsl.UNIFORM_BLOCK, -1);
		}
	}

	public static Program compile(String name, String vertexGlsl, String fragmentGlsl) {
		return compile(name, vertexGlsl, fragmentGlsl, null, Map.of());
	}

	/**
	 * outputRemap moves fragment outputs (see PackGlsl); fixedBuffers pins blocks to Metal buffer slots (Sodium's push
	 * constants sit where mcopt puts the frontend's push constants). Other blocks fill the free slots from 0.
	 */
	public static Program compile(String name, String vertexGlsl, String fragmentGlsl, int[] outputRemap, Map<String, Integer> fixedBuffers) {
		PackGlsl.Result glsl = PackGlsl.rewrite(vertexGlsl, fragmentGlsl, outputRemap);
		ByteBuffer vspv = toSpirv(name + ".vsh", glsl.vertex(), shaderc_vertex_shader);
		ByteBuffer fspv = null;
		try {
			fspv = toSpirv(name + ".fsh", glsl.fragment(), shaderc_fragment_shader);
			Reflected v = reflect(vspv, false), f = reflect(fspv, true);

			Map<String, Integer> buffers = new LinkedHashMap<>();
			TreeSet<String> bufferNames = new TreeSet<>(v.buffers.keySet());
			bufferNames.addAll(f.buffers.keySet());
			Set<Integer> taken = new TreeSet<>();
			for (String b : List.copyOf(bufferNames)) {
				Integer slot = fixedBuffers.get(b);
				if (slot != null) {
					buffers.put(b, slot);
					taken.add(slot);
					bufferNames.remove(b);
				}
			}
			int next = 0;
			List<String> order = new ArrayList<>();
			if (bufferNames.remove(PackGlsl.UNIFORM_BLOCK)) order.add(PackGlsl.UNIFORM_BLOCK);
			order.addAll(bufferNames);
			for (String b : order) {
				while (taken.contains(next)) next++;
				buffers.put(b, next++);
			}

			Map<String, Integer> textures = new LinkedHashMap<>();
			TreeSet<String> textureNames = new TreeSet<>(v.textures.keySet());
			textureNames.addAll(f.textures.keySet());
			for (String t : textureNames) textures.put(t, textures.size());
			if (textures.size() > MAX_TEXTURES) {
				throw new IllegalStateException(name + " samples " + textures.size() + " textures, Metal allows " + MAX_TEXTURES + ": " + textureNames);
			}
			Set<String> compare = new TreeSet<>(v.compare);
			compare.addAll(f.compare);

			Stage vs = toMsl(vspv, false, v, buffers, textures);
			Stage fs = toMsl(fspv, true, f, buffers, textures);
			Reflected withBlock = v.fields.isEmpty() ? f : v;
			return new Program(name, vs, fs, buffers, textures, compare, withBlock.fields, withBlock.uniformSize, glsl.vertexInputs(),
				glsl.vertexInputTypes(), glsl.members());
		} finally {
			MemoryUtil.memFree(vspv);
			if (fspv != null) MemoryUtil.memFree(fspv);
		}
	}

	private static ByteBuffer toSpirv(String name, String source, int kind) {
		long compiler = shaderc_compiler_initialize();
		long options = shaderc_compile_options_initialize();
		long result = 0;
		try {
			shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
			shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
			// Keeps OpName, so blocks and samplers can be matched by name (Mojang's GlslCompiler does the same).
			shaderc_compile_options_set_generate_debug_info(options);
			// Off-heap copies: the CharSequence overload encodes onto LWJGL's 64 KiB MemoryStack, too small for big packs.
			ByteBuffer text = MemoryUtil.memUTF8(source, false), file = MemoryUtil.memUTF8(name), entry = MemoryUtil.memUTF8("main");
			try {
				result = shaderc_compile_into_spv(compiler, text, kind, file, entry, options);
			} finally {
				MemoryUtil.memFree(text);
				MemoryUtil.memFree(file);
				MemoryUtil.memFree(entry);
			}
			if (result == 0) throw new IllegalStateException(name + ": shaderc returned nothing");
			if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
				throw new IllegalStateException(name + ": " + shaderc_result_get_error_message(result) + "\n" + numbered(source));
			}
			ByteBuffer bytes = shaderc_result_get_bytes(result);
			ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
			MemoryUtil.memCopy(bytes, copy);
			return copy;
		} finally {
			if (result != 0) shaderc_result_release(result);
			shaderc_compile_options_release(options);
			shaderc_compiler_release(compiler);
		}
	}

	private static String numbered(String source) {
		StringBuilder b = new StringBuilder();
		String[] lines = source.split("\n", -1);
		for (int i = 0; i < lines.length; i++) b.append(i + 1).append(": ").append(lines[i]).append('\n');
		return b.toString();
	}

	/** What one stage uses: blocks and samplers by name (to their GLSL binding), and iris_Uniforms' layout. */
	private static final class Reflected {
		final Map<String, Integer> buffers = new TreeMap<>();
		final Map<String, Integer> textures = new TreeMap<>();
		final Set<String> compare = new TreeSet<>();
		final Map<String, Field> fields = new LinkedHashMap<>();
		int uniformSize;
	}

	private static Reflected reflect(ByteBuffer spv, boolean fragment) {
		Reflected r = new Reflected();
		MetalShaderTranslation.withCompiler(spv, (stack, context, compiler) -> {
			PointerBuffer out = stack.callocPointer(1);
			check(context, spvc_compiler_get_active_interface_variables(compiler, out), "active variables");
			long active = out.get(0);
			check(context, spvc_compiler_create_shader_resources_for_active_variables(compiler, out, active), "resources");
			long resources = out.get(0);

			for (SpvcReflectedResource res : list(stack, context, resources, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER)) {
				String block = spvc_compiler_get_name(compiler, res.base_type_id());
				if (block == null || block.isEmpty()) block = res.nameString();
				r.buffers.put(block, spvc_compiler_get_decoration(compiler, res.id(), Spv.SpvDecorationBinding));
				if (block.equals(PackGlsl.UNIFORM_BLOCK)) layout(stack, context, compiler, res.base_type_id(), r);
			}
			for (SpvcReflectedResource res : list(stack, context, resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE)) {
				String sampler = res.nameString();
				r.textures.put(sampler, spvc_compiler_get_decoration(compiler, res.id(), Spv.SpvDecorationBinding));
				long type = spvc_compiler_get_type_handle(compiler, res.base_type_id());
				if (spvc_type_get_image_is_depth(type)) r.compare.add(sampler);
			}
			for (int kind : new int[] {SPVC_RESOURCE_TYPE_STORAGE_IMAGE, SPVC_RESOURCE_TYPE_STORAGE_BUFFER, SPVC_RESOURCE_TYPE_SEPARATE_IMAGE}) {
				List<SpvcReflectedResource> unsupported = list(stack, context, resources, kind);
				if (!unsupported.isEmpty()) {
					throw new IllegalStateException("not supported on Metal yet: " + unsupported.stream().map(SpvcReflectedResource::nameString).toList());
				}
			}
			return null;
		});
		return r;
	}

	private static void layout(MemoryStack stack, long context, long compiler, int typeId, Reflected r) {
		long type = spvc_compiler_get_type_handle(compiler, typeId);
		PointerBuffer size = stack.callocPointer(1);
		check(context, spvc_compiler_get_declared_struct_size(compiler, type, size), "struct size");
		r.uniformSize = (int) size.get(0);
		IntBuffer v = stack.callocInt(1);
		int members = spvc_type_get_num_member_types(type);
		for (int i = 0; i < members; i++) {
			String name = spvc_compiler_get_member_name(compiler, typeId, i);
			long member = spvc_compiler_get_type_handle(compiler, spvc_type_get_member_type(type, i));
			check(context, spvc_compiler_type_struct_member_offset(compiler, type, i, v), "offset");
			int offset = v.get(0);
			char base = switch (spvc_type_get_basetype(member)) {
				case SPVC_BASETYPE_INT32 -> 'i';
				case SPVC_BASETYPE_UINT32 -> 'u';
				case SPVC_BASETYPE_BOOLEAN -> 'b';
				default -> 'f';
			};
			int columns = spvc_type_get_columns(member);
			int matrixStride = 0;
			if (columns > 1) {
				check(context, spvc_compiler_type_struct_member_matrix_stride(compiler, type, i, v), "matrix stride");
				matrixStride = v.get(0);
			}
			int arrayLength = 0, arrayStride = 0;
			if (spvc_type_get_num_array_dimensions(member) > 0) {
				arrayLength = spvc_type_get_array_dimension(member, 0);
				check(context, spvc_compiler_type_struct_member_array_stride(compiler, type, i, v), "array stride");
				arrayStride = v.get(0);
			}
			r.fields.put(name, new Field(name, offset, base, spvc_type_get_vector_size(member), columns, matrixStride, arrayLength, arrayStride));
		}
	}

	private static Stage toMsl(ByteBuffer spv, boolean fragment, Reflected reflected, Map<String, Integer> buffers, Map<String, Integer> textures) {
		return MetalShaderTranslation.withCompiler(spv, (stack, context, compiler) -> {
			PointerBuffer out = stack.callocPointer(1);
			MetalShaderTranslation.options(stack, context, compiler, !fragment);
			int model = fragment ? Spv.SpvExecutionModelFragment : Spv.SpvExecutionModelVertex;

			reflected.buffers.forEach((block, binding) -> bind(stack, context, compiler, model, binding, buffers.get(block)));
			reflected.textures.forEach((sampler, binding) -> {
				int slot = textures.get(sampler);
				bind(stack, context, compiler, model, binding, slot);
			});
			return MetalShaderTranslation.finish(stack, context, compiler, model);
		});
	}

	private static void bind(MemoryStack stack, long context, long compiler, int model, int binding, int slot) {
        MetalShaderTranslation.bind(stack, context, compiler, model, 0, binding, slot);
    }

	private static List<SpvcReflectedResource> list(MemoryStack stack, long context, long resources, int kind) {
		PointerBuffer list = stack.callocPointer(1), count = stack.callocPointer(1);
		check(context, spvc_resources_get_resource_list_for_type(resources, kind, list, count), "resource list");
		List<SpvcReflectedResource> result = new ArrayList<>();
		if (count.get(0) == 0) return result;
		SpvcReflectedResource.Buffer buffer = SpvcReflectedResource.create(list.get(0), (int) count.get(0));
		for (int i = 0; i < buffer.capacity(); i++) result.add(buffer.get(i));
		return result;
	}


	private static void check(long context, int result, String step) {
		if (result != SPVC_SUCCESS) {
			throw new IllegalStateException("SPIRV-Cross " + step + " failed: " + (context != 0 ? spvc_context_get_last_error_string(context) : result));
		}
	}
}
