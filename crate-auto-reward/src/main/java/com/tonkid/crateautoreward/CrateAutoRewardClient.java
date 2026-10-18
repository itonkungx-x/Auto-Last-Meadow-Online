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
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.CycleButton;
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
    private enum Phase { OFF, NEXT, PICKUP, TYPE, RETURN, OPTIONS, OPEN_WEIGHT, SET_WEIGHT, RETURN_OPTIONS, RETURN_LIST }
    private final ArrayDeque<ItemStack> pending = new ArrayDeque<>();
    private final List<ItemStack> seen = new ArrayList<>();
    private Phase phase = Phase.OFF;
    private int phaseTicks = 0, done = 0, skipped = 0, sourceSlot = -1;
    private ItemStack active = ItemStack.EMPTY;
    private KeyMapping toggle, emergency, settingsKey;
    private RuleConfig config = RuleConfig.load();
    private RuleConfig.Rule currentRule;
    private boolean[] prior = new boolean[36];
    private boolean edited = false;
    private int cycles = 0;

    @Override public void onInitializeClient() {
        toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.crateautoreward.toggle", InputConstants.Type.KEYBOARD,
            InputConstants.KEY_F6, KeyMapping.Category.MISC));
        emergency = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.crateautoreward.stop", InputConstants.Type.KEYBOARD,
            InputConstants.KEY_F8, KeyMapping.Category.MISC));
        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.crateautoreward.settings", InputConstants.Type.KEYBOARD,
            InputConstants.KEY_F7, KeyMapping.Category.MISC));
        ScreenEvents.BEFORE_INIT.register((mc, screen, width, height) ->
            ScreenKeyboardEvents.beforeKeyPress(screen).register((s, key) -> {
                if (settingsKey.matches(key)) openSettings(mc);
                else if (emergency.matches(key)) stop(mc, "Stopped.");
                else if (toggle.matches(key)) {
                    if (phase == Phase.OFF) start(mc);
                    else stop(mc, "Stopped by F6.");
                }
            })
        );
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while(settingsKey.consumeClick()) openSettings(mc);
            while(emergency.consumeClick()) if(phase!=Phase.OFF) stop(mc,"Stopped.");
            while(toggle.consumeClick()) {
                if(phase==Phase.OFF) start(mc); else stop(mc,"Stopped.");
            }
            tick(mc);
        });
    }

    private void openSettings(Minecraft mc) {
        if(phase!=Phase.OFF){say(mc,"Stop automation before editing.");return;}
        config=RuleConfig.load();
        mc.gui.setScreen(new RulesScreen(mc.gui.screen(),config));
    }

    private void start(Minecraft mc) {
        config=RuleConfig.load();
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
            case OPTIONS -> options(mc);
            case OPEN_WEIGHT -> openWeight(mc);
            case SET_WEIGHT -> setWeight(mc);
            case RETURN_OPTIONS -> backOptions(mc);
            case RETURN_LIST -> backList(mc);
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
        currentRule=config.match(active);
        for(int i=0;i<36;i++) prior[i]=!menu.getSlot(i).getItem().isEmpty();
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
        if(phaseTicks>250){stop(mc,"Crate Rewards did not return.");return;}
        if(!rewards(mc.gui.screen()) || phaseTicks<12)return;
        AbstractContainerMenu m=menu(mc);
        if(m==null)return;
        if(!m.getCarried().isEmpty()){
            if(!ItemStack.isSameItemSameComponents(m.getCarried(),active) ||
                sourceSlot<0 || sourceSlot>=m.slots.size() || !m.getSlot(sourceSlot).getItem().isEmpty()){
                stop(mc,"Item still held; return manually.");return;
            }
            clickSlot(mc,m,sourceSlot);phaseTicks=0;return;
        }
        if(config.autoApplyRates && currentRule!=null){
            for(int i=0;i<36;i++){
                if(!prior[i] && !m.getSlot(i).getItem().isEmpty()){
                    say(mc,"Configuring rarity="+currentRule.rarity+", weight="+currentRule.weight);
                    clickSlot(mc,m,i);
                    phase=Phase.OPTIONS;phaseTicks=0;return;
                }
            }
            if(phaseTicks<100)return;
            stop(mc,"Reward created, but item slot not identified. Rate needs manual edit.");
            return;
        }
        complete(mc);
    }
    private void options(Minecraft mc){
        if(phaseTicks>140){stop(mc,"Reward Settings not found. Rate needs manual edit.");return;}
        AbstractContainerMenu m=menu(mc);
        if(m==null || m.slots.size()<54 || phaseTicks<7)return;
        if(!m.getSlot(12).getItem().is(Items.GLISTERING_MELON_SLICE)){
            if(phaseTicks>35)stop(mc,"Not ExcellentCrates Reward Settings (slot 12 mismatch).");
            return;
        }
        clickSlot(mc,m,12);
        phase=Phase.OPEN_WEIGHT;phaseTicks=0;
    }
    private void openWeight(Minecraft mc){
        Screen screen=mc.gui.screen();
        if(phaseTicks>140){stop(mc,"Rarity & Weight input did not open.");return;}
        if(screen==null || screen instanceof AbstractContainerScreen<?> || phaseTicks<7)return;
        String title=RuleConfig.normalize(screen.getTitle().getString());
        if(!(title.contains("weight") || title.contains("rari")))return;
        edited=false;cycles=0;phase=Phase.SET_WEIGHT;phaseTicks=0;
    }
    private void setWeight(Minecraft mc){
        Screen screen=mc.gui.screen();
        if(screen==null){stop(mc,"Rate dialog closed unexpectedly.");return;}
        if(phaseTicks>170){stop(mc,"Rate dialog controls not recognized. Set manually.");return;}
        String title=RuleConfig.normalize(screen.getTitle().getString());
        if(!title.contains("weight") && !title.contains("rari")){
            if(phaseTicks>25)stop(mc,"Unexpected rate dialog screen.");
            return;
        }
        List<AbstractWidget> widgets=new ArrayList<>();
        for(var child:screen.children())
            if(child instanceof AbstractWidget w && w.visible && w.active)widgets.add(w);
        if(!edited){
            List<EditBox> fields=new ArrayList<>();
            for(AbstractWidget w:widgets)if(w instanceof EditBox e)fields.add(e);
            if(fields.size()!=1){
                if(phaseTicks>35)stop(mc,"Weight input missing. Please enter rate manually.");
                return;
            }
            fields.get(0).setValue(currentRule.weight);
            edited=true;phaseTicks=0;return;
        }
        CycleButton<?> dropdown=null;
        boolean matched=false;
        for(AbstractWidget w:widgets){
            if(w instanceof CycleButton<?> c){
                dropdown=c;
                if(RuleConfig.normalize(w.getMessage().getString())
                    .contains(RuleConfig.normalize(currentRule.rarity)))matched=true;
            }
        }
        if(!matched){
            if(dropdown==null){
                if(phaseTicks>35)stop(mc,"Cannot recognize Rarity selector. Set manually.");
                return;
            }
            if(cycles++>=24){stop(mc,"Rarity not found in selector. Set manually.");return;}
            clickWidget(mc,screen,dropdown);phaseTicks=0;return;
        }
        if(phaseTicks<7)return;
        for(AbstractWidget w:widgets){
            if(!(w instanceof Button))continue;
            String label=RuleConfig.normalize(w.getMessage().getString());
            if(label.equals("ok") || label.equals("done") ||
               label.contains("confirm") || label.contains("save")){
                clickWidget(mc,screen,w);
                say(mc,"Submitted "+currentRule.rarity+" / "+currentRule.weight+".");
                phase=Phase.RETURN_OPTIONS;phaseTicks=0;return;
            }
        }
        if(phaseTicks>50)stop(mc,"Submit button not found. Set manually.");
    }
    private void backOptions(Minecraft mc){
        if(phaseTicks>140){stop(mc,"Reward Options did not reopen.");return;}
        AbstractContainerMenu m=menu(mc);
        if(m==null || m.slots.size()<54 || phaseTicks<9)return;
        if(!m.getSlot(12).getItem().is(Items.GLISTERING_MELON_SLICE))return;
        clickSlot(mc,m,49);phase=Phase.RETURN_LIST;phaseTicks=0;
    }
    private void backList(Minecraft mc){
        if(phaseTicks>140){stop(mc,"Crate Rewards did not reopen after rate setting.");return;}
        if(!rewards(mc.gui.screen()) || phaseTicks<12)return;
        complete(mc);
    }
    private void complete(Minecraft mc){
        pending.removeFirst();done++;
        active=ItemStack.EMPTY;sourceSlot=-1;currentRule=null;
        say(mc,"Created "+done+" reward(s); "+pending.size()+" remaining.");
        phase=Phase.NEXT;phaseTicks=0;
    }
    private static void clickWidget(Minecraft mc,Screen screen,AbstractWidget widget){
        MouseButtonInfo info=new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0);
        MouseButtonEvent event=new MouseButtonEvent(widget.getX()+widget.getWidth()/2.0,
            widget.getY()+widget.getHeight()/2.0,info);
        boolean handled=screen.mouseClicked(event,false);
        if(mc.gui.screen()==screen)screen.mouseReleased(event);
        if(!handled && widget instanceof Button b)b.onPress(info);
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
