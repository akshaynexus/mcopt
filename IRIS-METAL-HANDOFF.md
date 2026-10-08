# Handoff: Iris shaders on the mcopt Metal renderer

Read this file first. It holds everything a new session needs to continue the work.

## Current state — task 1, 2026-10-09

The current implementation is option (b): Iris native pass redirects call a public mcopt shader-program API.
`mcopt.metal.PackGlsl`, `PackCompiler`, `PackProgram`, and `PackUniforms` own rewriting, compilation/reflection,
resource slots, native libraries/PSOs, and uniform storage/upload. `MetalShaderTranslation` is shared with the
existing `MetalPipeline`; the optional device MSL cache includes the shared implementation in its key salt.
Iris retains pack semantics, targets and pass scheduling, and its mcopt dependency remains compileOnly.

The Iris copies DeviceGlsl/MslCompiler/UniformSink are removed. Its MetalProgram is now a small adapter.
mcopt also exposes native viewport and comparison-sampler operations, plus original-device-pipeline binding
and geometry-state restoration for redirected encoder restarts. The moved files retain LGPL-3.0 attribution
in NOTICE and LICENSE-IRIS-LGPL-3.0, including release jars.

Research: `../iris-mcopt/docs/METAL-RESEARCH.md`. Design: `../iris-mcopt/docs/METAL-PORT.md`.
Results and exact remaining limitations: `../iris-mcopt/docs/CODEX-REPORT-1.md`.

The requested verification is **offline only**. Build with Java 26, immediately run `./gradlew --stop` in each
repo after every Gradle build, run `../iris-mcopt/spike/harness/run` from its repo (requires GPU access), then
install both local jars. Do not start Minecraft or CurseForge. The user conducts the later image comparison.

Raster fixes include composite viewport, horizon, center-depth smoothing, mip sampling history, final copy,
fallback program routing, color masks, shadow alias/filter/compare semantics. Offline 99-program compilation
and a native GPU readback check cover compilation, viewport, std140 upload, LEQUAL samplers and PSO cache keys.
They do not establish full visual parity. Compute/shadowcomp, geometry/tessellation, DH, raw custom texture
extensions, full resource-pack PBR tracking, depth mip reduction and >8-attachment unions remain unsupported.

## Historical handoff (superseded where it conflicts with current state)

The following preserves the earlier investigation. Its game-launch loop and old "Next steps" are historical,
not instructions for the current offline task.

## Goal

Make Iris shader packs run on mcopt's Metal renderer (not OpenGL) on Apple Silicon. The test pack is
Complementary Reimagined r5.9.3. With Iris on OpenGL, the user gets about 30 fps, and the user wants Metal.

Work in a loop: build both mods, install them, start the game, read the logs, fix, and repeat. Continue until
Complementary draws on the Metal backend.

## Machine and tools

- Mac: MacBook Air, Apple M4, 10-core GPU, 16 GB RAM, screen 2880x1864.
- Build JDK: the default JVM is Java 17, which is too old. Use Java 26 for both repos:
  `export JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home`
- `glslc` (shaderc) is at `/opt/homebrew/bin/glslc`. `spirv-cross` and `glslangValidator` are not installed.
  The game ships LWJGL `shaderc` and `spvc` (SPIRV-Cross). Their jars are in `~/.gradle/caches/modules-2/files-2.1/org.lwjgl`.
- The mapped Minecraft 26.3 client jar: `~/.gradle/caches/fabric-loom/26.3/minecraft-client.jar`. Use `javap` from the
  Java 26 JDK to read the `com.mojang.renderpearl.*` API.
- Conversation style: follow `~/.claude/CLAUDE.md` (STE-flavored replies).

## Repositories

| Repo | Path | Branch | Notes |
|---|---|---|---|
| mcopt (Metal renderer mod) | `~/Documents/GitRepos/mcopt` | `main` | Fork of `noahdunnagan/mcopt`. Remote `upstream` is added. |
| Iris fork | `~/Documents/GitRepos/iris-mcopt` | `mcopt-metal` | Cloned from IrisShaders/Iris branch `26.3` (commit 770155cc9). LGPL-3.0. |

Nothing is committed in either repo.

### Build commands

- mcopt: `cd ~/Documents/GitRepos/mcopt && tools/release`. Output: `dist/mcopt-0.2.0-alpha.2.jar`.
- Iris fork: `cd ~/Documents/GitRepos/iris-mcopt && ./gradlew :fabric:build -x test --console=plain -q`.
  Output: `build/libs/iris-fabric-1.11.6-snapshot+mc26.3-local.jar`.

## Game instance (CurseForge)

