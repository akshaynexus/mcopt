package mcopt.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import java.lang.foreign.SymbolLookup;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The shaderpack runtime's (mcopt.metal.pack) window into this package: native handles of the backend's objects and
 * the loaded native library. Everything the pack package needs from the backend goes through here, so the backend's
 * own classes stay package-private and its hot paths untouched.
 */
public final class MetalBridge {
	private MetalBridge() {
	}

	/** libmcmetal as loaded by the backend; mcpack.m's functions live in the same image. */
	public static SymbolLookup library() {
		return Native.lookup();
	}

	/** A Metal context without a window, for headless checks (no game, no surface). */
	public static long createHeadlessContext() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long name = stack.nmalloc(1, 256), err = stack.nmalloc(1, 1024);
			long ctx = Native.create(name, 256, err, 1024);
			if (ctx == 0) throw new IllegalStateException("Metal unavailable: " + MemoryUtil.memUTF8(err));
			return ctx;
		}
	}

	/** A native encoder for headless checks; commitAndWait submits what was recorded and waits for the GPU. */
	public static long createHeadlessEncoder(long ctx) {
		return Native.encNew(ctx);
	}

	public static void commitAndWait(long enc) {
		long cmd = Native.encCommit(enc);
		Native.cmdWait(cmd);
		Native.release(cmd);
	}

	public static long newTexture(long ctx, GpuFormat format, int width, int height, boolean sampled) {
		return Native.textureNew(ctx, MetalConst.pixelFormat(format), width, height, 1, 1, 4 | (sampled ? 1 : 0), 0);
	}

	public static long newBuffer(long ctx, long size) {
		return Native.bufferNew(ctx, size);
	}

	public static long bufferContents(long buffer) {
		return Native.bufferContents(buffer);
	}

	public static void readTexture(long enc, long texture, int width, int height, int bytesPerPixel, long buffer) {
		Native.blitTextureToBuffer(enc, texture, 0, 0, 0, width, height, buffer, 0, width * bytesPerPixel);
	}

	public static int pixelFormat(GpuFormat format) {
		return MetalConst.pixelFormat(format);
	}

	public static int vertexFormat(GpuFormat format) {
		return MetalConst.vertexFormat(format);
	}

	public static int blendFactor(BlendFactor factor) {
		return MetalConst.blendFactor(factor);
	}

	public static int blendOp(BlendOp op) {
		return MetalConst.blendOp(op);
	}

	/** MTLCompareFunction for op. */
	public static int compare(CompareOp op) {
		return MetalConst.compare(op);
	}

	/** The MTLPrimitiveType the backend draws topology with (5 = triangle fan, emulated). */
	public static int primitive(PrimitiveTopology topology) {
		return MetalConst.primitive(topology);
	}

	public static int topologyClass(PrimitiveTopology topology) {
		return MetalConst.topologyClass(topology);
	}

	public static long textureHandle(GpuTexture texture) {
		return ((MetalTexture) texture).handle;
	}

	public static long viewHandle(GpuTextureView view) {
		return ((MetalTexture.View) view).handle;
	}

	public static long samplerHandle(GpuSampler sampler) {
		return ((MetalSampler) sampler).handle();
	}

	public static long bufferHandle(GpuBuffer buffer) {
		return ((MetalBuffer) buffer).handle;
	}

	/** The backend's encoder behind a frontend command encoder, or null when the game isn't on the Metal backend. */
	public static @Nullable Object encoder(CommandEncoderBackend backend) {
		return backend instanceof MetalEncoder e ? e : null;
	}

	/** Native Enc* of encoder (the object encoder() returned). */
	public static long enc(Object encoder) {
		return ((MetalEncoder) encoder).enc;
	}

	/** Whether encoder is recording the submit -Dmcopt.metal.trace names (whose operations and encoder GPU times get printed). */
	public static boolean tracing(Object encoder) {
		return ((MetalEncoder) encoder).tracing();
	}

	public static long ctx(Object encoder) {
		return ((MetalEncoder) encoder).ctx;
	}

	/**
	 * Opens (or continues) a render encoder on colors (0 = unused slot) and depth, like the backend's own passes: clears[i]
	 * null loads, else clears to {r, g, b, a}. Returns mc_render_begin's result (0 new encoder, 1 or 2 continued).
	 */
	/** A clears entry meaning: don't load this attachment, the pass writes all of it. */
	public static final float[] DONT_CARE = new float[0];

	public static int renderBegin(long enc, long[] colors, float @Nullable [][] clears, long depth, boolean clearDepth, float depthValue, int width, int height) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long handles = stack.nmalloc(8, Math.max(1, colors.length) * 8);
			long clearData = stack.ncalloc(4, Math.max(1, colors.length) * 5, 4);
			for (int i = 0; i < colors.length; i++) {
				MemoryUtil.memPutAddress(handles + i * 8L, colors[i]);
				float[] c = clears == null ? null : clears[i];
				if (c == DONT_CARE) {
					MemoryUtil.memPutFloat(clearData + i * 20L, 2);
				} else if (c != null) {
					long at = clearData + i * 20L;
					MemoryUtil.memPutFloat(at, 1);
					for (int k = 0; k < 4; k++) MemoryUtil.memPutFloat(at + 4 + k * 4L, c[k]);
				}
			}
			return Native.renderBegin(enc, colors.length, handles, clearData, depth, clearDepth ? 1 : 0, depthValue, width, height);
		}
	}

	public static void blitTextureToTexture(long enc, long src, long dst, int mip, int width, int height) {
		Native.blitTextureToTexture(enc, src, dst, mip, 0, 0, 0, 0, width, height);
	}

	/** Forgets a pending clear of texture: about to be overwritten whole. */
	public static void dropPendingClear(Object encoder, GpuTexture texture) {
		((MetalEncoder) encoder).dropPendingClear((MetalTexture) texture);
	}

	/** Native shading only: move the deferred main-depth clear to its tile-local replacement. */
	public static float takePendingDepthClear(Object encoder, GpuTexture texture) {
		MetalTexture t = (MetalTexture) texture;
		float value = (float) t.pendingDepthClear;
		((MetalEncoder) encoder).dropPendingClear(t);
		return value;
	}

	/** The texture's contents are dead after the open render pass: it isn't stored (store action don't care). */
	public static void discard(long enc, long texture) {
		Native.discard(enc, texture);
	}

	/** A pending (deferred) clear of texture written to memory now, for reading it outside a render pass. */
	public static void flushClear(Object encoder, GpuTexture texture) {
		((MetalEncoder) encoder).flushClear(texture);
	}

	public static void index(long enc, GpuBuffer buffer, boolean intIndices) {
		Native.index(enc, ((MetalBuffer) buffer).handle, intIndices ? 1 : 0);
	}

	public static void drawIndexed(long enc, int indexCount, int instances, int firstIndex, int baseVertex, int firstInstance) {
		Native.drawIndexed(enc, indexCount, instances, firstIndex, baseVertex, firstInstance);
	}

	public static void vertexBuffer(long enc, int index, long buffer, long offset) {
		Native.vertexBuffer(enc, index, buffer, offset);
	}

	public static @Nullable String pipelineName(Object backendPipeline) {
		return backendPipeline instanceof MetalPipeline p ? p.name : null;
	}

	/**
	 * Far terrain (mcopt.metal.lod, -Dmcopt.lod) drew into the open render pass with its own pipeline: put the pass's current
	 * pipeline state back. False when no pass is open (nothing was drawn into one).
	 */
	public static boolean reapplyPipeline(Object encoder) {
		return ((MetalEncoder) encoder).reapplyPipeline();
	}

	/** Whether a render pass is open on encoder (far terrain draws only into one). */
	public static boolean inRenderPass(Object encoder) {
		return ((MetalEncoder) encoder).inRenderPass();
	}

	// --- Shaderpack runtime (the Iris fork's net.irisshaders.iris.metal): its own pipelines, drawn into the backend's encoder. ---

	/** The live device's encoder (the object encoder() returns), or null before the Metal device exists. */
	public static @Nullable Object encoder() {
		MetalDevice d = MetalDevice.instance;
		return d == null ? null : d.encoder();
	}

	/** The live device's Metal context. */
	public static long ctx() {
		return java.util.Objects.requireNonNull(MetalDevice.instance, "Metal device").ctx();
	}

	/** Whether the frontend has a render pass open on encoder: native draws must not interleave with one. */
	public static boolean frontendPassOpen(Object encoder) {
		return ((MetalEncoder) encoder).inRenderPass();
	}

	/** A Metal library from MSL source; throws with the compiler's message on failure. */
	public static long libraryNew(long ctx, String msl) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 8192);
			MemoryUtil.memPutByte(err, (byte) 0);
			// Off-heap: a shaderpack's MSL is often bigger than the 64 KiB MemoryStack.
			java.nio.ByteBuffer source = MemoryUtil.memUTF8(msl);
			try {
				long lib = Native.libraryNew(ctx, MemoryUtil.memAddress(source), err, 8192);
				if (lib == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
				return lib;
			} finally {
				MemoryUtil.memFree(source);
			}
		}
	}

	/**
	 * A render pipeline state. desc is mc_pipeline_new's layout (see mcmetal.m): vertex buffers {slot, stride, stepRate},
	 * attributes {location, buffer slot, offset, MTLVertexFormat}, colors {MTLPixelFormat, write mask, blend on, 6 blend
	 * values}, depth MTLPixelFormat (0 none), MTLPrimitiveTopologyClass. Buffer slots here are Metal slots (use
	 * vertexBufferSlot). Throws with Metal's message on failure.
	 */
	public static long pipelineNew(long ctx, long vlib, String vname, long flib, String fname, int[] desc) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 8192);
			MemoryUtil.memPutByte(err, (byte) 0);
			java.nio.IntBuffer d = stack.mallocInt(desc.length).put(desc).flip();
			long pso = Native.pipelineNew(ctx, vlib, MemoryUtil.memAddress(stack.UTF8(vname)), flib, MemoryUtil.memAddress(stack.UTF8(fname)),
				MemoryUtil.memAddress(d), err, 8192);
			if (pso == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
			return pso;
		}
	}

	/** MTLDepthStencilState for an MTLCompareFunction (see compare) and depth writes on or off. */
	public static long depthStateNew(long ctx, int compare, boolean write) {
		return Native.depthStateNew(ctx, compare, write ? 1 : 0);
	}

	/** MTLSamplerState: address 0 clamp-to-edge, 2 repeat; filter 0 nearest, 1 linear; mip 0 none, 1 nearest, 2 linear. */
	public static long comparisonSamplerNew(long ctx, int addressU, int addressV, int minFilter, int magFilter, int mipFilter, float maxLod, int compare) {
		return Native.samplerCompareNew(ctx, addressU, addressV, minFilter, magFilter, mipFilter, 1, maxLod, compare);
	}

	public static long samplerNew(long ctx, int addressU, int addressV, int minFilter, int magFilter, int mipFilter, float maxLod) {
		return Native.samplerNew(ctx, addressU, addressV, minFilter, magFilter, mipFilter, 1, maxLod);
	}

	/** A private 2D texture of a raw MTLPixelFormat; usage is MTLTextureUsage (ShaderRead 1, ShaderWrite 2, RenderTarget 4). */
	public static long newTexture(long ctx, int mtlPixelFormat, int width, int height, int mips, int usage) {
		return Native.textureNew(ctx, mtlPixelFormat, width, height, 1, mips, usage, 0);
	}

	/** The Metal buffer slot a frontend vertex buffer slot is bound at. */
	public static int vertexBufferSlot(int slot) {
		return MetalConst.VERTEX_BUFFER_BASE + slot;
	}

	/** The first Metal buffer slot the frontend never uses for uniforms (push constants and vertex buffers sit above). */
	public static int firstReservedBufferSlot() {
		return MetalConst.PUSH_CONSTANTS_INDEX;
	}

    /** Bind an unmodified device program when a redirected pass restores its original attachments. */
    public static void bindDevicePipeline(long enc, Object backend, boolean hasDepth) {
        MetalPipeline p = (MetalPipeline) backend;
        Native.pipeline(enc, hasDepth ? p.withDepth : p.withoutDepth, p.depthState, p.cull ? 1 : 0,
            p.wireframe ? 1 : 0, p.depthBiasConstant, p.depthBiasSlope, p.primitive);
    }

	public static void bindPipeline(long enc, long pso, long depthState, boolean cull, int primitive) {
		Native.pipeline(enc, pso, depthState, cull ? 1 : 0, 0, 0, 0, primitive);
	}

	public static void bindPipeline(long enc, long pso, long depthState, boolean cull, float depthBiasConstant, float depthBiasSlope, int primitive) {
		Native.pipeline(enc, pso, depthState, cull ? 1 : 0, 0, depthBiasConstant, depthBiasSlope, primitive);
	}

	/** setVertexBytes and setFragmentBytes at slot (at most 4 KiB). */
	public static void bytes(long enc, int slot, long address, int length) {
		Native.bytes(enc, slot, address, length);
	}

	/** A texture (and sampler, 0 for none) at slot, for both stages. */
	public static void texture(long enc, int slot, long texture, long sampler) {
		Native.texture(enc, slot, texture, sampler);
	}

	public static void buffer(long enc, int slot, long buffer, long offset) {
		Native.buffer(enc, slot, buffer, offset);
	}

	/** The buffer behind a frontend slice, marked as used by the submit being recorded. */
	public static long useBuffer(Object encoder, GpuBuffer buffer) {
		return ((MetalEncoder) encoder).use(buffer).handle;
	}

	public static void draw(long enc, int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		Native.draw(enc, vertexCount, instanceCount, firstVertex, firstInstance);
	}

	public static void scissor(long enc, int x, int y, int width, int height) {
		Native.scissor(enc, x, y, width, height);
	}

	/** Set viewport after renderBegin; integer truncation is the caller's policy. */
	public static void viewport(long enc, int x, int y, int width, int height) {
		Native.viewport(enc, x, y, width, height);
	}

	public static void generateMipmaps(long enc, long texture) {
		Native.generateMipmaps(enc, texture);
	}

	/** What the frontend asked for when it built backendPipeline (vertex layout, uniforms, targets), or null if it isn't a Metal pipeline. */
	public static com.mojang.renderpearl.backend.api.BackendRenderPipeline.@Nullable CreateInfo createInfo(Object backendPipeline) {
		return backendPipeline instanceof MetalPipeline p ? p.info : null;
	}

	/** The MTLCompareFunction the backend pipeline tests depth with (7 always when it has no depth state). */
	public static int depthCompare(Object backendPipeline) {
		return ((MetalPipeline) backendPipeline).depthCompare;
	}

	/** The texture and sampler handles behind a frontend combined image sampler value (a TextureViewAndSampler). */
	public static long[] textureAndSampler(Object value) {
		var ts = (com.mojang.renderpearl.util.TextureViewAndSampler) value;
		return new long[] {((MetalTexture.View) ts.view()).handle, ((MetalSampler) ts.sampler()).handle()};
	}

	public static void release(long handle) {
		Native.release(handle);
	}
}
