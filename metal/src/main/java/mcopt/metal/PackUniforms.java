// SPDX-License-Identifier: LGPL-3.0-only
// Adapted from the local Iris Metal port; see NOTICE.
package mcopt.metal;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * A program's iris_Uniforms block in CPU memory, written by Iris's uniform classes through IrisRenderSystem (location =
 * member index) and handed to Metal with setBytes before each draw. Values are converted to the member's type, the way
 * glUniform* would for a loose uniform.
 */
public final class PackUniforms {
	final PackCompiler.Field[] fields;
	final ByteBuffer data;
	final long address;

	public PackUniforms(PackCompiler.Field[] fields, int size) {
		this.fields = fields;
		this.data = MemoryUtil.memCalloc(Math.max(16, size));
		this.address = MemoryUtil.memAddress(this.data);
	}

	public int size() {
		return this.data.capacity();
	}

	public void free() {
		MemoryUtil.memFree(this.data);
	}

	public void upload(long encoder, int slot) {
		MetalBridge.bytes(encoder, slot, this.address, size());
	}

	private void put(PackCompiler.Field f, int component, double value) {
		int at = f.offset() + component * 4;
		if (at + 4 > this.data.capacity()) return;
		if (f.baseType() == 'f') this.data.putFloat(at, (float) value);
		else this.data.putInt(at, (int) value);
	}

	public void floats(int location, float... values) {
		if (location < 0 || location >= this.fields.length) return;
		PackCompiler.Field f = this.fields[location];
		for (int c = 0; c < values.length && c < Math.max(1, f.vectorSize()); c++) put(f, c, values[c]);
	}

	public void ints(int location, int... values) {
		if (location < 0 || location >= this.fields.length) return;
		PackCompiler.Field f = this.fields[location];
		for (int c = 0; c < values.length && c < Math.max(1, f.vectorSize()); c++) put(f, c, values[c]);
	}

	public void matrix(int location, FloatBuffer m) {
		float[] values = new float[m.remaining()];
		m.get(m.position(), values);
		matrix(location, values);
	}

	/** Column-major, rows per column = the member's vector size (std140 pads each column to its matrix stride). */
	public void matrix(int location, float[] m) {
		if (location < 0 || location >= this.fields.length) return;
		PackCompiler.Field f = this.fields[location];
		int rows = Math.max(1, f.vectorSize()), columns = Math.max(1, f.columns()), stride = f.matrixStride() == 0 ? 16 : f.matrixStride();
		for (int c = 0; c < columns; c++) {
			for (int r = 0; r < rows && c * rows + r < m.length; r++) {
				int at = f.offset() + c * stride + r * 4;
				if (at + 4 <= this.data.capacity()) this.data.putFloat(at, m[c * rows + r]);
			}
		}
	}
}
