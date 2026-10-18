package com.tonkid.crateautoreward;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.Locale;

public class RulesScreen extends Screen {
    private final Screen parent;
    private final RuleConfig store;
    private EditBox patternBox,rarityBox,weightBox;
    private int page=0,selection=-1;
    private String source="NAME";
    private String message="Rules save automatically. First match has priority.";

    public RulesScreen(Screen parent,RuleConfig store){
        super(Component.literal("Crate Auto Reward Settings"));
        this.parent=parent;this.store=store;
    }
    @Override protected void init(){
        int x=this.width/2-155, top=Math.max(34,this.height/2-110);
        patternBox=this.addRenderableWidget(new EditBox(this.font,x,top+26,138,20,Component.literal("Contains")));
        rarityBox=this.addRenderableWidget(new EditBox(this.font,x+143,top+26,90,20,Component.literal("Rarity ID")));
        weightBox=this.addRenderableWidget(new EditBox(this.font,x+238,top+26,72,20,Component.literal("Weight")));
        patternBox.setMaxLength(72);rarityBox.setMaxLength(32);weightBox.setMaxLength(6);
        patternBox.setValue("STAFF");rarityBox.setValue("MYTHIC");weightBox.setValue("0.01");

        this.addRenderableWidget(Button.builder(Component.literal("Match: "+source),
            b->{source=source.equals("NAME")?"LORE":source.equals("LORE")?"MATERIAL":"NAME";rebuildWidgets();})
            .bounds(x,top+52,104,20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Add / Update"),
            b->submit()).bounds(x+110,top+52,102,20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Remove"),
            b->{if(selection>=0 && selection<store.rules.size()){
                store.rules.remove(selection);selection=-1;store.save();message="Rule removed.";rebuildWidgets();
            }}).bounds(x+217,top+52,93,20).build());

        int first=page*6;
        for(int row=0;row<6;row++){
            int i=first+row;
            if(i>=store.rules.size()) break;
            RuleConfig.Rule rule=store.rules.get(i);
            String label=(selection==i?"> ":"  ")+(i+1)+". "+rule.info();
            if(label.length()>54)label=label.substring(0,53)+"...";
            final int selected=i;
            this.addRenderableWidget(Button.builder(Component.literal(label),
                b->{selection=selected;source=rule.source;rebuildWidgets();
                    patternBox.setValue(rule.contains);rarityBox.setValue(rule.rarity);weightBox.setValue(rule.weight);
                }).bounds(x,top+78+row*22,310,20).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("< Prev"),
            b->{page=Math.max(0,page-1);selection=-1;rebuildWidgets();})
            .bounds(x,top+216,73,20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Next >"),
            b->{page=Math.min(Math.max(0,(store.rules.size()-1)/6),page+1);selection=-1;rebuildWidgets();})
            .bounds(x+79,top+216,73,20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Reset Defaults"),
            b->{store.rules=RuleConfig.defaults().rules;store.save();selection=-1;page=0;message="Defaults restored.";rebuildWidgets();})
            .bounds(x+158,top+216,152,20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Auto-apply rates: "+(store.autoApplyRates?"ON":"OFF")),
            b->{store.autoApplyRates=!store.autoApplyRates;store.save();rebuildWidgets();})
            .bounds(x,top+241,155,20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Done"),
            b->onClose()).bounds(x+161,top+241,149,20).build());
    }
    private void submit(){
        String pattern=patternBox.getValue().trim();
        String rarity=rarityBox.getValue().trim().toUpperCase(Locale.ROOT);
        String weight=weightBox.getValue().trim();
        if(pattern.isEmpty() || rarity.isEmpty() || !RuleConfig.validWeight(weight)){
            message="Enter a pattern, Rarity ID and numeric Weight (0..999999, max 6 chars).";
            return;
        }
        RuleConfig.Rule rule=new RuleConfig.Rule(source,pattern,rarity,weight);
        if(selection>=0 && selection<store.rules.size()) store.rules.set(selection,rule);
        else {store.rules.add(rule);selection=store.rules.size()-1;page=selection/6;}
        store.save();message="Saved. Rules run top to bottom.";rebuildWidgets();
        patternBox.setValue(pattern);rarityBox.setValue(rarity);weightBox.setValue(weight);
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float delta){
        int x=this.width/2-155, top=Math.max(34,this.height/2-110);
        this.renderBackground(graphics,mouseX,mouseY,delta);
        graphics.drawCenteredString(this.font,"Crate Auto Reward | Rules",this.width/2,top-13,0xFFFFFF);
        graphics.drawString(this.font,"Name / Lore / Material",x,top+3,0xAAAAAA,false);
        graphics.drawString(this.font,"Pattern",x,top+15,0xBBBBBB,false);
        graphics.drawString(this.font,"Rarity ID",x+143,top+15,0xBBBBBB,false);
        graphics.drawString(this.font,"Weight",x+238,top+15,0xBBBBBB,false);
        super.render(graphics,mouseX,mouseY,delta);
        graphics.drawCenteredString(this.font,message,this.width/2,top+264,0xE9B76C);
        graphics.drawCenteredString(this.font,"Change Start / Settings / Stop keys in Options > Controls > Key Binds",
            this.width/2,top+278,0xAAAAAA);
    }
    @Override public void onClose(){
        store.save();
        Minecraft.getInstance().setScreen(parent);
    }
}
