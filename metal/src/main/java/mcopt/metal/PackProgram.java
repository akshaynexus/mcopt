// SPDX-License-Identifier: LGPL-3.0-only
// Adapted from the local Iris Metal port; see NOTICE.
package mcopt.metal;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Owns a shader pack's libraries and cached pipeline states. Render-thread confined. */
public final class PackProgram implements AutoCloseable {
    private final long context, vertexLibrary, fragmentLibrary;
    private final PackCompiler.Program compiled;
    private final Map<Key, Long> pipelines = new HashMap<>();
    private final String[] textureNames;
    private boolean closed;

    private record Key(int[] descriptor) {
        Key { descriptor = descriptor.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(descriptor, key.descriptor);
        }
        @Override public int hashCode() { return Arrays.hashCode(descriptor); }
    }

    public PackProgram(long context, String name, String vertex, String fragment,
                       int[] outputs, Map<String, Integer> fixedBuffers) {
        this.context = context;
        this.compiled = PackCompiler.compile(name, vertex, fragment, outputs, fixedBuffers);
        this.vertexLibrary = MetalBridge.libraryNew(context, compiled.vertex().msl());
        try {
            this.fragmentLibrary = MetalBridge.libraryNew(context, compiled.fragment().msl());
        } catch (RuntimeException | Error e) {
            MetalBridge.release(vertexLibrary);
            throw e;
        }
        this.textureNames = new String[compiled.textureSlots().size()];
        compiled.textureSlots().forEach((name2, slot) -> textureNames[slot] = name2);
    }

    public PackCompiler.Program compiled() { return compiled; }
    public String[] textureNames() { return textureNames.clone(); }

    /** Descriptor follows MetalBridge.pipelineNew. The cache takes its own immutable copy. */
    public long pipeline(int[] descriptor) {
        if (closed) throw new IllegalStateException("Program is closed: " + compiled.name());
        return pipelines.computeIfAbsent(new Key(descriptor), key -> MetalBridge.pipelineNew(context,
            vertexLibrary, compiled.vertex().entry(), fragmentLibrary, compiled.fragment().entry(), key.descriptor));
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        pipelines.values().forEach(MetalBridge::release);
        pipelines.clear();
        MetalBridge.release(vertexLibrary);
        MetalBridge.release(fragmentLibrary);
    }
}