- Instance: `~/Documents/curseforge/minecraft/Instances/MyCustommods`
- Launcher: `/Applications/CurseForge.app`. Instance name in CurseForge: MyCustommods. World: "New World".
- Mods folder now: `fabric-api-0.161.0+26.3.jar`, `iris-fabric-1.11.7+mc26.3.jar` (upstream Iris),
  `mcopt-0.2.0-alpha.2.jar` (built from mcopt with the changes below), `sodium-fabric-0.9.3-alpha.1+mc26.3.jar`.
- The fork jar is **not installed yet**. To test on Metal, replace the upstream Iris jar with the fork jar.
  Keep a copy of the upstream jar somewhere outside `mods/`, so that the user can go back to it.
- `config/iris.properties`: `shaderPack=ComplementaryReimagined_r5.9.3.zip`, `enableShaders=true`,
  `enableDebugOptions=true` (set by the old session, so that Iris writes `patched_shaders/`), `maxShadowRenderDistance=32`.
- `options.txt`: `preferredGraphicsBackend:"opengl"`. mcopt puts Metal first anyway, unless `mcopt.metal=false`.
- Logs: `logs/latest.log`, `logs/stdout-logs.txt`. Native JVM crashes: `~/Library/Logs/DiagnosticReports/java-*.ips`.

## What is done

### mcopt changes (uncommitted)

1. `metal/src/main/java/mcopt/metal/mixin/chunk/ChunkMixinPlugin.java`: added `"BlockRendererMixin", List.of("iris")`
   to `YIELDS_TO`. mcopt's `@Overwrite` of Sodium `BlockRenderer.renderModel` blocked Iris's `MixinBlockRenderer`.
2. `metal/src/main/java/mcopt/metal/Profile.java`: `distantHorizons()` is now `openGlOnlyMods()`. When upstream Iris
   (or Distant Horizons) is loaded, mcopt sets `mcopt.metal=false`, because upstream Iris makes raw GL calls and LWJGL
   aborts the JVM ("No context is current" in `SamplerLimits.<init>`). If the Iris jar declares `"mcopt:metal"` in its
   `fabric.mod.json` custom block, mcopt keeps Metal on. An explicit `-Dmcopt.metal` or `config/mcopt.properties` wins.
3. `README.md`: Known issues text updated.

### Iris fork changes (uncommitted), phase 1 "gate"

- New `common/src/main/java/net/irisshaders/iris/mixin/MetalSupport.java`: detects mod `mcopt-metal`, calls
  `mcopt.metal.Profile.apply()` by reflection, and reads `mcopt.metal`.
- `IrisMixinPlugin.java`: new `usingMetal` flag. GL-only mixins stay off on Metal, as they do on Vulkan.
- `IrisConfig.java`: `boolean vk = IrisMixinPlugin.usingVulkan || IrisMixinPlugin.usingMetal;`
- `fabric.mod.json`: declares `"mcopt:metal": 1`.
- Both jars build.

### Port plan

Read `~/Documents/GitRepos/iris-mcopt/docs/METAL-PORT.md`. It has the design and 8 phases. Summary:

1. Gate (done).
2. Shader spike: take `patched_shaders/` output, put loose uniforms into one std140 block, give explicit layouts,
   compile each with shaderc (Vulkan target) and SPIRV-Cross (MSL 3.0). List every failure.
3. Transformer: add that step to `TransformPatcher` as `ShaderTarget.DEVICE`.
4. Composite and final passes on Mojang's device API (`com.mojang.renderpearl.api.*`). First visible result.
5. gbuffers: vanilla pipelines and Sodium terrain with Iris's vertex format. mcopt must not use its own Sodium MSL
   when a pack is active.
