// Renderium - ChunkManager 防腐层 Mixin (Thin Glue)
// 仅调用 RenderiumACL，不做任何逻辑，<=10行/方法

package com.ranecc.renderium.platform.mixin;

import com.ranecc.renderium.platform.bridge.acl.RenderiumACL;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ChunkManager 防腐层 Mixin — 截获区块加载/卸载/变化事件，委托 ACL。
 *
 * <p>Thin Glue 约束:
 * <ul>
 *   <li>方法体 <= 10 行</li>
 *   <li>仅调用 RenderiumACL</li>
 *   <li>不 @Shadow 内部字段</li>
 * </ul>
 */
@Mixin(ClientLevel.class)
public abstract class MixinChunkManagerACL {

    /**
     * 区块卸载 —— 通知 RenderiumACL。
     * ChunkPos 使用 getX()/getZ() 公开方法访问。
     */
    @Inject(method = "unload", at = @At("HEAD"))
    private void onChunkUnload(LevelChunk chunk, CallbackInfo ci) {
        RenderiumACL acl = RenderiumACL.getInstance();
        if (!acl.isReady()) return;
        acl.onChunkRemoved(chunk.getPos().x(), 0, chunk.getPos().z());
    }
}
