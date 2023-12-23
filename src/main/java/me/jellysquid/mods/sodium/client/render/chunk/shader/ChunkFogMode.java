package me.jellysquid.mods.sodium.client.render.chunk.shader;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.function.Function;

public enum ChunkFogMode {
    NONE {
        @Override
        public List<String> getDefines() {
            return ImmutableList.of();
        }
    },

    SMOOTH {
        @Override
        public List<String> getDefines() {
            return ImmutableList.of("USE_FOG", "USE_FOG_SMOOTH");
        }
    };

    private static final List<String> NONE_DEFINES = ImmutableList.of();

    private final Function<ShaderBindingContext, ChunkShaderFogComponent> factory;

    ChunkFogMode(Function<ShaderBindingContext, ChunkShaderFogComponent> factory) {
        this.factory = factory;
    }

    public Function<ShaderBindingContext, ChunkShaderFogComponent> getFactory() {
        return this.factory;
    }

    public static ChunkFogMode fromDefines(List<String> defines) {
        if (defines.contains("USE_FOG")) {
            if (defines.contains("USE_FOG_SMOOTH")) {
                return SMOOTH;
            } else {
                return NONE;
            }
        } else {
            return NONE;
        }
    }
}

