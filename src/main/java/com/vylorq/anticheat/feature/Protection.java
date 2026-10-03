package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.core.blocklog.BlockChange;
import com.vylorq.anticheat.core.claims.Claim;
import com.vylorq.anticheat.core.claims.ClaimAction;
import com.vylorq.anticheat.core.evidence.EvidenceEvent;
import com.vylorq.anticheat.core.lobby.Lobby;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.trade.SecureTrade;
import com.vylorq.anticheat.gui.OfflineInventory;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.vehicle.VehicleEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BucketItem;
import net.minecraft.item.FireChargeItem;
import net.minecraft.item.FlintAndSteelItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/** Registers the gameplay event hooks and applies every protection rule. */
public final class Protection {
    private static final ThreadLocal<Boolean> ENCLOSED = ThreadLocal.withInitial(() -> false);

    private Protection() {
    }

    /** Frozen, waiting and jailed players can't change the world. */
    private static boolean locked(ServerPlayerEntity p) {
        Ac ac = Ac.get();
        return ac.staff.isFrozen(p.getUuid()) || WaitingRoomFeature.waiting(p) || ac.jail.isJailed(p.getUuid())
                || ScareWarning.pending(p);
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (Ac.running()) {
                OfflineInventory.onJoin(handler.player);
                Joins.onJoin(handler.player);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (Ac.running()) {
                Joins.onLeave(handler.player);
            }
        });
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) ->
                !Ac.running() || (!Teams.onChat(sender, message.getContent().getString())
                        && ChatFeature.allow(sender, message.getContent().getString())));

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (!Ac.running() || !(player instanceof ServerPlayerEntity p) || !(world instanceof ServerWorld w)) {
                return true;
            }
            if (locked(p)) {
                Movement.ghostBlock(p);
                return false;
            }
            if (BuilderMode.is(p) && (!BuilderMode.mayBreak(w, pos) || !BuilderMode.mayBuildAt(p, w, pos))) {
                BuilderMode.denied(p);
                Movement.ghostBlock(p);
                return false;
            }
            if (!LobbyFeature.allowed(p, w, pos, Lobby.Action.BREAK) || !Claims.check(p, w, pos, ClaimAction.BREAK)) {
                Movement.ghostBlock(p);
                return false;
            }
            if (Ac.get().shadow.isShadowed(p.getUuid())) {
                // Shadow mode: they see the block vanish, nobody else does.
                p.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos, Blocks.AIR.getDefaultState()));
                return false;
            }
            ENCLOSED.set(Xray.wasHiddenOre(w, pos));
            return true;
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (!Ac.running() || !(player instanceof ServerPlayerEntity p) || !(world instanceof ServerWorld w)) {
                return;
            }
            BlockLog.log(p, null, w, pos, BlockChange.Kind.BREAK, state, w.getBlockState(pos), be);
            Ac.get().evidence.record(p.getUuid(), EvidenceEvent.Type.BREAK, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(),
                    "broke " + Mc.blockId(state.getBlock()).replace("minecraft:", "") + " at " + pos.toShortString());
            Xray.afterBreak(p, w, pos, state, ENCLOSED.get());
            ENCLOSED.set(false);
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> {
            if (!Ac.running() || !(player instanceof ServerPlayerEntity p) || !(world instanceof ServerWorld w)) {
                return ActionResult.PASS;
            }
            if (Tools.is(p.getMainHandStack(), Tools.BUILDER_WAND)) {
                BuilderTools.corner(p, w, pos, true);
                return ActionResult.FAIL;
            }
            if (Tools.is(p.getMainHandStack(), Tools.INSPECTOR)) {
                if (Perms.require(p, Perm.INSPECTOR_TOOL)) {
                    BlockLog.inspect(p, w, pos);
                }
                return ActionResult.FAIL;
            }
            if (Tools.is(p.getMainHandStack(), Tools.CLAIM_STICK) || Tools.is(p.getMainHandStack(), Tools.TRADER_STICK)) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!Ac.running() || !(player instanceof ServerPlayerEntity p) || !(world instanceof ServerWorld w)) {
                return ActionResult.PASS;
            }
            return useBlock(p, w, hand, hit);
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!Ac.running() || !(player instanceof ServerPlayerEntity p) || !(world instanceof ServerWorld w)) {
                return ActionResult.PASS;
            }
            return useItem(p, w, hand);
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!Ac.running() || !(player instanceof ServerPlayerEntity p)) {
                return ActionResult.PASS;
            }
            if (Shops.isShop(entity)) {
                if (hand == net.minecraft.util.Hand.MAIN_HAND) {
                    Shops.click(p, entity);
                }
                return ActionResult.FAIL;
            }
            if (Booths.isBooth(entity)) {
                if (hand == net.minecraft.util.Hand.MAIN_HAND) {
                    Booths.click(p, entity);
                }
                return ActionResult.FAIL;
            }
            if (BuilderMode.is(p)) {
                BuilderMode.denied(p);
                return ActionResult.FAIL;
            }
            return useEntity(p, hand, entity);
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!Ac.running() || !(player instanceof ServerPlayerEntity p)) {
                return ActionResult.PASS;
            }
            if (Shops.isShop(entity)) {
                Shops.click(p, entity);
                return ActionResult.FAIL;
            }
            if (Booths.isBooth(entity)) {
                Booths.click(p, entity);
                return ActionResult.FAIL;
            }
            if (BuilderMode.is(p)) {
                BuilderMode.denied(p);
                return ActionResult.FAIL;
            }
            return attackEntity(p, entity);
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!Ac.running()) {
                return true;
            }
            if (Traders.isTrader(entity) || Shops.isShop(entity) || Booths.isBooth(entity)) {
                return false;
            }
            if (!(entity instanceof ServerPlayerEntity p)) {
                return true;
            }
            if (BuilderMode.is(p) || ScareWarning.pending(p)) {
                // Builders can't die (dying would drop their items); nobody is hurt while reading the warning.
                return false;
            }
            Ac ac = Ac.get();
            if (WaitingRoomFeature.waiting(p) || Arenas.isCountdownFrozen(p)) {
                return false;
            }
            Entity attacker = source.getAttacker();
            if (attacker instanceof ServerPlayerEntity ap && ap != p) {
                if (ac.shadow.isShadowed(ap.getUuid())) {
                    return false;
                }
                if (Teams.damageBlocked(ap, p)) {
                    return false;
                }
                var am = ac.arenas.matchOf(ap.getUuid());
                if (am != null && am.sameTeam(ap.getUuid(), p.getUuid())) {
                    return false;
                }
                if (!pvpAllowed(ap, p)) {
                    return false;
                }
            }
            if (LobbyFeature.in(p)) {
                if (Ac.config().lobby.noFallDamage && source.isIn(DamageTypeTags.IS_FALL)) {
                    return false;
                }
                if (Ac.config().lobby.noPvp && attacker instanceof ServerPlayerEntity) {
                    return false;
                }
            }
            return true;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (Ac.running() && entity instanceof ServerPlayerEntity p && taken > 0) {
                Deaths.onDamage(p, source, taken);
            }
        });
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!Ac.running() || !(entity instanceof ServerPlayerEntity p)) {
                return true;
            }
            ServerPlayerEntity killer = source.getAttacker() instanceof ServerPlayerEntity k ? k : null;
            if (Arenas.onDeath(p, killer)) {
                return false;
            }
            Trades.cancelFor(p, SecureTrade.CancelReason.DIED);
            Deaths.onDeath(p, source);
            Teams.onKill(p, killer);
            Markets.onKill(p, killer);
            return true;
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!Ac.running()) {
                return;
            }
            PlayerSession s = Ac.session(newPlayer);
            s.teleported();
            s.ticksSinceJoin = 0;
            Deaths.afterRespawn(newPlayer);
            Barriers.afterRespawn(newPlayer);
            if (Jail.isJailed(newPlayer)) {
                Jail.toCell(newPlayer);
            }
            if (WaitingRoomFeature.waiting(newPlayer)) {
                WaitingRoomFeature.onJoin(newPlayer);
            }
        });
        // Claims with mob spawning off: new hostile mobs are removed as they appear.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (Ac.running() && entity instanceof net.minecraft.entity.mob.HostileEntity && entity.age == 0) {
                Claim c = Ac.get().claims.at(Mc.worldId(world), entity.getX(), entity.getZ());
                if (c != null && c.isActive() && !c.settings.mobSpawning) {
                    entity.discard();
                }
            }
        });
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            if (!Ac.running()) {
                return;
            }
            PlayerSession s = Ac.session(player);
            s.ticksSinceDimension = 0;
            s.teleported();
            Trades.cancelFor(player, SecureTrade.CancelReason.TELEPORTED);
        });
    }

    /** PvP rules from claims (both the attacker's and the victim's position). */
    static boolean pvpAllowed(ServerPlayerEntity attacker, ServerPlayerEntity victim) {
        if (Arenas.inMatch(attacker) && Arenas.inMatch(victim)) {
            return true;
        }
        for (ServerPlayerEntity where : new ServerPlayerEntity[]{attacker, victim}) {
            Claim c = Ac.get().claims.at(Mc.worldId(where.getEntityWorld()), where.getX(), where.getZ());
            if (c != null && c.isActive() && !Ac.get().claims.can(c, attacker.getUuid(), false, ClaimAction.PVP, true)) {
                return false;
            }
        }
        return true;
    }

    private static ClaimAction classify(BlockState st, BlockEntity be) {
        if (be instanceof Inventory || st.isOf(Blocks.ENDER_CHEST) || st.isOf(Blocks.LECTERN) || st.isOf(Blocks.JUKEBOX)
                || st.isOf(Blocks.CHISELED_BOOKSHELF) || st.isOf(Blocks.DECORATED_POT) || st.isOf(Blocks.CRAFTER)) {
            return st.isOf(Blocks.ENDER_CHEST) ? ClaimAction.DOOR : ClaimAction.CONTAINER;
        }
        if (st.getBlock() instanceof DoorBlock || st.getBlock() instanceof TrapdoorBlock || st.getBlock() instanceof FenceGateBlock) {
            return ClaimAction.DOOR;
        }
        if (st.getBlock() instanceof ButtonBlock || st.getBlock() instanceof LeverBlock || st.getBlock() instanceof AbstractPressurePlateBlock) {
            return ClaimAction.DOOR;
        }
        if (st.isOf(Blocks.REPEATER) || st.isOf(Blocks.COMPARATOR) || st.isOf(Blocks.DAYLIGHT_DETECTOR) || st.isOf(Blocks.NOTE_BLOCK)) {
            return ClaimAction.REDSTONE;
        }
        // Blocks whose right-click changes them: signs, pots, cakes, candles, cauldrons, hives, anchors...
        if (st.getBlock() instanceof net.minecraft.block.AbstractSignBlock || st.getBlock() instanceof net.minecraft.block.FlowerPotBlock
                || st.getBlock() instanceof net.minecraft.block.CakeBlock || st.getBlock() instanceof net.minecraft.block.CandleBlock
                || st.getBlock() instanceof net.minecraft.block.CandleCakeBlock || st.getBlock() instanceof net.minecraft.block.AbstractCauldronBlock
                || st.getBlock() instanceof net.minecraft.block.BeehiveBlock || st.isOf(Blocks.COMPOSTER) || st.isOf(Blocks.RESPAWN_ANCHOR)
                || st.isOf(Blocks.CAMPFIRE) || st.isOf(Blocks.SOUL_CAMPFIRE) || st.isOf(Blocks.SWEET_BERRY_BUSH)
                || st.isOf(Blocks.CAVE_VINES) || st.isOf(Blocks.CAVE_VINES_PLANT) || st.isOf(Blocks.PUMPKIN)
                || st.getBlock() instanceof net.minecraft.block.BedBlock) {
            return ClaimAction.PLACE;
        }
        return null;
    }

    /** Tells a player the End is closed (at most once every 3 seconds). */
    public static void endClosed(ServerPlayerEntity p) {
        long now = System.currentTimeMillis();
        Long last = END_TOLD.get(p.getUuid());
        if (last == null || now - last > 3000) {
            END_TOLD.put(p.getUuid(), now);
            Msg.actionBar(p, Msg.trFor(p, "end.closed"));
        }
    }

    private static final java.util.Map<java.util.UUID, Long> END_TOLD = new java.util.concurrent.ConcurrentHashMap<>();

    private static ActionResult useBlock(ServerPlayerEntity p, ServerWorld w, Hand hand, BlockHitResult hit) {
        Ac ac = Ac.get();
        BlockPos pos = hit.getBlockPos();
        ItemStack stack = p.getStackInHand(hand);
        if (BuilderMode.is(p) && Tools.toolOf(stack) == null && (!BuilderMode.allowed(stack)
                || (!BuilderMode.mayUse(w, pos) && !(p.isSneaking() && !stack.isEmpty())))) {
            // Builders place blocks; they don't open containers or menus (sneaking places against them instead).
            BuilderMode.denied(p);
            p.playerScreenHandler.syncState();
            return ActionResult.FAIL;
        }
        if (stack.isOf(net.minecraft.item.Items.ENDER_EYE) && w.getBlockState(pos).isOf(Blocks.END_PORTAL_FRAME)
                && !EndLock.allowedAt(w, pos)) {
            endClosed(p);
            p.playerScreenHandler.syncState();
            return ActionResult.FAIL;
        }
        String tool = Tools.toolOf(stack);
        if (tool != null && hand == Hand.MAIN_HAND) {
            switch (tool) {
                case Tools.CLAIM_STICK -> {
                    if (Perms.require(p, Perm.CLAIM)) {
                        corner(p, w, pos);
                    }
                    return ActionResult.FAIL;
                }
                case Tools.TRADER_STICK -> {
                    Traders.stickOnBlock(p, w, pos);
                    return ActionResult.FAIL;
                }
                case Tools.BUILDER_WAND -> {
                    BuilderTools.corner(p, w, pos, false);
                    return ActionResult.FAIL;
                }
                case Tools.BUILDER_MENU -> {
                    if (BuilderTools.canUse(p)) {
                        com.vylorq.anticheat.gui.BuilderMenu.open(p);
                    }
                    return ActionResult.FAIL;
                }
                case Tools.BUILDER_BRUSH -> {
                    if (BuilderTools.canUse(p)) {
                        BuilderTools.useBrush(p);
                    }
                    return ActionResult.FAIL;
                }
                case Tools.INSPECTOR -> {
                    if (Perms.require(p, Perm.INSPECTOR_TOOL)) {
                        BlockLog.inspect(p, w, pos);
                    }
                    return ActionResult.FAIL;
                }
                default -> {
                }
            }
        }
        if (ac.staff.isFrozen(p.getUuid()) || WaitingRoomFeature.waiting(p)) {
            return ActionResult.FAIL;
        }
        BlockState st = w.getBlockState(pos);
        BlockEntity be = w.getBlockEntity(pos);
        ClaimAction action = classify(st, be);
        boolean sneakingWithItem = p.isSneaking() && !stack.isEmpty();
        if (action == null || sneakingWithItem) {
            // Using an item on a block (placing is handled separately; buckets, fire and tools here).
            if (stack.getItem() instanceof BlockItem) {
                return ActionResult.PASS;
            }
            if (stack.isEmpty()) {
                return ActionResult.PASS;
            }
            BlockPos target = pos.offset(hit.getSide());
            ClaimAction a = stack.getItem() instanceof BucketItem ? ClaimAction.BUCKET
                    : (stack.getItem() instanceof FlintAndSteelItem || stack.getItem() instanceof FireChargeItem) ? ClaimAction.FIRE
                    : ClaimAction.PLACE;
            BlockPos check = a == ClaimAction.PLACE ? pos : target;
            if (ac.jail.isJailed(p.getUuid()) || !LobbyFeature.allowed(p, w, check, Lobby.Action.OTHER_INTERACT)
                    || !Claims.check(p, w, check, a)) {
                p.currentScreenHandler.syncState();
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        }
        if (ac.jail.isJailed(p.getUuid()) && action == ClaimAction.CONTAINER) {
            return ActionResult.FAIL;
        }
        Lobby.Action la = switch (action) {
            case CONTAINER -> Lobby.Action.CHEST;
            case DOOR -> Lobby.Action.DOOR;
            default -> Lobby.Action.OTHER_INTERACT;
        };
        if (!LobbyFeature.allowed(p, w, pos, la)) {
            return ActionResult.FAIL;
        }
        if (action == ClaimAction.CONTAINER && be instanceof Inventory inv && LobbyFeature.openChest(p, w, pos, inv)) {
            return ActionResult.SUCCESS;
        }
        if (!Claims.check(p, w, pos, action)) {
            return ActionResult.FAIL;
        }
        if (action == ClaimAction.CONTAINER) {
            if (StaffTools.openSilently(p, w, pos, st)) {
                return ActionResult.SUCCESS;
            }
            if (be instanceof Inventory inv) {
                ContainerLog.opened(p, w, pos, inv);
            }
            if (ac.watchlist.isWatched(p.getUuid()) && Ac.config().watchlist.logContainers) {
                ac.logs.activity(System.currentTimeMillis(), p.getUuid(), "container",
                        "opened " + Mc.blockId(st.getBlock()).replace("minecraft:", "") + " at " + pos.toShortString());
            }
            ac.evidence.record(p.getUuid(), EvidenceEvent.Type.INVENTORY, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(),
                    "opened " + Mc.blockId(st.getBlock()).replace("minecraft:", "") + " at " + pos.toShortString());
        }
        return ActionResult.PASS;
    }

    private static void corner(ServerPlayerEntity p, ServerWorld w, BlockPos pos) {
        PlayerSession s = Ac.session(p);
        String world = Mc.worldId(w);
        if (!world.equals(s.cornerWorld)) {
            s.corner1 = null;
            s.corner2 = null;
            s.nextCornerIsSecond = false;
        }
        s.cornerWorld = world;
        if (!s.nextCornerIsSecond) {
            s.corner1 = pos;
            s.corner2 = null;
            Msg.send(p, "claim.corner1", pos.toShortString());
        } else {
            s.corner2 = pos;
            int dx = Math.abs(s.corner1.getX() - pos.getX()) + 1;
            int dz = Math.abs(s.corner1.getZ() - pos.getZ()) + 1;
            Msg.send(p, "claim.corner2", pos.toShortString(), dx, dz);
        }
        s.nextCornerIsSecond = !s.nextCornerIsSecond;
    }

    private static ActionResult useItem(ServerPlayerEntity p, ServerWorld w, Hand hand) {
        Ac ac = Ac.get();
        if (ac.staff.isFrozen(p.getUuid()) || WaitingRoomFeature.waiting(p)) {
            return ActionResult.FAIL;
        }
        ItemStack stack = p.getStackInHand(hand);
        if (Tools.is(stack, Tools.BUILDER_MENU) || Tools.is(stack, Tools.BUILDER_WAND) || Tools.is(stack, Tools.BUILDER_BRUSH)) {
            if (Tools.is(stack, Tools.BUILDER_MENU) && BuilderTools.canUse(p)) {
                com.vylorq.anticheat.gui.BuilderMenu.open(p);
            } else if (Tools.is(stack, Tools.BUILDER_BRUSH) && BuilderTools.canUse(p)) {
                BuilderTools.useBrush(p);
            }
            return ActionResult.FAIL;
        }
        if (BuilderMode.is(p) && !BuilderMode.allowed(stack)) {
            BuilderMode.denied(p);
            p.playerScreenHandler.syncState();
            return ActionResult.FAIL;
        }
        if (stack.isOf(Items.ENDER_EYE) && !EndLock.open()) {
            // Nothing to find while the End is closed.
            endClosed(p);
            p.playerScreenHandler.syncState();
            return ActionResult.FAIL;
        }
        if (stack.isOf(Items.FIREWORK_ROCKET) && p.isGliding()) {
            Ac.session(p).ticksSinceFirework = 0;
        }
        if (stack.getItem() instanceof BucketItem) {
            HitResult hit = p.raycast(5.0, 1f, stack.isOf(Items.BUCKET));
            if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
                BlockPos target = stack.isOf(Items.BUCKET) ? bh.getBlockPos() : bh.getBlockPos().offset(bh.getSide());
                if (ac.jail.isJailed(p.getUuid()) || !LobbyFeature.allowed(p, w, target, Lobby.Action.BUCKET)
                        || !Claims.check(p, w, target, ClaimAction.BUCKET)) {
                    p.currentScreenHandler.syncState();
                    return ActionResult.FAIL;
                }
            }
        }
        return ActionResult.PASS;
    }

    private static ActionResult useEntity(ServerPlayerEntity p, Hand hand, Entity entity) {
        Ac ac = Ac.get();
        if (Traders.isTrader(entity)) {
            if (hand == Hand.MAIN_HAND) {
                if (Tools.is(p.getMainHandStack(), Tools.TRADER_STICK)) {
                    Traders.stickOnEntity(p, entity);
                } else {
                    var t = Traders.of(entity);
                    if (t != null) {
                        Traders.open(p, t);
                    }
                }
            }
            // Leads, name tags and vanilla trading never apply to traders.
            return ActionResult.SUCCESS;
        }
        if (ac.staff.isFrozen(p.getUuid()) || WaitingRoomFeature.waiting(p)) {
            return ActionResult.FAIL;
        }
        ClaimAction a;
        Lobby.Action la;
        if (entity instanceof ItemFrameEntity) {
            a = ClaimAction.ITEM_FRAME;
            la = Lobby.Action.ITEM_FRAME;
        } else if (entity instanceof ArmorStandEntity) {
            a = ClaimAction.ARMOR_STAND;
            la = Lobby.Action.ARMOR_STAND;
        } else if (entity instanceof VehicleEntity || entity instanceof net.minecraft.entity.passive.AbstractHorseEntity) {
            a = ClaimAction.VEHICLE;
            la = Lobby.Action.SIT;
        } else if (entity instanceof AnimalEntity || entity instanceof VillagerEntity) {
            a = ClaimAction.ENTITY;
            la = Lobby.Action.OTHER_INTERACT;
        } else {
            return ActionResult.PASS;
        }
        BlockPos pos = entity.getBlockPos();
        if (!LobbyFeature.allowed(p, (ServerWorld) entity.getEntityWorld(), pos, la) || !Claims.check(p, entity.getEntityWorld(), pos, a)) {
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    private static ActionResult attackEntity(ServerPlayerEntity p, Entity entity) {
        Ac ac = Ac.get();
        if (Traders.isTrader(entity)) {
            return ActionResult.FAIL;
        }
        if (locked(p) || Arenas.isCountdownFrozen(p)) {
            return ActionResult.FAIL;
        }
        if (entity instanceof ServerPlayerEntity victim) {
            if (WaitingRoomFeature.waiting(victim) || (LobbyFeature.in(victim) && Ac.config().lobby.noPvp)) {
                return ActionResult.FAIL;
            }
            var m = ac.arenas.matchOf(p.getUuid());
            if (m != null && m.sameTeam(p.getUuid(), victim.getUuid())) {
                return ActionResult.FAIL;
            }
            if (!pvpAllowed(p, victim)) {
                Msg.actionBar(p, Msg.tr("claim.no-pvp"));
                return ActionResult.FAIL;
            }
        } else {
            ClaimAction a = null;
            Lobby.Action la = null;
            if (entity instanceof ItemFrameEntity || entity instanceof AbstractDecorationEntity) {
                a = ClaimAction.ITEM_FRAME;
                la = Lobby.Action.PAINTING;
            } else if (entity instanceof ArmorStandEntity) {
                a = ClaimAction.ARMOR_STAND;
                la = Lobby.Action.ARMOR_STAND;
            } else if (entity instanceof VehicleEntity) {
                a = ClaimAction.VEHICLE;
                la = Lobby.Action.OTHER_INTERACT;
            } else if (entity instanceof AnimalEntity || entity instanceof VillagerEntity) {
                a = ClaimAction.ENTITY;
                la = Lobby.Action.OTHER_INTERACT;
            }
            if (a != null) {
                BlockPos pos = entity.getBlockPos();
                if (!LobbyFeature.allowed(p, (ServerWorld) entity.getEntityWorld(), pos, la) || !Claims.check(p, entity.getEntityWorld(), pos, a)) {
                    return ActionResult.FAIL;
                }
            }
        }
        if (!Combat.onAttack(p, entity)) {
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    /** Block placement (called from the BlockItem mixin). @return false to cancel. */
    public static boolean canPlace(ServerPlayerEntity p, ServerWorld w, BlockPos pos) {
        if (!Ac.running()) {
            return true;
        }
        if (locked(p)) {
            return false;
        }
        if (BuilderMode.is(p) && !BuilderMode.mayBuildAt(p, w, pos)) {
            Msg.actionBar(p, Msg.trFor(p, "builder.lobby-only"));
            return false;
        }
        if (!LobbyFeature.allowed(p, w, pos, Lobby.Action.PLACE) || !Claims.check(p, w, pos, ClaimAction.PLACE)) {
            return false;
        }
        return true;
    }

    /** After a placement succeeded (logging, shadow mode, evidence). */
    public static void afterPlace(ServerPlayerEntity p, ServerWorld w, BlockPos pos, BlockState before) {
        Ac ac = Ac.get();
        BlockState after = w.getBlockState(pos);
        if (ac.shadow.isShadowed(p.getUuid())) {
            // Shadow mode: undo for everyone, but keep showing it to them.
            w.setBlockState(pos, before, net.minecraft.block.Block.NOTIFY_LISTENERS);
            p.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos, after));
            return;
        }
        BlockLog.log(p, null, w, pos, BlockChange.Kind.PLACE, before, after, null);
        if (BuilderMode.is(p)) {
            BuilderLog.change(p.getUuid(), "PLACE", w, pos, before, after, "hand");
        }
        ac.evidence.record(p.getUuid(), EvidenceEvent.Type.PLACE, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(),
                "placed " + Mc.blockId(after.getBlock()).replace("minecraft:", "") + " at " + pos.toShortString());
    }
}
