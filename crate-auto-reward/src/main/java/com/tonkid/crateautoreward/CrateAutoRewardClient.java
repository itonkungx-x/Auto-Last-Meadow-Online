package com.tonkid.crateautoreward;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class CrateAutoRewardClient implements ClientModInitializer {
    private static final int ANVIL_SLOT = 42;
    private enum Phase { OFF, NEXT, PICKUP, TYPE, RETURN }
    private final ArrayDeque<ItemStack> pending = new ArrayDeque<>();
    private final List<ItemStack> seen = new ArrayList<>();
    private Phase phase = Phase.OFF;
    private int phaseTicks = 0, done = 0, skipped = 0, sourceSlot = -1;
    private ItemStack active = ItemStack.EMPTY;
    private KeyMapping toggle, emergency;

    @Override public void onInitializeClient() {
        toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.crateautoreward.toggle", InputConstants.Type.KEYBOARD,
            InputConstants.KEY_F6, KeyMapping.Category.MISC));
        emergency = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.crateautoreward.stop", InputConstants.Type.KEYBOARD,
            InputConstants.KEY_F8, KeyMapping.Category.MISC));
        ScreenEvents.BEFORE_INIT.register((mc, screen, width, height) ->
            ScreenKeyboardEvents.beforeKeyPress(screen).register((s, key) -> {
                if (emergency.matches(key)) stop(mc, "Stopped by F8.");
                else if (toggle.matches(key)) {
                    if (phase == Phase.OFF) start(mc);
                    else stop(mc, "Stopped by F6.");
                }
            })
        );
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void start(Minecraft mc) {
        AbstractContainerMenu menu = menu(mc);
        if (mc.player == null || mc.gameMode == null || !rewards(mc.gui.screen()) || menu == null) {
            say(mc, "Open Crate Rewards, then press F6.");
            return;
        }
        if (menu.slots.size() <= ANVIL_SLOT || !menu.getSlot(ANVIL_SLOT).getItem().is(Items.ANVIL) ||
            !menu.getCarried().isEmpty()) {
            say(mc, "Missing Anvil in slot 42, or cursor is holding an item.");
            return;
        }
        pending.clear(); seen.clear(); done=0; skipped=0; sourceSlot=-1; active=ItemStack.EMPTY;
        for (Slot slot : menu.slots) {
            ItemStack item = slot.getItem();
            if (slot.container != mc.player.getInventory() || item.isEmpty()) continue;
            if (seen.stream().anyMatch(old -> ItemStack.isSameItemSameComponents(old,item))) skipped++;
            else {seen.add(item.copy()); pending.addLast(item.copy());}
        }
        if (pending.isEmpty()) { say(mc,"No items in inventory."); return; }
        phase = Phase.NEXT; phaseTicks = 0;
        say(mc,"Started "+pending.size()+" unique items ("+skipped+" duplicates skipped). F8 stops.");
    }

    private void tick(Minecraft mc) {
        if (phase == Phase.OFF) return;
        phaseTicks++;
        if (mc.player == null || mc.gameMode == null) { stop(mc,"Disconnected."); return; }
        switch(phase) {
            case NEXT -> next(mc);
            case PICKUP -> pickup(mc);
            case TYPE -> clickItemReward(mc);
            case RETURN -> returned(mc);
            default -> {}
        }
    }

    private void next(Minecraft mc) {
        if (phaseTicks < 12) return;
        AbstractContainerMenu menu = menu(mc);
        if (!rewards(mc.gui.screen()) || menu==null || !menu.getCarried().isEmpty()) {
            stop(mc,"Unexpected Crate Rewards state or cursor occupied."); return;
        }
        if (menu.slots.size() <= ANVIL_SLOT || !menu.getSlot(ANVIL_SLOT).getItem().is(Items.ANVIL)) {
            stop(mc,"Anvil has changed. Stopped."); return;
        }
        if (pending.isEmpty()) { stop(mc,"Finished "+done+" rewards. Skipped "+skipped+" duplicate stacks."); return; }
        active=pending.peekFirst(); sourceSlot=-1;
        for (int i=0;i<menu.slots.size();i++) {
            Slot slot=menu.slots.get(i);
            if (slot.container==mc.player.getInventory() &&
                ItemStack.isSameItemSameComponents(slot.getItem(), active)) { sourceSlot=i; break; }
        }
        if (sourceSlot<0) {
            pending.removeFirst();skipped++;say(mc,"Inventory item missing; skipped.");phaseTicks=0;return;
        }
        clickSlot(mc,menu,sourceSlot);
        phase=Phase.PICKUP;phaseTicks=0;
    }

    private void pickup(Minecraft mc) {
        AbstractContainerMenu menu = menu(mc);
        if (!rewards(mc.gui.screen()) || menu==null) {
            stop(mc,"Crate Rewards changed during pickup.");return;
        }
        if (!menu.getCarried().isEmpty()) {
            if (!ItemStack.isSameItemSameComponents(menu.getCarried(), active)) {
                stop(mc,"Unexpected item on cursor.");return;
            }
            // Deliberately click ON the ANVIL, not an empty reward slot.
            clickSlot(mc,menu,ANVIL_SLOT);
            phase=Phase.TYPE;phaseTicks=0;
        } else if (phaseTicks > 60) stop(mc,"Timed out picking up item.");
    }

    private void clickItemReward(Minecraft mc) {
        Screen screen=mc.gui.screen();
        if (screen==null || rewards(screen) || phaseTicks<7) return;
        if (phaseTicks>200) {
            stop(mc,"Item Reward dialog timed out: "+screen.getClass().getSimpleName());return;
        }

        List<AbstractWidget> widgets=new ArrayList<>();
        for (var child:screen.children()) {
            if (child instanceof AbstractWidget w && w.visible && w.active) widgets.add(w);
        }
        AbstractWidget target=null;
        for (AbstractWidget widget:widgets) {
            String label=widget.getMessage().getString().replaceAll("§.", "").toLowerCase(Locale.ROOT).trim();
            if (label.contains("item reward")) { target=widget;break; }
        }

        String title=screen.getTitle().getString().toLowerCase(Locale.ROOT);
        boolean expectedDialog=title.contains("reward") && title.contains("creation");
        if (target==null && expectedDialog) {
            List<AbstractWidget> primary=new ArrayList<>();
            for(AbstractWidget widget:widgets) {
                if (widget instanceof Button &&
                    widget.getWidth()>75 && widget.getY()>screen.height/9 &&
                    widget.getY()<screen.height*3/4 &&
                    !widget.getMessage().getString().equalsIgnoreCase("Back")) primary.add(widget);
            }
            primary.sort(Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));
            if (primary.size()>=2 && Math.abs(primary.get(0).getY()-primary.get(1).getY())<24) {
                target=primary.get(0).getX()<primary.get(1).getX()?primary.get(0):primary.get(1);
            }
        }

        if (target != null) {
            MouseButtonInfo button=new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0);
            MouseButtonEvent event=new MouseButtonEvent(target.getX()+target.getWidth()/2.0,
                                                       target.getY()+target.getHeight()/2.0,button);
            boolean clicked=screen.mouseClicked(event,false);
            if (mc.gui.screen()==screen) screen.mouseReleased(event);
            if (!clicked && target instanceof Button b) {
                b.onPress(button);
                clicked=true;
            }
            if (clicked) {
                say(mc,"Item Reward selected. Waiting for Crate Rewards...");
                phase=Phase.RETURN;phaseTicks=0;return;
            }
        }

        // Extremely narrow fallback: only the confirmed Reward Creation dialog.
        // Click its left action button at the same relative position as in screenshot.
        if (target==null && expectedDialog && phaseTicks>=35) {
            MouseButtonInfo button=new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0);
            MouseButtonEvent event=new MouseButtonEvent(screen.width*0.42,screen.height*0.285,button);
            if (screen.mouseClicked(event,false)) {
                if (mc.gui.screen()==screen) screen.mouseReleased(event);
                say(mc,"Item Reward dialog action sent (fallback).");
                phase=Phase.RETURN;phaseTicks=0;
            } else if (phaseTicks==35) {
                say(mc,"Could not find the action: screen="+screen.getClass().getSimpleName()+
                    ", widgets="+widgets.size()+".");
            }
        }
    }

    private void returned(Minecraft mc) {
        if (phaseTicks>240) { stop(mc,"Crate Rewards did not reopen. Check the dialog.");return; }
        if (!rewards(mc.gui.screen()) || phaseTicks<8) return;
        AbstractContainerMenu menu=menu(mc);
        if (menu==null) return;
        if (!menu.getCarried().isEmpty()) {
            if (!ItemStack.isSameItemSameComponents(menu.getCarried(),active) ||
                sourceSlot<0 || sourceSlot>=menu.slots.size() || !menu.getSlot(sourceSlot).getItem().isEmpty()) {
                stop(mc,"Item still on cursor. Return it to inventory manually.");return;
            }
            clickSlot(mc,menu,sourceSlot);phaseTicks=0;return;
        }
        pending.removeFirst();done++;
        active=ItemStack.EMPTY;sourceSlot=-1;
        say(mc,"Created "+done+" reward(s); "+pending.size()+" remaining.");
        phase=Phase.NEXT;phaseTicks=0;
    }

    private static void clickSlot(Minecraft mc, AbstractContainerMenu menu, int slot) {
        mc.gameMode.handleContainerInput(menu.containerId,slot,0,ContainerInput.PICKUP,mc.player);
    }
    private static AbstractContainerMenu menu(Minecraft mc) {
        return mc.gui.screen() instanceof AbstractContainerScreen<?> screen?screen.getMenu():null;
    }
    private static boolean rewards(Screen screen) {
        return screen instanceof AbstractContainerScreen<?> &&
            screen.getTitle().getString().toLowerCase(Locale.ROOT).contains("crate rewards");
    }
    private void stop(Minecraft mc,String reason) {
        phase=Phase.OFF;pending.clear();active=ItemStack.EMPTY;sourceSlot=-1;say(mc,reason);
    }
    private static void say(Minecraft mc,String msg) {
        if (mc.player!=null) mc.player.sendSystemMessage(Component.literal("[Crate Auto Reward] "+msg));
    }
}
