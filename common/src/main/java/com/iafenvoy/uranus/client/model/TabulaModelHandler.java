package com.iafenvoy.uranus.client.model;

import com.google.gson.*;
import com.iafenvoy.uranus.client.model.tabula.TabulaModelContainer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;

/**
 * @author pau101
 * @since 1.0.0
 */
@Environment(EnvType.CLIENT)
public enum TabulaModelHandler implements JsonDeserializationContext {
    INSTANCE;

    // 26.2: ItemTransform/ItemTransforms moved to net.minecraft.client.resources.model.cuboid
    // and became vanilla item-model internals (Deserializer is now protected). The .tbl parser
    // never deserialized those types — Tabula containers use their own Transform POJO — so the
    // Gson adapters were incidental legacy and are removed; .tbl geometry semantics are unchanged.
    private final Gson gson = new GsonBuilder().create();

    public TabulaModelContainer loadTabulaModel(InputStream stream) {
        return this.gson.fromJson(new InputStreamReader(stream), TabulaModelContainer.class);
    }

    @Override
    public <T> T deserialize(JsonElement json, Type type) throws JsonParseException {
        return this.gson.fromJson(json, type);
    }
}