6. Shadows: depth textures, compare samplers, clamp-to-border.
7. mcopt extensions: compute, storage textures and buffers (Complementary's `shadowcomp.csh`), mipmaps, scaled blit.
8. Speed.

## Key facts found

- Iris 26.3 has 983 classes. About 79 call `org/lwjgl/opengl` directly, 91 use Mojang's GL backend classes, and 148 use
  the `renderpearl` API. Most raw GL is in `gl/*`, `targets/*`, `shadows/*`, `pipeline/*`.
- Complementary r5.9.3: 36 `.vsh`, 36 `.fsh`, 1 `.csh` (`shadowcomp.csh`). No geometry or tessellation shaders, so it
  can fit Metal.
- mcopt's `MetalPipeline` already turns SPIR-V into MSL with SPIRV-Cross (`org.lwjgl.util.spvc`).
- mcopt comments refer to a "shaderpack runtime" (`mcopt.metal.pack`, `mcpack.m`, `mcshade.m`). That code does not
  exist: not in this repo, not in git history, not in upstream `noahdunnagan/mcopt` or its open PRs.
  `MetalHooks.setRedirector` / `setLabeler` have no callers, so `MetalHooks`, `PassDelegate` and `MetalBridge` are
  empty hooks. The Metal side must be written from zero.
- Metal limits: no geometry or tessellation shaders, at most 8 color attachments per pass.

## Progress (2026-10-06, session 2)

- The Mac crashed once from memory pressure (jetsam): Gradle daemons (2.5 GB + 1.4 GB) and the game ran together.
  Run `./gradlew --stop` after each build, before the game starts.
- `patched_shaders/` is copied to `~/Documents/GitRepos/iris-mcopt/spike/patched/` (270 files).
- Phase 2 is done: 198 of 198 stages compile GLSL -> SPIR-V -> MSL -> Metal library. See "Phase 2 result" in
  `docs/METAL-PORT.md`. Tools: `spike/spike.py`, `spike/mtlcheck`. `spirv-cross` is now installed with Homebrew.
- OpenGL baseline confirmed by the user: 31 fps.
- Next: phase 3 (the rewrite in `TransformPatcher`), then phase 4.


## Progress (2026-10-06, session 2, later)

Status: Complementary runs on Metal (deferred, composite, final, gbuffers, shadows). The image still looks much worse
than OpenGL. Paused at the user's request.

Installed now in `MyCustommods/mods`: the fork jar `iris-fabric-1.11.6-snapshot+mc26.3-local.jar` and the mcopt jar
built from this repo. Upstream Iris is in `MyCustommods/iris-upstream-backup/`. To go back to OpenGL: move it back into
`mods/` and delete the fork jar.

Code (all uncommitted):
- mcopt: `MetalBridge` (pipeline states, binding, draws, mipmaps, `createInfo`), `MetalDevice.instance`,
  `MetalPipeline.info`, native `mc_generate_mipmaps`, constant vertex step in `mc_pipeline_new`.
- Iris fork, package `net.irisshaders.iris.metal`: `DeviceGlsl` (GLSL rewrite), `MslCompiler` (shaderc + SPIRV-Cross),
  `UniformSink`/`MetalUniforms` (Iris uniforms into a std140 block), `MetalProgram`, `MetalTargets`,
  `MetalCustomTextures`, `MetalPackPipeline` (replaces IrisRenderingPipeline on Metal), `MetalGbuffers` (redirect +
  PassDelegate for gbuffers and shadows). Mixin gating in `IrisMixinPlugin.GL_ONLY`; new `MixinRenderSystem_MetalOnly`.
  GL guards in `IrisRenderSystem`, `GLDebug`, `SamplerLimits`, `StandardMacros`, `ShadowRenderer`, uniform classes.
- Loop tools (scratchpad, recreate if gone): build+install script, log watcher, window screenshot via
  `screencapture -l<window id>`. Start the game with CurseForge's Play button; the user enters the world.
- Offline check: `iris-mcopt/spike/harness/MetalSpike.java` builds every patched program into a Metal pipeline state.

Fixed in the last round but NOT yet seen in game: shadow convention as GL (clear 0, GEQUAL test and compare),
blend "off" and per-buffer blend overrides, PBR default textures for normals/specular, GL's full gbuffer uniform set
(BuiltinReplacementUniforms, VanillaUniforms, FogMode.PER_VERTEX), custom pack textures, mipmaps, per-frame custom
uniform push and per-pipeline state cache (heat).

Next: run the game, compare screenshots with the OpenGL run, and check these known gaps:
- Viewport scale of composite passes is ignored (warning in log).
- Sky disc / HorizonRenderer is not drawn; `iris_centerDepthSmooth` is not bound.
- Gbuffer programs past 8 color targets, geometry/tessellation programs, compute, SSBOs, images: not supported.
- Programs with no key or no source drop their draws (see "Metal:" warnings in latest.log).
- Shadowcomp passes do not run (Complementary disables them by default).
- Verify depth: main world keeps vanilla reversed Z; Iris's zero-to-one transforms invert depth reads.
- Memory: run `./gradlew --stop` after builds; the Mac crashed once from memory pressure.

## Next steps

1. Get `patched_shaders/`. Start the game once with the current setup (upstream Iris on OpenGL, Complementary),
   open "New World", wait about 10 seconds, and quit. Iris writes the patched sources to
   `MyCustommods/patched_shaders/`.
   - With computer use: open CurseForge, click Play on MyCustommods, then Singleplayer > New World.
   - The user stopped a shell launch attempt earlier. Ask before you start the game from the shell.
2. Do phase 2 (the shader spike) offline on those files. Record the failures.
3. Do phases 3 and 4. Install the fork jar in place of upstream Iris, start the game on Metal, read the logs and
   screenshots, fix, and repeat.
4. Continue through the phases until Complementary draws on Metal. Measure fps against the 30 fps OpenGL baseline.

## Source of this handoff

Old session: `8eba64df-b938-412d-8899-4c0361324b57`. Transcript:
`~/.claude/projects/-Users-akshaycm-Documents-GitRepos-mcopt/8eba64df-b938-412d-8899-4c0361324b57.jsonl`.
