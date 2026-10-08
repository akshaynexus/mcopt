// SPDX-License-Identifier: LGPL-3.0-only
// Shared by device pipelines and shader-pack programs. See NOTICE.
package mcopt.metal;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import static org.lwjgl.util.spvc.Spvc.*;

final class MetalShaderTranslation {
    private MetalShaderTranslation() {}
    static void options(MemoryStack stack, long context, long compiler, boolean vertex) {
        PointerBuffer out = stack.callocPointer(1);
        check(context, spvc_compiler_create_compiler_options(compiler, out), "options");
        long options = out.get(0);
        spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, 30000);
        spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
        spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
        if (vertex) spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
        check(context, spvc_compiler_install_compiler_options(compiler, options), "install options");
    }
    static PackCompiler.Stage finish(MemoryStack stack, long context, long compiler, int model) {
        PointerBuffer out = stack.callocPointer(1);
        check(context, spvc_compiler_compile(compiler, out), "compile");
        return new PackCompiler.Stage(MemoryUtil.memUTF8(out.get(0)),
            spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model));
    }
    static void bind(MemoryStack stack, long context, long compiler, int model, int set, int binding, int slot) {
        SpvcMslResourceBinding b = SpvcMslResourceBinding.calloc(stack);
        spvc_msl_resource_binding_init(b);
        b.stage(model).desc_set(set).binding(binding).msl_buffer(slot).msl_texture(slot).msl_sampler(slot);
        check(context, spvc_compiler_msl_add_resource_binding(compiler, b), "binding " + binding);
    }
	interface CompilerUse<T> {
		T run(MemoryStack stack, long context, long compiler);
	}

	static <T> T withCompiler(ByteBuffer spv, CompilerUse<T> use) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.callocPointer(1);
			check(0, spvc_context_create(out), "context");
			long context = out.get(0);
			try {
				IntBuffer words = spv.duplicate().order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
				check(context, spvc_context_parse_spirv(context, words, words.remaining(), out), "parse");
				long ir = out.get(0);
				check(context, spvc_context_create_compiler(context, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out), "compiler");
				return use.run(stack, context, out.get(0));
			} finally {
				spvc_context_destroy(context);
			}
		}
	}


	static void check(long context, int result, String step) {
		if (result != SPVC_SUCCESS) {
			throw new IllegalStateException("SPIRV-Cross " + step + " failed: " + (context != 0 ? spvc_context_get_last_error_string(context) : result));
		}
	}
}
